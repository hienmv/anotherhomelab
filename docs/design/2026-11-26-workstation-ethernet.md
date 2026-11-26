# node-workstation on Ethernet (H2)

| | |
|---|---|
| **Authors** | Hien V. Mai (owner), Claude (draft) |
| **Reviewers** | Hien V. Mai |
| **Status** | In review |
| **Created / updated** | 2026-11-26 / 2026-11-26 |
| **Roadmap** | H2 ([roadmap](../roadmap.md)); unblocks A3 (3 control-plane nodes) |
| **Related** | [learn/1](../learn/1-big-picture.md) (layers), K3S-GUIDE 10 (network), [A1](2026-11-22-postgres-replica.md) |

## 1. Context and scope

node-workstation is the k3s **server** (control plane), the data node (Postgres primary, Redis), the
backup orchestrator and the admin box. Since its setup it has been on **Wi-Fi** (`wlp0s20f3`, static
192.168.40.249). The Pis have always been on cable.

Wi-Fi was a known weakness (K3S-GUIDE 10.5):
- 5–10 % packet loss and latency spikes up to ~570 ms were measured on 2026-11-13
- the AP's DFS channel can drop Wi-Fi for about a minute, long enough for agents to show `NotReady`
- it blocks A3: etcd on several control-plane nodes needs a stable network

On 2026-11-26 the owner connected node-workstation by cable. The router lists two leases:

| Router name | IP | What |
|---|---|---|
| CFSV9 | .249 | node-workstation, Wi-Fi, static: **all traffic** |
| node-workstation | .102 | node-workstation, Ethernet (`enp0s31f6`), DHCP profile "Wired connection 1", **no routes, unused** |
| hien-pi-4b / hien-pi-4b-4gb / hien-pi-3b | .250 / .251 / .252 | node-8gb / node-4gb / node-1gb, Ethernet (old hostnames cached by the router) |

Scope: move node-workstation's LAN link to Ethernet, keeping its IP, automatically and safely. Not in
scope: router configuration, the Pis (already on cable), IPv6.

## 2. Goals and non-goals

| # | Goal | Measured by |
|---|---|---|
| G1 | node-workstation's static IP (.249) and all its LAN traffic on Ethernet | `verify.yml --tags network`: `.249` on an `en*` port; `ip route` default via `enp0s31f6` |
| G2 | the pod network (flannel VXLAN) on Ethernet too | `ip -d link show flannel.1` shows `dev enp0s31f6`; verify checks it |
| G3 | **no manual step**: applied by host GitOps after the merge | the apply run's Telegram message; no SSH session needed |
| G4 | **a failed switch can't cut the control plane off** | tested: gateway unreachable over the cable → static IP back on Wi-Fi within ~25 s, marker written, no retry loop |
| G5 | one link, one address: Wi-Fi off, no stray DHCP profile | verify: no IPv4 on `wl*`; no "Wired connection 1" |
| G6 | latency and loss at least as good as Wi-Fi | ping 50× to the gateway and Pis before (Wi-Fi, below) and after |

Baseline, Wi-Fi, 2026-11-26, 50 pings each: gateway 0 % loss, avg 3.5 ms (max 8.7); node-8gb 0 %,
4.7 ms; node-4gb 0 %, 4.5 ms. Today's Wi-Fi was good; the 2026-11-13 loss is the case to avoid.

Non-goals:
- link redundancy (bonding Ethernet + Wi-Fi with failover): one laptop port, and two links in one subnet bring their own problems (5.)
- keeping Wi-Fi as a live fallback (decided against by the owner, 5.)
- changing any IP

## 3. Overview

The inventory already supports this: `lan_link: wifi | ethernet`. Changing it to `ethernet` makes
`workstation-prepare.yml`, which runs in host GitOps, call `files/net-static.sh`. This design makes
that path production-safe:

```text
 merge (lan_link: ethernet)
   └─ homelab-apply (≤ 15 min) → workstation-prepare → net-static.sh
        1. profile homelab-wired: .249 static, autoconnect, priority 100
        2. delete other Ethernet profiles on the port ("Wired connection 1"); NetworkManager makes no new ones
        3. Wi-Fi profile → DHCP, no autoconnect (configured, not yet applied)
        4. 3 s later (systemd), COMMIT CONFIRMED:
             up homelab-wired → .249 on enp0s31f6 AND gateway answers through it within 20 s?
               yes → Wi-Fi down                     no → ROLL BACK: .249 back on Wi-Fi, marker file
   └─ playbook: wait until .249 is on the Ethernet port (or fail: rolled back → apply fails → Telegram)
   └─ flannel still bound to wlp0s20f3? → restart k3s (~30 s API pause; running pods untouched) → /readyz
```

