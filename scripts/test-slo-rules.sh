#!/usr/bin/env bash
# Unit tests for the SLO alert rules (docs/learn/11-slo.md), like Prometheus' `promtool test rules`.
#   scripts/test-slo-rules.sh          (needs docker; CI runs it in validate.yml)
# k8s/monitoring/slo-rules.yaml is a VMRule with Flux escapes ($$ -> $); this script turns it into a
# plain rules file, then runs k8s/monitoring/slo-rules.test.yaml with vmalert-tool.
set -euo pipefail
cd "$(dirname "$0")/.."
VMALERT_TOOL_IMAGE="${VMALERT_TOOL_IMAGE:-victoriametrics/vmalert-tool:v1.155.0}"   # = chart 0.97.1's VictoriaMetrics

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
python3 - "$work" <<'EOF'
import sys, yaml
vmrule = yaml.safe_load(open("k8s/monitoring/slo-rules.yaml"))
plain = yaml.safe_dump({"groups": vmrule["spec"]["groups"]}, sort_keys=False).replace("$$", "$")
open(f"{sys.argv[1]}/slo-rules.yaml", "w").write(plain)
EOF
cp k8s/monitoring/slo-rules.test.yaml "$work/"

# files go in through stdin (tar): works the same on a CI runner and on a Mac with Docker Desktop
tar -C "$work" -c . | docker run --rm -i --entrypoint sh "$VMALERT_TOOL_IMAGE" -c \
  'tar -x -C /tmp && /vmalert-tool-prod unittest --files=/tmp/slo-rules.test.yaml'
