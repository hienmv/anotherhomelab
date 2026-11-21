# 13. The SSD on node-8gb, and backups that survive a dead disk

Until now Postgres, metrics, logs and traces lived on **one disk** (node-workstation's), and the
backup archives too. One dead disk = everything gone. This chapter adds a second disk on another
machine and, step by step, real backups. (Stage 1 of the roadmap; parts B-D follow.)

## The 3-2-1 rule: how many copies, where

The rule backup professionals have used for decades (and still the baseline in cloud guidance):

| Number | Rule | Protects against |
|---|---|---|
| **3** | keep **three copies** of your data: the live one + two backups | one copy failing is normal; two failing at once is rare |
| **2** | on **two different kinds of storage** (different disks, machines, or media) | one kind of failure taking out every copy: one disk model's bug, one machine's power supply, one filesystem |
| **1** | **one copy off-site**, somewhere else entirely | fire, flood, theft, a power surge, a burglar taking the box with both disks |

Modern versions add two letters, **3-2-1-1-0**:

| | Rule | Why |
|---|---|---|
| **1** | one copy **immutable or offline**: nobody, not even an admin or malware, can delete it before its time | ransomware deletes backups first; a mistake (`rm`, a wrong script) can too |
| **0** | **zero errors when restoring**: test restores regularly | a backup that was never restored is a hope, not a backup (our drills) |

How this homelab measures up:

| | Copy | Where | Kind of storage |
|---|---|---|---|
| 1 | live Postgres | node-workstation, internal NVMe | |
| 2 | WAL + nightly base backups, nightly archives | node-8gb, USB SSD (Garage) | another disk, another machine ✅ |
| 3 | encrypted copy (part D, nightly 04:30) | Cloudflare R2, another city | another provider, **off-site** ✅ (first copy 2026-11-21: 37 files / 13 MiB Postgres, 4 archives, cryptcheck identical) |
| +1 | R2 only adds, never deletes; R2 itself expires objects after 35 days | | protects against deleting both local copies (close to "immutable") |
| 0 | PITR drill (restore to a chosen second), off-site check (part D) | | tested ✅ |

Before this chapter: 2 copies (live + an archive on the **same** disk), 0 off-site, never restored.

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

Measured 2026-11-19: a closed WAL segment is on the SSD **7 s** later (so the RPO is set by the 5-min segment
timer, not by the upload). The first nightly base backup failed ("plugin is not available"): it
started while Postgres was restarting with the plugin; all 9 archive failures are from those minutes,
none since.

Checked by `verify.yml` (`pg-backup:`): WAL archiving works (CNPG's `ContinuousArchiving` condition),
newest base backup < 26 h old.

### The drill: a backup is only real once it has been restored

`ansible-playbook playbooks/pitr-drill.yml` (as `ops`), touching nothing live:

1. one snapshot of the live database gives a time **T** and the row count of every table
2. write one tiny marker transaction (WAL only, no table touched), close the WAL segment, wait until
   exactly that segment is in the store. First version without the marker timed out (2026-11-19): on
   an idle database `pg_switch_wal()` closes nothing, and a restore "to T" needs a transaction after T
3. restore into a **new, temporary** cluster `pg-drill`: newest base backup + WAL up to exactly T
4. compare the row counts with step 1 (must be identical), report how long the restore took
5. delete `pg-drill`

`pg-drill` only reads the backups: it has no WAL archiver, so it can never write into them.

### Try it yourself: undo an "accident"

Restore to a moment **you** choose, into a side copy you can look at (the live database stays as it is):

```bash
P="kubectl -n data exec -i pg-1 -c postgres -- psql -U postgres"
$P -c "create database playground"                      # a scratch database, not TicketLite's
$P -d playground -c "create table notes(t text); insert into notes values ('one'),('two'),('three')"
sleep 5; date -u +%Y-%m-%dT%H:%M:%SZ                     # note this time: "before the accident"
sleep 5; $P -d playground -c "delete from notes"         # the accident: all rows gone
$P -d playground -c "select count(*) from notes"         # 0 in the live database

cd ~/workspace/source/homelab/ansible
ansible-playbook playbooks/pitr-drill.yml -e target_time=<the time you noted> -e keep=true
kubectl -n data exec -i pg-drill-1 -c postgres -- psql -U postgres -d playground -c "select * from notes"   # one, two, three
kubectl -n data delete cluster pg-drill                  # the side copy
$P -c "drop database playground"                         # tidy up
```

In a real incident you would now copy the lost rows from the side copy back into the live database
(`pg_dump -t notes` from `pg-drill`, `psql` into `pg`), never overwrite the live database blindly.
The target must be after the oldest base backup still kept (`kubectl -n data get backups`).

### Drill log

| Date | Target T | Restore took | Result |
|---|---|---|---|
| 2026-11-19 | 2026-11-19T05:39:48.749Z (14:39:48 JST) | **54 s** | ✅ 4 tables identical to the live database at T (concerts 7, flyway_schema_history 2, orders 27, users 3); 1 base backup + WAL. A first attempt the same day timed out waiting for the WAL (idle database, fixed: marker transaction) |

## Part D: the off-site copy (Cloudflare R2)

```text
 node-8gb                                                          Cloudflare R2 (off-site)
 Garage  store:cnpg, store:archives ──► rclone crypt (encrypt) ──► bucket homelab-offsite
         (read-only key "offsite")      nightly 04:30, user offsite   scrambled content + names
                                        then cryptcheck: every file there, identical
```

| Choice | Why |
|---|---|
| **R2** | your Cloudflare account exists already; free tier 10 GB storage, **no download fees** (a restore costs nothing) |
| **encrypted before upload** (rclone crypt) | Cloudflare only ever sees scrambled data and file names (tested: the plain text appears nowhere in R2's data) |
| **copy, never delete**; `--immutable` | if the local backups are deleted (mistake, ransomware), the off-site copy is not (tested). A file that exists off-site is never overwritten |
| **R2 lifecycle rule: delete after 35 days** | R2 removes old copies itself, so storage stays small; a few days longer than the local 30 |
| **read-only Garage key** | the job can read the backups, never change or delete them (Ansible re-applies exactly "read" every 6 h) |
| **`cryptcheck` after every copy** | proves every local file exists off-site and decrypts to the same content |
| checked by | `verify.yml` (`offsite:`): last successful copy < 26 h ago |

### Setting it up (the only manual part: Cloudflare's credentials)

1. **Cloudflare dashboard → R2 Object Storage.** First time: enable R2 (Cloudflare asks for a payment
   method even for the free tier; under 10 GB nothing is charged).
2. **Create bucket** → name `homelab-offsite`, location Automatic → Create.
3. In the bucket → **Settings → Object lifecycle rules → Add rule**: "delete objects 35 days after
   they were uploaded", applied to the whole bucket.
4. R2 overview → **Manage R2 API tokens → Create API token**: permission **Object Read & Write**,
   **Specify bucket: homelab-offsite** (only this one), no expiry or a long one → Create. Copy the
   **Access Key ID** and **Secret Access Key** (shown once) and note the **Account ID** (R2 overview).
5. On node-workstation as `ops`, in the repo: `scripts/secrets/set-r2.sh` (asks for the 4 values,
   hidden input, writes `ansible/secrets/r2.sops.yaml` encrypted), then commit, push a branch, PR.
6. After the merge: host GitOps installs the job within ~15 min and starts the first copy.
7. **Password manager:** `sops -d ansible/secrets/offsite.sops.yaml` (as `ops`) shows the crypt
   **password** and **salt**. Store both: if node-workstation and the SSD are gone, they and the
   age key are what turn the R2 copy back into data.

### What went wrong first (2026-11-21), and what changed

1. The apply failed with a **hidden** error: the decrypted secrets were stored as a fact named `off`,
   and YAML 1.1 reads a bare `off` (also `on`, `yes`, `no`) as **false**. `no_log` (which protects the
   secrets) also hid the error. Now: plain names, and a rescue that names the cause without values.
2. Then the job failed at 04:30 and 11:37: a blind search-and-replace during fix 1 had turned R2's
   address into `<account>.r2_secrets.cloudflarestorage.com` (no such host). rclone reported it as
   "is a file not a directory", which pointed the wrong way.
3. Now the apply runs a **smoke test** right after writing the settings: it lists the store and the R2
   bucket and does a `--dry-run` of the exact copy. A wrong setting fails the apply within minutes
   (with rclone's error: addresses, never keys), not the nightly job hours later.

Lessons: never rename by blind text replacement in config that contains hostnames; test a change
where it runs (the local test used Garage as a stand-in, not R2's real address); verify right after
configuring, not at the next scheduled run.

### Restoring from R2 (if both local copies are gone)

```bash
# with the env settings of the job (the crypt password/salt from the password manager):
rclone copy offsite:cnpg     <new store>:cnpg       # Postgres WAL + base backups, decrypted
rclone copy offsite:archives <new store>:archives   # nightly archives (still age-encrypted)
# then: Postgres restore (pitr-drill.yml mechanism / restore.yml), archives with the age key
```

## Watching the backups (observability)

Checks say "OK now". Observability also keeps the **history**: when each backup ran, how long, how
big, how far behind; graphs for "is it getting slower or bigger?", alerts on the **promises**.

| Backup | Metrics from | Where they come from |
|---|---|---|
| Postgres WAL (continuous) | `cnpg_pg_stat_archiver_*`, `cnpg_collector_pg_wal_archive_status` | CNPG's exporter in pg-1 (already scraped) |
| Postgres base (nightly) | `barman_cloud_cloudnative_pg_io_last_available_backup_timestamp` or `cnpg_collector_last_available_backup_timestamp` | the plugin / CNPG |
| nightly archive (node-workstation), off-site (node-8gb) | `homelab_backup_last_success_timestamp_seconds{backup=...}`, `..._last_status`, `..._last_duration_seconds`, `..._last_size_bytes`, `homelab_backup_offsite_bucket_bytes{bucket}` | the jobs themselves: `homelab-backup-metrics` runs before and after each job (systemd `ExecStartPre` / `ExecStopPost`, so a **failed** run is recorded too) and writes a `.prom` file to `/var/lib/node_exporter/textfile`; node-exporter publishes it (its **textfile collector**, the standard way for jobs that are not always running) |

Grafana → Dashboards → Apps → **Backups**: time since each backup's last success, WAL waiting to be
uploaded, WAL archived per hour, job durations, sizes, off-site bytes per bucket, alerts firing.

**Alerts on the promises** (`k8s/monitoring/backup-rules.yaml`, label `slo: backup-rpo`, so they go
to Telegram through the existing route):

| Alert | Promise broken |
|---|---|
| `BackupWALNotArchived` (critical) | finished WAL waits > 15 min: "lose at most ~5 min" no longer holds |
| `BackupWALArchiveFailing` | archive attempts failing (Postgres retries; warning before the promise breaks) |
| `BackupBaseStale` | no full Postgres copy for > 26 h |
| `BackupJobFailed` / `BackupJobStale` | the nightly archive or the off-site copy failed / no success for > 26 h |
| `BackupJobMissing` | the archive job's metrics never appeared for 26 h |

Why "WAL **waiting**" and not "time since last upload": on an idle database nothing is uploaded for
hours, and that is fine. What breaks the promise is a *finished* segment that does not get uploaded.

Tested like the SLO rules (`backup-rules.test.yaml`, CI). The tests found two real mistakes before
anything was deployed: VictoriaMetrics' `increase()` counts a counter's first value (the 9 old
failures looked new: `increase_prometheus()` now), and test data with gaps hid the "stale" alerts.

**Logs** of the host jobs: `journalctl -u homelab-backup` (node-workstation), `journalctl -u
homelab-offsite` and `-u garage` (node-8gb). They are not yet in VictoriaLogs (it collects pod logs);
shipping the hosts' journald there is a later step.

## Next parts

| Part | What |
|---|---|

Back to: [README](README.md)
