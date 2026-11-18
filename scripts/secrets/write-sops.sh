#!/usr/bin/env bash
# Plain YAML on stdin -> encrypted <file> (SOPS, rules in .sops.yaml), docs/learn/12-gitops-secrets.md.
#   ... | scripts/secrets/write-sops.sh k8s/secrets/<ns>/<name>.sops.yaml      prints: written | unchanged
# Unchanged content leaves the file as it is (re-encrypting would change it and show a git diff).
# The plain text exists only in memory (tmpfs) and is deleted at once.
set -euo pipefail
cd "$(dirname "$0")/../.."
out="$1"
: "${SOPS_AGE_KEY_FILE:=$HOME/.config/sops/age/keys.txt}"; export SOPS_AGE_KEY_FILE
tmp="$(mktemp -d "${XDG_RUNTIME_DIR:-/dev/shm}/write-sops.XXXXXX")"; trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/plain.yaml"
if [ -f "$out" ] && sops --decrypt "$out" | python3 -c '
import sys, yaml
a = [d for d in yaml.safe_load_all(open(sys.argv[1])) if d]
b = [d for d in yaml.safe_load_all(sys.stdin) if d]
sys.exit(0 if a == b else 1)' "$tmp/plain.yaml"; then
  echo unchanged; exit 0
fi
mkdir -p "$(dirname "$out")"
sops --encrypt --filename-override "$out" --input-type yaml --output-type yaml "$tmp/plain.yaml" > "$out.new"
mv "$out.new" "$out"
echo written
