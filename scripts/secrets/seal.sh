#!/usr/bin/env bash
# Encrypts live Kubernetes Secrets into git (docs/learn/12-gitops-secrets.md). Run on node-workstation
# as the automation user (needs kubectl, sops, the age key):
#
#   scripts/secrets/seal.sh <namespace>/<name> [...]    -> k8s/secrets/<namespace>/<name>.sops.yaml
#
# Only data/stringData (and a Provider's channel) are encrypted; names stay readable. The plain
# text exists only in memory (tmpfs) and is deleted at once. Then: review `git diff`, commit, PR.
# Change a value later:  sops edit k8s/secrets/<ns>/<name>.sops.yaml   (decrypts in an editor)
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${SOPS_AGE_KEY_FILE:=$HOME/.config/sops/age/keys.txt}"; export SOPS_AGE_KEY_FILE
[ $# -gt 0 ] || { sed -n '2,9p' "$0"; exit 1; }
[ -f .sops.yaml ] || { echo ".sops.yaml missing: run scripts/secrets/bootstrap.sh first" >&2; exit 1; }

tmp="$(mktemp -d "${XDG_RUNTIME_DIR:-/dev/shm}/seal.XXXXXX")"; trap 'rm -rf "$tmp"' EXIT
for ref in "$@"; do
  ns="${ref%%/*}" name="${ref#*/}"
  out="k8s/secrets/$ns/$name.sops.yaml"
  mkdir -p "k8s/secrets/$ns"
  # keep only what defines the secret: type, data, name, namespace (no uid, owner, timestamps)
  kubectl -n "$ns" get secret "$name" -o json \
    | jq '{apiVersion: "v1", kind: "Secret", type: .type, metadata: {name: .metadata.name, namespace: .metadata.namespace}, data: .data}' \
    > "$tmp/plain.yaml"   # JSON is valid YAML
  sops --encrypt --filename-override "$out" --input-type yaml --output-type yaml "$tmp/plain.yaml" > "$out"
  rm -f "$tmp/plain.yaml"
  echo "sealed $ref -> $out"
done
scripts/secrets/index.sh
