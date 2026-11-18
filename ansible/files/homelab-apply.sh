#!/usr/bin/env bash
# Host GitOps (docs/learn/14-host-gitops.md): the machines follow main, like Flux does for the cluster.
# Run by homelab-apply.timer (every 15 min, as the automation user):
#   1. update the repo to GitHub's main (fast-forward only)
#   2. nothing new and the last good run < 6 h ago -> exit (quiet)
#   3. ansible-playbook playbooks/site.yml
#   4. Telegram: what CHANGED (per machine and task), or that it FAILED. Silent when nothing changed.
# By hand: ansible/files/homelab-apply.sh --force    (apply now, even without a new commit)
set -uo pipefail
. "$(dirname "$0")/lib-homelab.sh"

state="$state_dir/apply.state"          # "<commit> <epoch of last run> <ok|fail> <epoch of last message>"
out="$state_dir/last-apply.log"
drift_every=21600                       # 6 h: re-apply even without a new commit (undo hand changes)

take_lock || { echo "another Ansible job is still running; next time"; exit 0; }
update_repo
cd "$repo_ansible"
head="$(git -C "$repo" rev-parse --short HEAD)"
now=$(date +%s)
last_commit=- last_run=0 last_status=fail last_msg=0
[ -f "$state" ] && read -r last_commit last_run last_status last_msg < "$state"

if [ "${1:-}" != "--force" ] && [ -z "$pull_problem" ] && [ "$head" = "$last_commit" ] \
   && [ "$last_status" = ok ] && [ $((now - last_run)) -lt $drift_every ]; then
  exit 0
fi
reason="new commit $head"
if [ "$head" = "$last_commit" ]; then
  reason="drift check at $head"; [ "$last_status" = fail ] && reason="retry at $head"
fi
[ "${1:-}" = "--force" ] && reason="by hand at $head"

ansible-playbook playbooks/site.yml -e key_passphrase= > "$out" 2>&1
rc=$?

# "host: task" for every task that reported changed
changes="$(awk '
  /^TASK \[/ { t = $0; sub(/^TASK \[/, "", t); sub(/\] \**$/, "", t) }
  /^changed: \[/ { h = $2; gsub(/[][]/, "", h); print "- " h ": " t }' "$out" | sort -u)"
host=$(hostname -s)

if [ $rc -eq 0 ] && [ -z "$pull_problem" ]; then
  [ -n "$changes" ] && send "🔧 homelab host apply ($reason): changed
$changes"
  [ "$last_status" = fail ] && [ -z "$changes" ] && send "🟢 homelab host apply ($reason): OK again"
  echo "$head $now ok $now" > "$state"
  echo "apply ok ($reason)${changes:+, changed:
$changes}"
else
  err="${pull_problem:-$(grep -m1 -E '^fatal:|ERROR' "$out" | cut -c1-300)}"
  # tell once per commit, then remind every 6 h while it stays broken
  if [ "$last_status" = ok ] || [ "$head" != "$last_commit" ] || [ $((now - last_msg)) -ge $drift_every ]; then
    send "🔴 homelab host apply FAILED ($reason) on $host:
$err
${changes:+Changed before the failure:
$changes
}Details: sudo -iu ops; less ~/.local/state/homelab/last-apply.log"
    last_msg=$now
  fi
  echo "$head $now fail $last_msg" > "$state"
  echo "apply FAILED ($reason): $err"
fi
exit 0
