#!/usr/bin/env bash
# Unit tests for the alert rules: SLOs (docs/learn/11-slo.md) and backups (docs/learn/13), like Prometheus' `promtool test rules`.
#   scripts/test-slo-rules.sh          (needs docker; CI runs it in validate.yml)
# k8s/monitoring/slo-rules.yaml is a VMRule with Flux escapes ($$ -> $); this script turns it into a
# plain rules file, then runs k8s/monitoring/slo-rules.test.yaml with vmalert-tool.
set -euo pipefail
cd "$(dirname "$0")/.."
VMALERT_TOOL_IMAGE="${VMALERT_TOOL_IMAGE:-victoriametrics/vmalert-tool:v1.155.0}"   # = chart 0.97.1's VictoriaMetrics

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
# every k8s/monitoring/<name>-rules.yaml (VMRule) -> plain rules file, then every <name>-rules.test.yaml
python3 - "$work" <<'EOF'
import glob, os, sys, yaml
for path in sorted(glob.glob("k8s/monitoring/*-rules.yaml")):
    vmrule = yaml.safe_load(open(path))
    plain = yaml.safe_dump({"groups": vmrule["spec"]["groups"]}, sort_keys=False).replace("$$", "$")
    open(os.path.join(sys.argv[1], os.path.basename(path)), "w").write(plain)
EOF
cp k8s/monitoring/*-rules.test.yaml "$work/"
tests="$(cd "$work" && ls *-rules.test.yaml | sed 's#^#/tmp/#' | paste -sd, -)"

# files go in through stdin (tar): works the same on a CI runner and on a Mac with Docker Desktop
tar -C "$work" -c . | docker run --rm -i --entrypoint sh "$VMALERT_TOOL_IMAGE" -c \
  "tar -x -C /tmp && /vmalert-tool-prod unittest --files=$tests"
