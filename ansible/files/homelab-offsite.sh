#!/usr/bin/env bash
# Nightly off-site copy (docs/learn/13-ssd-and-backups.md part D), run by homelab-offsite.service as
# user offsite on the backup store's node. Installed by ansible/playbooks/offsite.yml.
#
#   for each bucket:  rclone copy  store:<bucket> -> offsite:<bucket>   (encrypted on the way out)
#                     rclone cryptcheck                                 (every file there, identical)
#
# copy, not sync: nothing is ever deleted off-site by this job (a deleted or ransomed local backup
# stays safe in R2 until R2's own lifecycle rule expires it). --immutable: a file that already
# exists off-site is never overwritten; if its content changed, that is an error, not an update.
set -euo pipefail
set -a; . /etc/homelab-offsite.env; set +a
RCLONE_CONFIG_OFFSITE_PASSWORD="$(rclone obscure "$CRYPT_PASSWORD")"
RCLONE_CONFIG_OFFSITE_PASSWORD2="$(rclone obscure "$CRYPT_SALT")"
export RCLONE_CONFIG_OFFSITE_PASSWORD RCLONE_CONFIG_OFFSITE_PASSWORD2
unset CRYPT_PASSWORD CRYPT_SALT
state="${STATE_DIRECTORY:-/var/lib/homelab-offsite}"
opts=(--config /dev/null --stats-one-line --stats 0 --transfers 4 --checkers 8 --retries 5 --low-level-retries 10)

summary=""
for b in $BUCKETS; do
  rclone copy "store:$b" "offsite:$b" --immutable "${opts[@]}"
  # proves every local file exists off-site and decrypts to the same content (hashes compared)
  rclone cryptcheck "store:$b" "offsite:$b" --one-way "${opts[@]}"
  n=$(rclone lsf -R --files-only "store:$b" --config /dev/null | wc -l)
  size=$(rclone size "store:$b" --config /dev/null --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["bytes"])')
  summary="$summary $b=${n}files/$((size / 1048576))MiB"
done
echo "$(date +%s)$summary" > "$state/last-ok"
echo "off-site copy ok:$summary"
