#!/usr/bin/env bash
# Results of a backup job as metrics (docs/learn/13-ssd-and-backups.md, "Watching the backups").
# Called by the job's systemd unit, so a FAILED run is recorded too:
#   ExecStartPre=/usr/local/bin/homelab-backup-metrics start <job>
#   ExecStopPost=/usr/local/bin/homelab-backup-metrics stop  <job> [<glob of the produced file>]
# Writes <dir>/homelab_backup_<job>.prom; node-exporter's textfile collector publishes it:
#   homelab_backup_last_run_timestamp_seconds{backup}       when it last ended
#   homelab_backup_last_success_timestamp_seconds{backup}   when it last ended WELL (kept across failures)
#   homelab_backup_last_status{backup}                      1 ok, 0 failed
#   homelab_backup_last_duration_seconds{backup}
#   homelab_backup_last_size_bytes{backup}                  size of the newest produced file (if a glob is given)
set -uo pipefail
action="$1" job="$2" glob="${3:-}"
dir="${TEXTFILE_DIR:-/var/lib/node_exporter/textfile}"
f="$dir/homelab_backup_${job}.prom"
started="$dir/.homelab_backup_${job}.started"

case "$action" in
  start) date +%s > "$started"; exit 0;;
  stop)  ;;
  *) echo "usage: $0 start|stop <job> [glob]" >&2; exit 2;;
esac

now=$(date +%s)
t0=$(cat "$started" 2>/dev/null || echo "$now")
# systemd sets SERVICE_RESULT for ExecStopPost: "success" or the kind of failure
status=0; [ "${SERVICE_RESULT:-}" = success ] && status=1
last_ok=$(awk '/^homelab_backup_last_success_timestamp_seconds/ {print $2}' "$f" 2>/dev/null)
[ "$status" = 1 ] && last_ok=$now
size=""
if [ -n "$glob" ]; then
  newest=$(ls -t $glob 2>/dev/null | head -1)
  [ -n "$newest" ] && size=$(stat -c %s "$newest")
fi

tmp=$(mktemp "$dir/.tmp.XXXXXX")
{
  echo "# HELP homelab_backup_last_run_timestamp_seconds When the backup job last ended."
  echo "# TYPE homelab_backup_last_run_timestamp_seconds gauge"
  echo "homelab_backup_last_run_timestamp_seconds{backup=\"$job\"} $now"
  echo "# HELP homelab_backup_last_status 1 = the last run succeeded, 0 = it failed (${SERVICE_RESULT:-unknown})."
  echo "# TYPE homelab_backup_last_status gauge"
  echo "homelab_backup_last_status{backup=\"$job\"} $status"
  echo "# HELP homelab_backup_last_duration_seconds How long the last run took."
  echo "# TYPE homelab_backup_last_duration_seconds gauge"
  echo "homelab_backup_last_duration_seconds{backup=\"$job\"} $((now - t0))"
  if [ -n "${last_ok:-}" ]; then
    echo "# HELP homelab_backup_last_success_timestamp_seconds When the backup job last succeeded."
    echo "# TYPE homelab_backup_last_success_timestamp_seconds gauge"
    echo "homelab_backup_last_success_timestamp_seconds{backup=\"$job\"} $last_ok"
  fi
  if [ -n "$size" ]; then
    echo "# HELP homelab_backup_last_size_bytes Size of the newest file the job produced."
    echo "# TYPE homelab_backup_last_size_bytes gauge"
    echo "homelab_backup_last_size_bytes{backup=\"$job\"} $size"
  fi
} > "$tmp"
chmod 644 "$tmp"
mv -f "$tmp" "$f"      # atomic: node-exporter never reads a half-written file
