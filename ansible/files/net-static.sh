#!/usr/bin/env bash
# Put the host's static IP on exactly one link (NetworkManager), values from the Ansible inventory.
# docs/design/2026-11-26-workstation-ethernet.md
#   wifi:     static IP on Wi-Fi profile <wifi-profile> (default: the active one), Wi-Fi autoconnects;
#             the wired profile is removed.
#   ethernet: static IP on profile `homelab-wired`; every other Ethernet profile on that port removed;
#             Wi-Fi OFF (DHCP, no autoconnect: `nmcli connection up <wifi-profile>` is the way back in).
#
# Switching to Ethernet is "commit confirmed" (like `netplan try`), run by systemd 3 s later so the
# SSH session running this returns first:
#   1. bring up homelab-wired (Wi-Fi still holds the IP meanwhile)
#   2. confirm: the static IP is on the Ethernet port AND the gateway answers through it (20 s)
#   3. yes: Wi-Fi down.  no: ROLL BACK (static IP back on Wi-Fi) and leave a marker; later runs then
#      fail loudly instead of retrying every 15 min, until a person removes the marker.
# Prints CHANGED/UNCHANGED, and RECONNECT when links are switched.
#
# Usage: net-static.sh <wifi|ethernet> <ip> <prefix> <gateway> <dns> [<wifi-profile>]
set -euo pipefail
# Never `cmd | grep -q` here: grep -q exits at the first match, cmd then gets SIGPIPE, and pipefail
# turns that into a failure (found by scripts/test-net-static.sh). grep ... >/dev/null reads all input.
link=$1 ip=$2 prefix=$3 gw=$4 dns=$5 wifi=${6:-}
want="manual|$ip/$prefix|$gw|$dns"
state_dir=${HOMELAB_NET_STATE:-/var/lib/homelab}
marker=$state_dir/net-ethernet-rolled-back

wired_if=$(ls "${HOMELAB_NET_SYS:-/sys/class/net}" | grep -E '^(en|eth)' | head -1 || true)
if [ -n "$wifi" ]; then
  nmcli -g NAME connection show | grep -xF "$wifi" >/dev/null || { echo "Wi-Fi profile not found: $wifi" >&2; exit 1; }
else
  wifi=$(nmcli -t -f NAME,TYPE connection show --active | awk -F: '$2=="802-11-wireless"{print $1; exit}')
fi

get()        { nmcli -g ipv4.method,ipv4.addresses,ipv4.gateway,ipv4.dns connection show "$1" | paste -sd'|'; }
set_static() { nmcli connection modify "$1" ipv4.method manual ipv4.addresses "$ip/$prefix" ipv4.gateway "$gw" ipv4.dns "$dns"; }
set_dhcp()   { nmcli connection modify "$1" ipv4.method auto ipv4.addresses "" ipv4.gateway "" ipv4.dns ""; }
has_wired()  { nmcli -g NAME connection show | grep -x homelab-wired >/dev/null; }
is_active()  { nmcli -g NAME connection show --active | grep -xF "$1" >/dev/null; }
autoconn()   { nmcli -g connection.autoconnect connection show "$1"; }
# Ethernet profiles bound to the port other than ours (e.g. NetworkManager's "Wired connection 1")
other_wired() {
  nmcli -t -f NAME,TYPE connection show | awk -F: '$2=="802-3-ethernet" && $1!="homelab-wired"{print $1}' |
    while IFS= read -r c; do
      dev=$(nmcli -g connection.interface-name connection show "$c")
      { [ -z "$dev" ] || [ "$dev" = "$wired_if" ]; } && printf '%s\n' "$c"
    done
}
q() { printf "'%s'" "${1//\'/}"; } # quote for the generated switch script

changed=0; switch=""; wired_mod=0
case "$link" in
  wifi)
    [ -n "$wifi" ] || { echo "no Wi-Fi profile; set wifi_profile in the inventory" >&2; exit 1; }
    if [ "$(get "$wifi")" != "$want" ] || [ "$(autoconn "$wifi")" != yes ]; then
      set_static "$wifi"; nmcli connection modify "$wifi" connection.autoconnect yes; changed=1
      switch="nmcli connection up $(q "$wifi") || true"
    fi
    if has_wired; then nmcli connection delete homelab-wired >/dev/null; changed=1; fi
    rm -f "$marker"
    ;;
  ethernet)
    [ -n "$wired_if" ] || { echo "no Ethernet interface found" >&2; exit 1; }
    if [ -e "$marker" ]; then
      echo "the switch to Ethernet was rolled back ($(cat "$marker")): the gateway did not answer over $wired_if." >&2
      echo "Check the cable/port, then: sudo rm $marker (the next run tries again)." >&2
      exit 1
    fi
    if ! has_wired; then
      nmcli connection add type ethernet con-name homelab-wired ifname "$wired_if" >/dev/null; changed=1
    fi
    if [ "$(get homelab-wired)" != "$want" ] || [ "$(autoconn homelab-wired)" != yes ]; then
      set_static homelab-wired
      nmcli connection modify homelab-wired connection.autoconnect yes connection.autoconnect-priority 100
      changed=1; wired_mod=1
    fi
    while IFS= read -r c; do
      [ -n "$c" ] || continue
      nmcli connection delete "$c" >/dev/null; changed=1
    done < <(other_wired)
    # Wi-Fi off: DHCP (so a manual `nmcli connection up` never clashes with the static IP), no autoconnect
    if [ -n "$wifi" ] && { [ "$(nmcli -g ipv4.method connection show "$wifi")" != auto ] || [ "$(autoconn "$wifi")" != no ]; }; then
      set_dhcp "$wifi"; nmcli connection modify "$wifi" connection.autoconnect no; changed=1
    fi
    if [ $wired_mod = 1 ] || ! is_active homelab-wired || { [ -n "$wifi" ] && is_active "$wifi"; }; then
      w=$(q "$wifi")
      switch="nmcli connection up homelab-wired || true
ok=0; i=0
while [ \$i -lt 20 ]; do
  if ip -o -4 addr show dev $wired_if | grep ' $ip/' >/dev/null && ping -c1 -W1 -I $wired_if $gw >/dev/null 2>&1; then ok=1; break; fi
  i=\$((i+1)); sleep 1
done
if [ \$ok = 1 ]; then
  ${wifi:+nmcli connection down $w || true}
  logger -t homelab-net 'Ethernet confirmed ($ip on $wired_if, gateway answers); Wi-Fi off'
else
  nmcli connection modify homelab-wired connection.autoconnect no || true
  nmcli connection down homelab-wired || true
  ${wifi:+nmcli connection modify $w ipv4.method manual ipv4.addresses $ip/$prefix ipv4.gateway $gw ipv4.dns $dns connection.autoconnect yes || true}
  ${wifi:+nmcli connection up $w || true}
  mkdir -p $state_dir; date -u +%FT%TZ > $marker
  logger -t homelab-net 'ROLLBACK: no gateway over $wired_if; $ip back on Wi-Fi'
fi"
    fi
    ;;
  *) echo "lan_link must be wifi or ethernet, got: $link" >&2; exit 1 ;;
esac

if [ -n "$switch" ]; then
  f=$(mktemp "${HOMELAB_NET_RUN:-/run}/homelab-net-switch.XXXXXX")
  printf '#!/bin/sh\n%s\n' "$switch" > "$f"; chmod 700 "$f"
  systemd-run --quiet --no-block --on-active=3s --unit="homelab-net-$(date +%s)" /bin/sh "$f"
  echo RECONNECT
fi
[ $changed = 1 ] && echo CHANGED || echo UNCHANGED
