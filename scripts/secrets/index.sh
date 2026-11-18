#!/usr/bin/env bash
# Rewrites the resources list of k8s/secrets/kustomization.yaml and k8s/notifications/kustomization.yaml
# from the files present (docs/learn/12-gitops-secrets.md). Safe to run any time.
set -euo pipefail
cd "$(dirname "$0")/../.."

write() {   # <dir> <header comment line>
  local dir="$1" header="$2" files
  files="$(cd "$dir" && find . -name '*.yaml' ! -name kustomization.yaml | sed 's|^\./||' | LC_ALL=C sort)"
  {
    echo "$header"
    echo "# Do not edit this list by hand: scripts/secrets/index.sh rewrites it."
    echo "apiVersion: kustomize.config.k8s.io/v1beta1"
    echo "kind: Kustomization"
    if [ -z "$files" ]; then echo "resources: []"; else echo "resources:"; printf '  - %s\n' $files; fi
  } > "$dir/kustomization.yaml"
  echo "$dir/kustomization.yaml: $(printf '%s\n' $files | grep -c . || true) file(s)"
}
write k8s/secrets "# Secrets, encrypted with SOPS + age, decrypted by Flux (docs/learn/12-gitops-secrets.md)."
write k8s/notifications "# Flux notifications to Telegram (docs/learn/12-gitops-secrets.md)."
