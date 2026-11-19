#!/usr/bin/env bash
# Cloudflare R2 settings for the off-site copy -> ansible/secrets/r2.sops.yaml (encrypted).
# docs/learn/13-ssd-and-backups.md part D. Run on node-workstation as the automation user, in the repo:
#
#   scripts/secrets/set-r2.sh
#
# Asks for 4 values (the secret ones without echo), encrypts them at once; nothing is written in
# plain text. Then: commit + push a branch + PR; after the merge host GitOps sets up the job.
set -euo pipefail
cd "$(dirname "$0")/../.."
echo "Cloudflare dashboard -> R2 (see docs/learn/13-ssd-and-backups.md, part D, for where each value is)"
read -r  -p "Account ID (32 hex characters, right side of the R2 overview page): " account_id
read -r  -p "Bucket name [homelab-offsite]: " bucket; bucket="${bucket:-homelab-offsite}"
read -rs -p "Access Key ID of the API token (hidden): " key_id; echo
read -rs -p "Secret Access Key of the API token (hidden): " key_secret; echo
[[ "$account_id" =~ ^[0-9a-f]{32}$ ]] || { echo "Account ID should be 32 hex characters" >&2; exit 1; }
[[ "$bucket" =~ ^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$ ]] || { echo "bucket name: lowercase letters, digits, -" >&2; exit 1; }
[ ${#key_id} -ge 20 ] && [ ${#key_secret} -ge 40 ] || { echo "key id / secret look too short" >&2; exit 1; }
printf '%s\n' "# Cloudflare R2 for the off-site copy (docs/learn/13-ssd-and-backups.md part D). All values encrypted." \
  "account_id: $account_id" "bucket: $bucket" "access_key_id: $key_id" "secret_access_key: $key_secret" \
  | scripts/secrets/write-sops.sh ansible/secrets/r2.sops.yaml
unset key_id key_secret
echo
echo "Next:"
echo "  git switch -c secrets/r2 && git add ansible/secrets/r2.sops.yaml && git commit -m 'feat(secrets): R2 for the off-site copy' && git push -u origin HEAD && git switch main"
echo "  then the PR; after the merge the first off-site copy starts within ~15 min (host GitOps)."
