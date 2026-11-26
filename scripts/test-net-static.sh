#!/usr/bin/env bash
# Tests ansible/files/net-static.sh against a FAKE NetworkManager (nmcli), ip, ping and systemd-run,
# so every path (switch, confirm, rollback, idempotency, refusal after a rollback) runs without touching
# a real network. docs/design/2026-11-26-workstation-ethernet.md. Runs in CI (validate.yml).
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
script=$here/ansible/files/net-static.sh
fails=0

setup() { # $1 = gateway reachable over Ethernet? yes|no
  T=$(mktemp -d); mkdir -p "$T/bin" "$T/nm" "$T/sys/enp0s31f6" "$T/sys/wlp0s20f3" "$T/sys/lo" "$T/run" "$T/state"
  echo "$1" > "$T/gw_over_eth"
  # NetworkManager as found on node-workstation, 2026-11-26
  mk JCOM_CKQY 802-11-wireless wlp0s20f3 manual 192.168.40.249/24 192.168.40.1 192.168.40.1 yes 1
  mk "Wired connection 1" 802-3-ethernet "" auto 192.168.40.102/24 "" "" yes 1
  cat > "$T/bin/nmcli" <<'NM'
#!/usr/bin/env bash
d=$FAKE/nm; f() { cat "$d/$1/$2" 2>/dev/null; }
args=("$@"); fields=""; terse=0; active=0
while [ "${args[0]}" = -g ] || [ "${args[0]}" = -t ] || [ "${args[0]}" = -f ]; do
  case "${args[0]}" in -g) fields=${args[1]}; args=("${args[@]:2}");; -t) terse=1; args=("${args[@]:1}");; -f) fields=${args[1]}; args=("${args[@]:2}");; esac
