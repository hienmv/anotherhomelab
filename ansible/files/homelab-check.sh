#!/usr/bin/env bash
# Scheduled health check with alerting (systemd timers homelab-check / homelab-check-full, as ops).
#
#   homelab-check.sh          quick: verify.yml without the canary pods   (every 15 min)
#   homelab-check.sh --full   everything, canary included                 (once a day)
#
# - Telegram message only when the state CHANGES (new problems / all good again), plus a reminder
#   every 6 h while it stays broken.
# - healthchecks.io: ping on success, /fail on problems. If the pings STOP (homelab, power or
#   internet down), healthchecks.io alerts from outside: the "dead man's switch".
# Settings (never in git): ~/.config/homelab/alerting.env with
#   TELEGRAM_BOT_TOKEN=...  TELEGRAM_CHAT_ID=...  HEALTHCHECK_URL=https://hc-ping.com/<uuid>
# Missing settings = the check still runs and logs, it just cannot notify.
set -uo pipefail

repo_ansible="$(cd "$(dirname "$0")/.." && pwd)"
conf="${HOME}/.config/homelab/alerting.env"
state_dir="${HOME}/.local/state/homelab"; mkdir -p "$state_dir"
state="$state_dir/check.state"          # "<ok|fail> <epoch of last message> <hash of problems>"
[ -f "$conf" ] && . "$conf"

args=(--skip-tags containers); [ "${1:-}" = "--full" ] && args=()
# Scheduled runs (~100 a day) stay out of ansible/logs/ansible.log, which is for runs by a person.
# The latest output is kept instead (overwritten each run): ~/.local/state/homelab/last-check.log
out="$state_dir/last-check.log"
export ANSIBLE_LOG_PATH=/dev/null

cd "$repo_ansible"
ansible-playbook playbooks/verify.yml "${args[@]}" > "$out" 2>&1
rc=$?

# one line per host with problems, from the final assert ("N problem(s) on host: a | b")
problems="$(grep -oE '"[0-9]+ problem\(s\) on [^"]+"' "$out" | tr -d '"' | sed 's/^/- /')"
if [ $rc -ne 0 ] && [ -z "$problems" ]; then
  problems="- verify.yml failed before its checks finished (exit $rc): $(grep -m1 -E 'ERROR|fatal' "$out" | cut -c1-200)"
fi

now=$(date +%s)
last_status=ok last_sent=0 last_hash=-
[ -f "$state" ] && read -r last_status last_sent last_hash < "$state"
hash=$(printf '%s' "$problems" | sha256sum | cut -c1-16)

send() {   # Telegram, if configured
  [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ] || return 0
  curl -sS -m 20 -o /dev/null "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
    --data-urlencode "chat_id=${TELEGRAM_CHAT_ID}" --data-urlencode "text=$1" || true
}
ping_hc() {   # healthchecks.io, if configured ($1 = "" or "/fail")
  [ -n "${HEALTHCHECK_URL:-}" ] || return 0
  curl -sS -m 15 -o /dev/null --retry 3 --data-raw "${2:-ok}" "${HEALTHCHECK_URL}$1" || true
}

host=$(hostname -s)
if [ $rc -eq 0 ]; then
  ping_hc "" "all checks passed"
  if [ "$last_status" = fail ]; then send "🟢 homelab ($host): all checks passed again"; fi
  echo "ok $now -" > "$state"
  echo "all checks passed"
else
  ping_hc "/fail" "$problems"
  if [ "$last_status" = ok ] || [ "$hash" != "$last_hash" ] || [ $((now - last_sent)) -ge 21600 ]; then
    send "🔴 homelab ($host): problems found
$problems

Details: sudo -iu ops; cd ~/workspace/source/homelab/ansible && ansible-playbook playbooks/verify.yml"
    last_sent=$now
  fi
  echo "fail $last_sent $hash" > "$state"
  echo "$problems"
fi
exit 0
