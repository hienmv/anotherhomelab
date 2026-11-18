# 13. The SSD on node-8gb, and backups that survive a dead disk

Until now Postgres, metrics, logs and traces lived on **one disk** (node-workstation's), and the
backup archives too. One dead disk = everything gone. This chapter adds a second disk on another
machine and, step by step, real backups. (Stage 1 of the roadmap; parts B-D follow.)

## Part A: the SSD (2026-11-18)

| | |
|---|---|
| Drive | VETESA-64G, 2.5" SATA SSD, in a USB-SATA adapter (Realtek RTL9201, USB 2 speed) |
| Where | node-8gb, through a **powered** USB hub, mounted at `/mnt/ssd` |
| Config | inventory `node-8gb: ssd:` (model, serial, label, mount point) |
| Setup | `ansible-playbook playbooks/ssd.yml` (once with `-e confirm_wipe=<serial>`) |
| Checked by | `verify.yml`: mounted, SMART health, < 80 % full, USB link stable |

### What we learned plugging it in

| Symptom | Cause | Lesson |
|---|---|---|
| `Undervoltage detected!`, `throttled=0x50000`, USB `error -71` | a Pi 4 gives ~1.2 A to all USB ports together; the SSD's start pulled the whole Pi's voltage down | disks on a Pi need their own power: a **powered hub** (or an adapter with a power input) |
| the power check never noticed | it looked only at "under-voltage **now**" and ran without root (`vcgencmd` failed, check silently skipped) | check "has happened since boot" too, and treat "cannot read" as a problem (fixed in `verify.yml`) |
| `sda 0B`, `Read Capacity(10) failed` | the SSD was not fully seated in the adapter | the adapter answering is not the disk answering: ask the drive itself (`smartctl -d sat`) |
| `smartctl -d sat` "unsupported field" | this adapter needs the 12-byte form | `-d sat,12` / `sat,16` (smartctl's other modes) |

### Before trusting a disk: its health

`smartctl -H -A -d sat /dev/sda` (S.M.A.R.T., the drive's own health log):

| Attribute | Means | Ours |
|---|---|---|
| overall-health | the drive's own verdict | PASSED |
| Reallocated / Pending / Uncorrectable | flash cells that failed; > 0 and rising = replace soon | 0 |
| UDMA_CRC_Error_Count | transfer errors: bad cable or adapter | 0 |
| Power_On_Hours | age | 1 h (new) |

`verify.yml` reads these every 15 minutes; a change is a Telegram message.

### Why the playbook looks for a serial number

`/dev/sda` is just "the first USB disk found at this boot": plug in a stick and it may become
`/dev/sdb`. Formatting by `/dev/sdX` is how people erase the wrong disk. `ssd.yml` asks every disk
for its **serial number** and only touches the one in the inventory, and only erases after you type
that serial (`-e confirm_wipe=...`). It mounts by **label** with `nofail`: if the SSD is unplugged
or dead, node-8gb still boots, and `verify.yml` reports the missing mount.

## Part B: the backup store (S3 on the SSD)

```text
 node-workstation                                    node-8gb, SSD /mnt/ssd
 backup.yml (nightly 03:30) ── rclone, S3 ─────────► Garage (systemd service, not a pod)
   age-encrypted archive                              bucket archives  (key: workstation)
 Postgres (part C) ─────────── S3 ─────────────────►  bucket cnpg      (key: cnpg-backup)
                                                      data: /mnt/ssd/backup/garage
```

| Piece | What | Why |
|---|---|---|
| **S3** | the object-storage API made by AWS, now the standard: put / get / list files ("objects") in "buckets" over HTTP | every backup tool speaks it (CNPG's Barman, rclone), and so do clouds (R2): the same backups can move to a cloud unchanged |
| **Garage** v2.5.0 | a small S3 server for self-hosting, ~50 MB RAM | one binary; fine on a Pi |
| **outside Kubernetes** | a plain systemd service on node-8gb | if the cluster breaks, the store you restore it from still works |
| `RequiresMountsFor=/mnt/ssd` | the service only starts when the SSD is mounted | never quietly fills the Pi's SD card |
| **one key per user** | `workstation` may use only `archives`, `cnpg-backup` only `cnpg` | least privilege: one leaked key exposes one bucket. Tested: the workstation key is refused on `cnpg` |
| keys | **declared in git, encrypted**: Postgres' in `k8s/secrets/data/backup-s3.sops.yaml` (Flux gives it to Postgres), the workstation's in `ansible/secrets/backup-store.sops.yaml`. `backup-store.yml` imports them into Garage once and writes the workstation's into `~/.config/homelab/backup-s3.env` (mode 600) for rclone | one source of truth; nobody copies keys from a server ([chapter 14](14-host-gitops.md)) |
| **nightly** | `homelab-backup.timer` runs `backup.yml` at 03:30: archive, copy to `ssd:archives`, delete copies older than 30 days (store and local) | before: only by hand |
| checked by | `verify.yml`: Garage running + healthy; newest archive < 26 h old, also in the store | a failed night is a Telegram message the next morning |

First start failed (2026-11-18): `OutOfMemory error while trying to open LMDB database`. Garage's
default metadata engine, LMDB, reserves 1 TiB of *virtual address space* (not memory) up front; the
Raspberry Pi kernel gives a process only 512 GiB. Locally (Docker on the Mac, a different kernel) it
had worked. Fixed by `db_engine = "sqlite"`, which also copes better with power cuts. Lesson: test on
the real target (same kernel, same CPU) when you can; host GitOps reported the failure and retried
by itself after the fix.

Trust, without a checksum file: Garage publishes none, so the binary's sha256 was pinned after
comparing the download with the binary inside Garage's official container image (two channels,
same hash). A supply-chain habit: verify what you run, from two independent sources if you can.

Honest limit: inside the LAN the store speaks plain HTTP, protected by the keys but not encrypted on
the wire. The archives are age-encrypted anyway; the off-site copy (part D) is encrypted end to end.

Useful commands:

```bash
ssh node-8gb sudo garage status                 # the store, its node, capacity
ssh node-8gb sudo garage bucket info archives   # size, number of objects
set -a; . ~/.config/homelab/backup-s3.env; set +a; rclone ls ssd:archives   # as ops on node-workstation
systemctl list-timers homelab-backup; journalctl -u homelab-backup -n 50
```

## Part C: Postgres, continuously, and a restore drill

```text
 Postgres (pg-1, node-workstation)
   every change -> WAL (write-ahead log, 16 MB segments) ──► Barman Cloud plugin ──► ssd:cnpg/postgres/wals
   every night 02:00 (Tokyo) -> base backup (a full copy) ─────────────────────────► ssd:cnpg/postgres/base
 restore = newest base backup before T + replay the WAL up to T  ->  the database exactly as it was at T
```

| Word | Meaning |
|---|---|
| **WAL** | Postgres writes every change to its write-ahead log first; replaying it reproduces the changes |
| **WAL archiving** | copying each finished WAL segment off the machine. Postgres closes a segment at least every 5 min, so at most ~5 min of changes can be lost (the **RPO**) |
| **base backup** | a full copy of the database files at one moment |
| **PITR** | point-in-time recovery: base backup + WAL up to any chosen moment ("yesterday 14:05, just before the bad import") |
| **Barman Cloud plugin** | CNPG's add-on that uploads WAL and base backups to S3 (`k8s/releases/barman-cloud.yaml`, needs cert-manager) |

Configured in `k8s/data/objectstore.yaml` (ObjectStore `ssd`: bucket `cnpg`, endpoint from site-settings
`BACKUP_STORE_URL`, key from the encrypted Secret `backup-s3`, 30 days kept; ScheduledBackup nightly)
and `k8s/data/postgres.yaml` (`plugins:` turns the archiving on).

Checked by `verify.yml` (`pg-backup:`): WAL archiving works (CNPG's `ContinuousArchiving` condition),
newest base backup < 26 h old.

### The drill: a backup is only real once it has been restored

`ansible-playbook playbooks/pitr-drill.yml` (as `ops`), touching nothing live:

1. one snapshot of the live database gives a time **T** and the row count of every table
2. close the current WAL segment and wait until it is in the store
3. restore into a **new, temporary** cluster `pg-drill`: newest base backup + WAL up to exactly T
4. compare the row counts with step 1 (must be identical), report how long the restore took
5. delete `pg-drill`

`pg-drill` only reads the backups: it has no WAL archiver, so it can never write into them.

### Drill log

| Date | Target T | Restore took | Result |
|---|---|---|---|
| | | | (first drill after part C is deployed) |

## Next parts

| Part | What |
|---|---|
| D | nightly encrypted copy to Cloudflare R2 (off-site: survives fire or theft) |

Back to: [README](README.md)
