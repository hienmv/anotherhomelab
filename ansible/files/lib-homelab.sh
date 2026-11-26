# Shared by the timers' scripts (homelab-check.sh, homelab-apply.sh). Source it, do not run it.
# Settings (never in git): ~/.config/homelab/alerting.env (TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID, ...)

repo_ansible="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$repo_ansible/.." && pwd)"
conf="${HOME}/.config/homelab/alerting.env"
state_dir="${HOME}/.local/state/homelab"; mkdir -p "$state_dir"
[ -f "$conf" ] && . "$conf"
export ANSIBLE_LOG_PATH=/dev/null        # timers keep their own last-*.log; ansible.log is for people

send() {   # Telegram, if configured
  [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${TELEGRAM_CHAT_ID:-}" ] || return 0
  curl -sS -m 20 -o /dev/null "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
    --data-urlencode "chat_id=${TELEGRAM_CHAT_ID}" --data-urlencode "text=$1" || true
}

# One Ansible job at a time (check, apply, backup): the check must not judge a machine half-way
# through an apply. Waits up to $1 seconds (default 25 min), then gives up (returns 1).
take_lock() {
  exec 9>"$state_dir/ansible.lock"
  flock -w "${1:-1500}" 9
}

# GitHub's main, anonymously over HTTPS (public repo: a timer has no ssh-agent). Fetch only: moves
# origin/main, never the checkout. Sets pull_problem="" on success, else a one-line reason.
fetch_main() {
  local url out
  pull_problem=""
  url="$(git -C "$repo" remote get-url origin | sed -E 's#^git@github\.com:#https://github.com/#')"
  if ! out="$(git -C "$repo" fetch -q "$url" "+refs/heads/main:refs/remotes/origin/main" 2>&1)"; then
    pull_problem="repo on $(hostname -s) could not fetch from GitHub: $(printf '%s' "$out" | tr '\n' ' ' | cut -c1-160)"
  fi
}

# Fast-forward to GitHub's main, anonymously over HTTPS (public repo: a timer has no ssh-agent for the
# passphrase-protected key, and reading needs none). Never overwrites local changes.
# Sets pull_problem="" on success, else a one-line reason.
update_repo() {
  local url out
  pull_problem=""
  url="$(git -C "$repo" remote get-url origin | sed -E 's#^git@github\.com:#https://github.com/#')"
  if ! out="$(git -C "$repo" fetch -q "$url" "+refs/heads/main:refs/remotes/origin/main" 2>&1 \
              && git -C "$repo" merge -q --ff-only origin/main 2>&1)"; then
    pull_problem="repo on $(hostname -s) not updated from GitHub: $(printf '%s' "$out" | tr '\n' ' ' | cut -c1-160)"
  fi
}
