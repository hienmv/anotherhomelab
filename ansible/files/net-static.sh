#!/usr/bin/env bash
# Put the host's static IP on exactly one link (NetworkManager), values from the Ansible inventory.
#   wifi:     static IP on the active (else first) Wi-Fi profile; the wired profile is removed.
#   ethernet: static IP on profile `homelab-wired`; Wi-Fi goes back to DHCP as a fallback link.
# Reconnects are scheduled 3 s later via systemd-run, so the SSH session running this returns first.
# Prints CHANGED/UNCHANGED, and RECONNECT when the address of an active link changes.
#
# Usage: net-static.sh <wifi|ethernet> <ip> <prefix> <gateway> <dns>
set -euo pipefail
link=$1 ip=$2 prefix=$3 gw=$4 dns=$5
want="manual|$ip/$prefix|$gw|$dns"

wired_if=$(ls /sys/class/net | grep -E '^(en|eth)' | head -1 || true)
wifi=$(nmcli -t -f NAME,TYPE connection show --active | awk -F: '$2=="802-11-wireless"{print $1; exit}')
[ -n "$wifi" ] || wifi=$(nmcli -t -f NAME,TYPE connection show | awk -F: '$2=="802-11-wireless"{print $1; exit}')

get()        { nmcli -g ipv4.method,ipv4.addresses,ipv4.gateway,ipv4.dns connection show "$1" | paste -sd'|'; }
set_static() { nmcli connection modify "$1" ipv4.method manual ipv4.addresses "$ip/$prefix" ipv4.gateway "$gw" ipv4.dns "$dns"; }
set_dhcp()   { nmcli connection modify "$1" ipv4.method auto ipv4.addresses "" ipv4.gateway "" ipv4.dns ""; }
has_wired()  { nmcli -g NAME connection show | grep -qx homelab-wired; }

changed=0; up=()
case "$link" in
  wifi)
    [ -n "$wifi" ] || { echo "no Wi-Fi profile found" >&2; exit 1; }
    if [ "$(get "$wifi")" != "$want" ]; then set_static "$wifi"; changed=1; up+=("$wifi"); fi
    if has_wired; then nmcli connection delete homelab-wired >/dev/null; changed=1; fi
    ;;
  ethernet)
    [ -n "$wired_if" ] || { echo "no Ethernet interface found" >&2; exit 1; }
    if ! has_wired; then
      nmcli connection add type ethernet con-name homelab-wired ifname "$wired_if" >/dev/null; changed=1
    fi
    if [ "$(get homelab-wired)" != "$want" ]; then
      set_static homelab-wired
      nmcli connection modify homelab-wired connection.autoconnect yes connection.autoconnect-priority 100
      changed=1; up+=(homelab-wired)
    fi
    if [ -n "$wifi" ] && [ "$(nmcli -g ipv4.method connection show "$wifi")" != auto ]; then
      set_dhcp "$wifi"; changed=1; up+=("$wifi")
    fi
    ;;
  *) echo "lan_link must be wifi or ethernet, got: $link" >&2; exit 1 ;;
esac

if [ ${#up[@]} -gt 0 ]; then
  cmd=""; for c in "${up[@]}"; do cmd+="nmcli connection up '${c//\'/}' || true; "; done
  systemd-run --quiet --no-block --on-active=3s --unit="homelab-net-$(date +%s)" /bin/sh -c "$cmd"
  echo RECONNECT
fi
[ $changed = 1 ] && echo CHANGED || echo UNCHANGED
