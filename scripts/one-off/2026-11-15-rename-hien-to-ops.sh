#!/usr/bin/env bash
# One-off (2026-11-15): on node-workstation, turn the account `hien` (which ran everything) into the
# service account `ops`, and make `ethan` the human admin. Kept in git as a record.
#
# Run as ethan, with hien fully LOGGED OUT (desktop and SSH):   sudo bash 2026-11-15-rename-hien-to-ops.sh
#
#   hien (1001) -> ops (2000/2000), home /home/hien -> /home/ops, password locked, hidden from the
#   login screen, passwordless sudo via /etc/sudoers.d/ops, removed from human groups (sudo, ...).
#   The human key to the Pis (id_homelab) and the Mac's key (authorized_keys) move to ethan.
set -euo pipefail
OLD=hien NEW=ops UID_NEW=2000 HUMAN=ethan
[ "$(id -u)" = 0 ] || { echo "run with sudo"; exit 1; }
id "$HUMAN" >/dev/null && id -nG "$HUMAN" | grep -qw sudo || { echo "$HUMAN missing or not in group sudo"; exit 1; }
if ! id "$OLD" >/dev/null 2>&1; then
  id "$NEW" >/dev/null 2>&1 && { echo "already done: $(id $NEW)"; exit 0; }
  echo "neither $OLD nor $NEW exists"; exit 1
fi
[ "$(logname 2>/dev/null || echo "$SUDO_USER")" != "$OLD" ] || { echo "you are logged in as $OLD: log in as $HUMAN"; exit 1; }

echo "1/8 end every $OLD session"
loginctl terminate-user "$OLD" 2>/dev/null || true; sleep 3
pkill -KILL -u "$OLD" 2>/dev/null || true; sleep 1
! pgrep -u "$OLD" >/dev/null || { echo "$OLD still has processes:"; ps -u "$OLD"; exit 1; }

echo "2/8 rename user, group and home"
usermod -l "$NEW" -d "/home/$NEW" -m "$OLD"
groupmod -n "$NEW" "$OLD"
OLD_UID=$(id -u "$NEW"); OLD_GID=$(id -g "$NEW")

echo "3/8 fixed IDs $UID_NEW/$UID_NEW (was $OLD_UID/$OLD_GID)"
groupmod -g "$UID_NEW" "$NEW"
usermod -u "$UID_NEW" -g "$UID_NEW" "$NEW"
chown -R "$NEW:$NEW" "/home/$NEW"
find / -xdev \( -uid "$OLD_UID" -o -gid "$OLD_GID" \) -not -path '/proc/*' -exec chown -h "$UID_NEW:$UID_NEW" {} + 2>/dev/null || true

echo "4/8 service account: no password, not a human (groups, login screen)"
usermod -c "homelab automation" -s /bin/bash -p '!' "$NEW"
for g in sudo lpadmin cdrom dip plugdev; do gpasswd -d "$NEW" "$g" >/dev/null 2>&1 || true; done
mkdir -p /var/lib/AccountsService/users
printf '[User]\nSystemAccount=true\n' > "/var/lib/AccountsService/users/$NEW"
rm -f "/var/lib/AccountsService/users/$OLD"

echo "5/8 passwordless sudo for $NEW"
echo "$NEW ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/$NEW"; chmod 0440 "/etc/sudoers.d/$NEW"
visudo -cf "/etc/sudoers.d/$NEW" >/dev/null
rm -f "/etc/sudoers.d/$OLD"

echo "6/8 human key (id_homelab) and the Mac's login key move to $HUMAN"
H=/home/$HUMAN; install -d -m 700 -o "$HUMAN" -g "$HUMAN" "$H/.ssh"
for f in id_homelab id_homelab.pub; do
  [ -f "/home/$NEW/.ssh/$f" ] && mv "/home/$NEW/.ssh/$f" "$H/.ssh/$f"
done
if [ -f "/home/$NEW/.ssh/authorized_keys" ]; then
  cat "/home/$NEW/.ssh/authorized_keys" >> "$H/.ssh/authorized_keys"; sort -u -o "$H/.ssh/authorized_keys" "$H/.ssh/authorized_keys"
  rm -f "/home/$NEW/.ssh/authorized_keys"      # ops has no inbound SSH: it runs locally
fi
chown -R "$HUMAN:$HUMAN" "$H/.ssh"; chmod 600 "$H/.ssh/"id_homelab "$H/.ssh/authorized_keys" 2>/dev/null || true

echo "7/8 paths inside ops's files"
sed -i "s#/home/$OLD/#/home/$NEW/#g" "/home/$NEW/.bashrc" 2>/dev/null || true

echo "8/8 result"
id "$NEW"; id "$HUMAN"; ls -la "$H/.ssh"; ls "/home/$NEW/.ssh"
echo "done: now run workstation-prepare.yml and access.yml as $NEW (sudo -iu $NEW)"