done
[ "${args[0]}" = connection ] || exit 2; sub=${args[1]}; rest=("${args[@]:2}")
case "$sub" in
  show)
    [ "${rest[0]:-}" = --active ] && { active=1; rest=("${rest[@]:1}"); }
    if [ ${#rest[@]} -eq 0 ]; then
      for c in "$d"/*; do n=$(basename "$c"); [ $active = 1 ] && [ "$(f "$n" active)" != 1 ] && continue
        case "$fields" in NAME) echo "$n";; NAME,TYPE) echo "$n:$(f "$n" type)";; esac; done
    else n=${rest[0]}; [ -d "$d/$n" ] || exit 10
      IFS=, read -ra fs <<< "$fields"; for k in "${fs[@]}"; do f "$n" "$k"; echo; done | sed '/^$/d;s/^$//' ; fi ;;
  modify) n=${rest[0]}; set -- "${rest[@]:1}"; while [ $# -gt 0 ]; do printf '%s' "$2" > "$d/$n/$1"; shift 2; done ;;
  add) n=${rest[3]}; mkdir -p "$d/$n"; printf 802-3-ethernet > "$d/$n/type"; printf '%s' "${rest[5]}" > "$d/$n/connection.interface-name"
       printf auto > "$d/$n/ipv4.method"; printf yes > "$d/$n/connection.autoconnect"; printf 0 > "$d/$n/active" ;;
  delete) rm -rf "${d:?}/${rest[0]}" ;;
  up) printf 1 > "$d/${rest[0]}/active"; [ "$(f "${rest[0]}" type)" = 802-3-ethernet ] && for c in "$d"/*; do n=$(basename "$c")
        [ "$n" != "${rest[0]}" ] && [ "$(f "$n" type)" = 802-3-ethernet ] && printf 0 > "$c/active"; done; true ;;
  down) printf 0 > "$d/${rest[0]}/active" ;;
esac
NM
  cat > "$T/bin/ip" <<'IP'
#!/usr/bin/env bash
# ip -o -4 addr show dev <if>: the address of the active profile on that device
dev=${@: -1}; for c in "$FAKE"/nm/*; do
  [ "$(cat "$c/active")" = 1 ] || continue
  cd=$(cat "$c/connection.interface-name" 2>/dev/null); [ -z "$cd" ] && cd=enp0s31f6
  [ "$cd" = "$dev" ] && echo "2: $dev    inet $(cat "$c/ipv4.addresses") brd x scope global"; done; true
IP
  printf '#!/bin/sh\n[ "$(cat "$FAKE/gw_over_eth")" = yes ]\n' > "$T/bin/ping"
  printf '#!/bin/sh\nfor a; do last=$a; done; echo "$last" >> "$FAKE/scheduled"\n' > "$T/bin/systemd-run"
  printf '#!/bin/sh\necho "$*" >> "$FAKE/log"\n' > "$T/bin/logger"
  printf '#!/bin/sh\n:\n' > "$T/bin/sleep"
  chmod +x "$T"/bin/*
}
mk() { # name type ifname method addr gw dns autoconnect active
  local d=$T/nm/$1; mkdir -p "$d"; printf '%s' "$2" > "$d/type"; printf '%s' "$3" > "$d/connection.interface-name"
  printf '%s' "$4" > "$d/ipv4.method"; printf '%s' "$5" > "$d/ipv4.addresses"; printf '%s' "$6" > "$d/ipv4.gateway"
  printf '%s' "$7" > "$d/ipv4.dns"; printf '%s' "$8" > "$d/connection.autoconnect"; printf '%s' "$9" > "$d/active"
}
run() { # $@ = net-static.sh args; then runs what it scheduled (the 3 s later part)
  : > "$T/scheduled"
  out=$(PATH=$T/bin:$PATH FAKE=$T HOMELAB_NET_SYS=$T/sys HOMELAB_NET_RUN=$T/run HOMELAB_NET_STATE=$T/state \
        bash "$script" "$@" 192.168.40.249 24 192.168.40.1 192.168.40.1 JCOM_CKQY 2>&1) && rc=0 || rc=$?
  while read -r s; do [ -n "$s" ] && PATH=$T/bin:$PATH FAKE=$T sh "$s"; done < "$T/scheduled"
}
v() { cat "$T/nm/$1/$2" 2>/dev/null || echo "<none>"; }
check() { if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1: got '$2', want '$3'"; fails=$((fails+1)); fi; }

echo "1. Wi-Fi -> Ethernet, gateway answers over the cable"
setup yes; run ethernet
check "exit 0, RECONNECT + CHANGED" "$rc $(echo "$out" | tr '\n' ' ')" "0 RECONNECT CHANGED "
check "homelab-wired holds .249 and is up" "$(v homelab-wired ipv4.addresses) $(v homelab-wired active)" "192.168.40.249/24 1"
check "'Wired connection 1' (DHCP .102) removed" "$(v 'Wired connection 1' type)" "<none>"
check "Wi-Fi off: DHCP, no autoconnect, down" "$(v JCOM_CKQY ipv4.method) $(v JCOM_CKQY connection.autoconnect) $(v JCOM_CKQY active)" "auto no 0"
check "no rollback marker" "$(ls "$T/state")" ""
echo "   again (converged):"; run ethernet
check "UNCHANGED, nothing scheduled" "$(echo "$out" | tr '\n' ' ')$(wc -l < "$T/scheduled" | tr -d ' ')" "UNCHANGED 0"

echo "2. Wi-Fi -> Ethernet, gateway does NOT answer (bad cable / port): roll back"
setup no; run ethernet
check "static IP back on Wi-Fi, autoconnect, up" "$(v JCOM_CKQY ipv4.method) $(v JCOM_CKQY ipv4.addresses) $(v JCOM_CKQY connection.autoconnect) $(v JCOM_CKQY active)" "manual 192.168.40.249/24 yes 1"
check "homelab-wired down, no autoconnect" "$(v homelab-wired active) $(v homelab-wired connection.autoconnect)" "0 no"
check "rollback marker written" "$(ls "$T/state")" "net-ethernet-rolled-back"
check "logged" "$(grep -c ROLLBACK "$T/log")" "1"
echo "   next run (15 min later): refuses, does not flap"; run ethernet
check "exit 1 with instructions, nothing scheduled" "$rc $(echo "$out" | grep -c 'rm .*net-ethernet-rolled-back') $(wc -l < "$T/scheduled" | tr -d ' ')" "1 1 0"
echo "   cable fixed, marker removed by a person:"; echo yes > "$T/gw_over_eth"; rm "$T/state/net-ethernet-rolled-back"; run ethernet
check "switches now" "$(v homelab-wired active) $(v JCOM_CKQY active)" "1 0"

echo "3. Back to Wi-Fi (lan_link: wifi)"
run wifi
check "Wi-Fi static .249, autoconnect, up; wired profile gone" "$(v JCOM_CKQY ipv4.method) $(v JCOM_CKQY connection.autoconnect) $(v JCOM_CKQY active) $(v homelab-wired type)" "manual yes 1 <none>"
run wifi
check "converged: UNCHANGED" "$(echo "$out" | tr '\n' ' ')" "UNCHANGED "

echo "4. Someone brings Wi-Fi up by hand while on Ethernet: switched off again (confirmed first)"
setup yes; run ethernet; PATH=$T/bin:$PATH FAKE=$T nmcli connection up JCOM_CKQY; run ethernet
check "Wi-Fi down again, Ethernet up" "$(v JCOM_CKQY active) $(v homelab-wired active)" "0 1"

[ $fails = 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
