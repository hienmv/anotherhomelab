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

## Next parts

| Part | What |
|---|---|
| B | an S3 store on the SSD (outside Kubernetes, so a broken cluster cannot take its backups with it) |
| C | Postgres: continuous WAL archive + nightly base backup to it (point-in-time restore), and a restore drill |
| D | nightly encrypted copy to Cloudflare R2 (off-site: survives fire or theft) |

Back to: [README](README.md)
