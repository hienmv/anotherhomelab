#!/usr/bin/env bash
# Tests ansible/files/homelab-check.sh with real git repos (a bare "GitHub" + the workstation checkout)
# and a fake ansible-playbook that records WHICH verify.yml it ran.
# docs/design/2026-11-26-check-applied-revision.md. Needs git, flock (util-linux). Runs in CI.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
fails=0
check() { if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1: got '$2', want '$3'"; fails=$((fails+1)); fi; }

T=$(mktemp -d); export HOME=$T/home; mkdir -p "$HOME" "$T/bin"
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t
cat > "$T/bin/ansible-playbook" <<'EOF'
#!/bin/sh
# prints the marker of the verify.yml it was given (run from <checkout>/ansible) and passes
cat playbooks/verify.yml > "$FAKE_RAN"
echo 'ok: [node-workstation] => { "msg": "node-workstation: all checks passed" }'
EOF
chmod +x "$T/bin/ansible-playbook"; export PATH=$T/bin:$PATH FAKE_RAN=$T/ran

# "GitHub" with commit A, the workstation's checkout of it (what the apply last applied)
git init -q --bare -b main "$T/github.git"
git clone -q "$T/github.git" "$T/seed" 2>/dev/null
mkdir -p "$T/seed/ansible/files" "$T/seed/ansible/playbooks"
cp "$here/ansible/files/lib-homelab.sh" "$here/ansible/files/homelab-check.sh" "$T/seed/ansible/files/"
echo "VERIFY-A" > "$T/seed/ansible/playbooks/verify.yml"
git -C "$T/seed" add -A && git -C "$T/seed" commit -qm A && git -C "$T/seed" push -q origin HEAD:main
git clone -q "$T/github.git" "$T/repo"
newcommit() { # $1 marker, $2 age in minutes: a merge to main that the apply has not applied yet
  echo "$1" > "$T/seed/ansible/playbooks/verify.yml"
  GIT_COMMITTER_DATE="$(( $(date +%s) - $2 * 60 )) +0000" git -C "$T/seed" commit -qam "$1"
  git -C "$T/seed" push -q origin HEAD:main
}
run() { out=$(bash "$T/repo/ansible/files/homelab-check.sh" 2>&1); state=$(cut -d' ' -f1 "$HOME/.local/state/homelab/check.state"); }

echo "1. nothing new: checks the applied commit"
run
check "ran verify A" "$(cat "$T/ran")" "VERIFY-A"
check "state ok" "$state" "ok"

echo "2. a merge 5 min ago, not applied yet (the 2026-11-26 false alert)"
newcommit VERIFY-B 5; run
check "still checks A (what is applied), not B" "$(cat "$T/ran")" "VERIFY-A"
check "the checkout did not move" "$(git -C "$T/repo" log -1 --format=%s)" "A"
check "pending reported in the log" "$(echo "$out" | grep -c 'pending: .*merged 5 min ago')" "1"
check "no problem, state ok" "$state" "ok"

echo "3. a merge still not applied after 50 min (3 apply runs missed): the apply is stuck -> a problem"
newcommit VERIFY-B2 50; run
check "problem: host apply has not applied" "$(echo "$out" | grep -c 'host apply has not applied .* for 50 min')" "1"
check "state fail" "$state" "fail"

echo "4. the apply ran (fast-forward, as homelab-apply.sh does): checks B2, pending gone"
git -C "$T/repo" merge -q --ff-only origin/main; run
check "ran verify B2" "$(cat "$T/ran")" "VERIFY-B2"
check "no pending line" "$(echo "$out" | grep -c pending || true)" "0"
check "state ok again" "$state" "ok"

echo "5. GitHub unreachable: a problem, the check still runs on the applied commit"
git -C "$T/repo" remote set-url origin "$T/nowhere.git"; run
check "fetch problem reported" "$(echo "$out" | grep -c 'could not fetch')" "1"
check "still ran verify B2" "$(cat "$T/ran")" "VERIFY-B2"

[ $fails = 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