## 4. Detailed design

### 4.1 Commit confirmed (why and how)

Changing the network of the only control-plane node, without a person watching, is the riskiest kind
of change in this homelab. A typo, a bad cable or a switch-port problem would leave the node unreachable:
no SSH, no host GitOps, no Kubernetes API.

The answer used by network engineers: **"commit confirmed"** (Juniper), `netplan try` (Ubuntu), or the
"apply with rollback timer" of many routers. The new configuration is only kept if the device proves
it still works; otherwise the old one comes back by itself.

| Step | Detail |
|---|---|
| test | `ip -o -4 addr show dev enp0s31f6` contains `.249/` **and** `ping -I enp0s31f6 192.168.40.1` answers; up to 20 tries, 1 s apart |
| Wi-Fi during the test | still up with .249. The address is on both cards for a few seconds; the LAN may briefly send to either, which is harmless and over in seconds |
| success | Wi-Fi down; `logger -t homelab-net` line (central host logs, O1) |
| failure | `homelab-wired` down and no autoconnect; Wi-Fi back to static .249, autoconnect, up; marker `/var/lib/homelab/net-ethernet-rolled-back` with the time; log line `ROLLBACK` |
| after a rollback | every later run **refuses** with instructions (exit 1 → apply fails → Telegram) instead of trying again every 15 min (flapping). A person checks the cable, removes the marker, and the next run tries again |

The switch runs from a transient systemd unit 3 s after the script returns. An SSH session or Ansible
run that started over Wi-Fi then finishes cleanly before the network changes.

### 4.2 Pod network: flannel follows the port at k3s start

```text
$ ip -d link show flannel.1   (2026-11-26)
vxlan id 1 local 192.168.40.249 dev wlp0s20f3
```

Flannel's VXLAN device is bound to the **interface** that held the node IP when k3s started. Moving
`.249` to Ethernet does not move it, so pod-to-pod traffic between nodes would keep leaving through
the Wi-Fi card.

The playbook therefore compares that interface with the one holding `.249`. If they differ, it
restarts k3s once and waits for `/readyz`.

A k3s server restart:
- **does** pause the Kubernetes API for about 30 s
- does **not** stop running containers: Postgres, Redis, the apps and Traefik keep serving
- agents reconnect by themselves

**Expected, not verified:** flannel re-creates `flannel.1` on the new interface during that restart,
because it picks the interface of the node IP when it starts. A Docker lab can't move one IP between
two physical links, so this can't be tested beforehand. It's therefore an open question (Q1) with a
detector: verify reports `pod network (flannel) on X, the node IP on Y`. If the restart doesn't move
it, that check fails and says so.

### 4.3 Config and files

| What | Where |
|---|---|
| `lan_link: ethernet`, `wifi_profile` (now: "off when ethernet") | `ansible/inventory.yml` |
| switch, confirm, rollback, marker | `ansible/files/net-static.sh` |
| `no-auto-default=*` (no automatic DHCP profiles) | `/etc/NetworkManager/conf.d/homelab-no-auto-default.conf` (workstation-prepare) |
| confirm wait, flannel check, k3s restart | `workstation-prepare.yml` |
| drift check | `verify.yml --tags network` |
| tests | `scripts/test-net-static.sh` (CI) |

### 4.4 Failure modes

| Situation | Behaviour |
|---|---|
| cable / port bad at switch time | rollback to Wi-Fi in ~25 s, marker, apply fails with instructions, Telegram |
| the cable is pulled later | `.249` is gone with it: node-workstation unreachable, agents `NotReady`, the sites keep running on the Pis' copies; Postgres primary and Redis unreachable → TicketLite 503 (U1). The same as a dead Wi-Fi today. Recovery: plug it back in (NetworkManager reconnects), or keyboard + `nmcli connection up JCOM_CKQY` for a DHCP address |
| the router reboots | the cable has link; NetworkManager keeps the static address |
| Wi-Fi brought up by hand while on Ethernet | the next run turns it off again (after confirming Ethernet works) |
| going back to Wi-Fi | `lan_link: wifi`: static on Wi-Fi with autoconnect, `homelab-wired` deleted, marker cleared; the flannel check restarts k3s again |

