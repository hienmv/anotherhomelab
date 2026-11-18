#!/usr/bin/env bash
# ONE-TIME move of the hand-made secrets into git, encrypted (docs/learn/12-gitops-secrets.md).
# Run on node-workstation as the automation user, in the repo, on an up-to-date main:
#
#   scripts/secrets/bootstrap.sh
#
# 1. .sops.yaml with the age PUBLIC key (who can decrypt: whoever has the private key)
# 2. Secret flux-system/sops-age = the age PRIVATE key, so Flux can decrypt (never in git)
# 3. the live secrets below -> k8s/secrets/<ns>/<name>.sops.yaml (encrypted)
# 4. Alertmanager config + Flux's Telegram provider from alerting.env (playbooks/alerting.yml)
# 5. a branch with all that, pushed: then a PR (CI checks it), merge, Flux takes over.
# Nothing is applied to the cluster except step 2; the running secrets do not change.
set -euo pipefail
cd "$(dirname "$0")/../.."
key="${SOPS_AGE_KEY_FILE:-$HOME/.config/sops/age/keys.txt}"
export SOPS_AGE_KEY_FILE="$key"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/homelab.yaml}"

# Secrets made by hand (backup.yml hand_secrets) that Flux can own. NOT here, on purpose:
#   flux-system/flux-system  Flux's own git key: Flux needs it BEFORE it can read git (backup only)
#   data/pg-app, apps/app-env  database password: made per cluster by CNPG / restore.yml
secrets=(
  infra/tunnel-creds
  data/redis
  apps/ticketlite
  apps/ghcr-pull
  monitoring/grafana-admin
  monitoring/grafana-lan-tls
)

[ -f "$key" ] || { echo "age key $key not found (password manager -> that file, chmod 600)" >&2; exit 1; }
[ "$(git branch --show-current)" = main ] || { echo "run on main (git switch main && git pull)" >&2; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "uncommitted changes in the repo: commit or stash first" >&2; exit 1; }
git pull --ff-only -q

pub="$(age-keygen -y "$key")"
echo "1. .sops.yaml (public key $pub)"
cat > .sops.yaml <<EOF
# SOPS: which files are encrypted, which fields, for whom (docs/learn/12-gitops-secrets.md).
# age public key = recipient; the private key is in the password manager + on node-workstation
# (~/.config/sops/age/keys.txt) + in the cluster (Secret flux-system/sops-age). Written by
# scripts/secrets/bootstrap.sh.
creation_rules:
  - path_regex: k8s/(secrets|notifications)/.*\.sops\.yaml\$
    encrypted_regex: ^(data|stringData|channel)\$
    age: $pub
EOF

echo "2. Secret flux-system/sops-age (Flux's decryption key)"
kubectl -n flux-system create secret generic sops-age --from-file=age.agekey="$key" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

echo "3. sealing ${#secrets[@]} secrets"
scripts/secrets/seal.sh "${secrets[@]}"

echo "4. Alertmanager config + Flux Telegram provider (from alerting.env)"
(cd ansible && ansible-playbook playbooks/alerting.yml)

echo "5. branch + push"
branch="secrets/sops-$(date +%Y%m%d)"
git switch -q -c "$branch"
git add .sops.yaml k8s/secrets k8s/notifications
echo "--- files (values are ENC[...]; check no plain value is visible):"
git diff --cached --stat
git -c user.name="${GIT_AUTHOR_NAME:-homelab ops}" -c user.email="${GIT_AUTHOR_EMAIL:-ops@node-workstation}" \
  commit -q -m "feat(secrets): hand-made secrets into git, encrypted with SOPS + age"
git push -q -u origin "$branch"
git switch -q main
echo
echo "Done. Branch $branch pushed. Next: open the PR (or tell Claude), wait for the checks, merge."