## 5. Alternatives considered

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **A. Move .249 to Ethernet with commit-confirmed, Wi-Fi off** (chosen) | one link, one address; same IP, so no k3s certificate / agent / config change; safe automatic rollout | no live second link | chosen |
| B. Ethernet + Wi-Fi as a DHCP fallback | a second way in over SSH | two cards in one subnet: Linux answers ARP for .249 on both by default ("ARP flux"), so it needs `arp_ignore`/`arp_announce` sysctls; routing asymmetry; and the fallback doesn't keep `.249`, so the cluster is down anyway | rejected by the owner (2026-11-26) |
| C. Bonding (active-backup Ethernet + Wi-Fi) | real link failover with the same IP | Wi-Fi in a bond is poorly supported (one MAC per station), fragile on laptops | rejected |
| D. Use the DHCP address .102 instead of .249 | no move | every node, certificate (`tls-san`), Garage URL and config uses `.249`; a full IP migration for nothing | rejected |
| E. Switch by hand, at the machine | a person watching | against host GitOps (everything through git); no record; the same risk if the person is not at the keyboard | rejected; A gives the same safety automatically |

## 6. Cross-cutting concerns

### 6.1 Security

- No new ports or secrets.
- The switch script is written to `/run`, root only, mode 0700.
- Wi-Fi off removes a radio path into the control-plane node.

### 6.2 Observability

- `homelab-net` lines in the host journal, and so in VictoriaLogs since O1: `Ethernet confirmed …` or `ROLLBACK …`.
- `verify.yml --tags network` reports a wrong port, flannel on the other port, Wi-Fi still addressed, or a rollback marker.
- Packet loss per peer is still reported (unchanged).

### 6.3 Reliability and SLOs

- One k3s restart (API pause ~30 s) at rollout. Serving pods are untouched, so the user-facing SLOs are unaffected.
- After the switch, fewer `NotReady` flaps from Wi-Fi drops are expected.

### 6.4 Compatibility

The IP is unchanged, so nothing else changes:
- agents (`server: https://192.168.40.249:6443`)
- the API certificate SANs, the kubeconfig
- Garage's URL, the logs ingest, the inventory
- the Pis' ufw rules (they allow the workstation's IP)

## 7. Rollout plan

1. Merge. Within 15 min `homelab-apply` runs workstation-prepare: switch, confirm, and the k3s restart. The Telegram message shows the changed tasks.
2. Watch, about 5 min:
   - `ip -br addr`: `.249` on `enp0s31f6`, no IPv4 on `wlp0s20f3`
   - `ip -d link show flannel.1`: `dev enp0s31f6`
   - `kubectl get nodes`: 4 Ready
   - `verify.yml`: all checks pass
   - ping test as in G6
3. If it rolls back:
   - the apply fails with instructions
   - the node stays on Wi-Fi and the cluster is unaffected
   - fix the cable or port, `sudo rm /var/lib/homelab/net-ethernet-rolled-back`, and the next run tries again
4. Rollback by choice: a PR with `lan_link: wifi`.

## 8. Testing

| Level | What | Result |
|---|---|---|
| unit (fake NetworkManager) | switch confirmed; idempotent re-run; **gateway unreachable → rollback, marker, log**; refuses afterwards (no flapping); retries after the marker is removed; back to Wi-Fi + idempotent; Wi-Fi brought up by hand → off again | `scripts/test-net-static.sh`, 16 checks, all pass (Ubuntu 24.04 container; also in CI) |
| found by the tests | `cmd \| grep -q` under `pipefail` fails at random (grep exits early, SIGPIPE): fixed in `net-static.sh` | fixed |
| Ansible | syntax-check, list-tasks (ansible 9.2.0) | pass |
| after deploy | 7.2 | pending |

## 9. Open questions

| # | Question | Owner / when |
|---|---|---|
| Q1 | Flannel re-creates `flannel.1` on `enp0s31f6` after the k3s restart (4.2) | first rollout; verify catches it |
| Q2 | Router: reserve .249 for the Ethernet MAC `b8:20:8e:22:64:ab` (and drop the Wi-Fi MAC's reservation, if any), so the router never hands .249 to another device | owner, router UI |

## 10. Future work

- **A3**: 3 control-plane nodes (needs H4, an SSD on node-4gb). H2 was its network prerequisite.
- A non-DFS Wi-Fi channel is no longer relevant for the cluster.
