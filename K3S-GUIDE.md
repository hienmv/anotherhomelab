# Homelab k3s Guide

How this cluster was built, why it looks the way it does, and how to rebuild or extend it.
The **current state** lives in [README.md](README.md) (diagrams, components) and in the manifests
under `k8s/`; this guide links to them instead of copying them.

| Topic | Document |
|---|---|
| Overview, diagrams, components, admin workstation | [README.md](README.md) |
| Hostnames, Cloudflare, tunnel, Ingress, redirects | [docs/domains.md](docs/domains.md) |
| TicketLite (Ktor/React, CI, metrics) | [apps/ticketlite/README.md](apps/ticketlite/README.md) |
| Landing page (hien0ethan.com) | [apps/landing/README.md](apps/landing/README.md) |
| Notes (notes.hien0ethan.com) | [apps/notes/README.md](apps/notes/README.md) |

## 1. Hardware (audit 2026-11-07)

| | pi4-8gb (`hien-pi-4b`) | pi4-4gb (`hien-pi-4b-4gb`) | pi3 (`hien-pi-3b`) |
|---|---|---|---|
| IP (static) | 192.168.40.250 | 192.168.40.251 | 192.168.40.252 |
| Board | Pi 4B rev 1.5 | Pi 4B rev 1.1 | Pi 3B+ |
| CPU / RAM | 4x A72 / 7.6 GiB | 4x A72 / 3.7 GiB | 4x A53 / 905 MiB |
| Disk | 32 GB SD | 64 GB SD | 32 GB SD |
| OS | Debian 13 trixie arm64 (kernel 6.18 after upgrade) | same | same |
| Power | OK | under-voltage at boot only | **under-voltage under load** (new PSU helped at idle) |

Open hardware issues: **pi3 power** (keep it to light pods), **no SSD** (Postgres, Redis, metrics and the
k3s datastore all sit on pi4-8gb's SD card, ~12 GiB written per day; see section 8).

## 2. Design decisions

| Decision | Why |
|---|---|
| Single k3s server (pi4-8gb), SQLite datastore, no HA | HA needs 3 etcd servers; etcd on SD cards and 1 GB pi3 is a bad fit. Rebuild from git + Flux instead |
| Node labels `role=data` (pi4-8gb), `role=apps` (pi4-4gb), `role=light` + taint (pi3) | Pin stateful pods to one node; keep the weak pi3 nearly empty |
| Data pinned to pi4-8gb (`local-path` volumes) | Simple; move to an SSD when one is added |
| Redis = cache + queue (streams) | Enough for a homelab; no extra broker |
| Public access only through Cloudflare Tunnel | No open router ports, free TLS ([docs/domains.md](docs/domains.md)) |
| Images built by GitHub Actions for arm64, stored in private GHCR | Reproducible; the Mac is not needed to deploy |
| GitOps with Flux, read-only deploy key | `git push` is the only deploy path |
| Static IPs on the Pis (NetworkManager), not DHCP reservations | The HUMAX router has no reservations and a fixed pool `.2–.254`; statics use `.250–.252` |
| One-level hostnames (`<app>.hien0ethan.com`) | Cloudflare's free certificate does not cover deeper names |
| No dedicated admin server | See README → Admin workstation |
| Backups deferred (2026-11-08) | User decision; risk accepted until real data exists (section 8) |

## 3. Prepare the Pis

### 3.1 Access and static IPs
- Mac `/etc/hosts` maps `pi3`, `pi4-8gb`, `pi4-4gb`; `~/.ssh/config` sets `User hien`,
  `IdentityFile ~/.ssh/id_homelab`. Passwordless sudo via `/etc/sudoers.d/hien`.
- Static IP per Pi (connection name differs: `netplan-eth0` on the Pi 4s, `Wired connection 1` on pi3):
  ```bash
  sudo nmcli con mod netplan-eth0 ipv4.method manual ipv4.addresses 192.168.40.250/24 \
    ipv4.gateway 192.168.40.1 ipv4.dns 192.168.40.1
  sudo systemd-run --on-active=2 nmcli con up netplan-eth0   # apply after the SSH command returns
  ```
  Applying inside the live SSH session drops it; `systemd-run` avoids that.

### 3.2 Memory cgroup, upgrade, prerequisites
```bash
for h in pi3 pi4-8gb pi4-4gb; do
  ssh $h 'sudo sed -i "1 s/\$/ cgroup_enable=memory/" /boot/firmware/cmdline.txt &&
          sudo apt-get update && sudo apt-get -y full-upgrade &&
          sudo apt-get -y install curl nfs-common open-iscsi jq && sudo reboot'
done
for h in pi3 pi4-8gb pi4-4gb; do ssh $h 'echo $(hostname): $(cat /sys/fs/cgroup/cgroup.controllers)'; done  # must list memory
```
pi4-8gb had **509 locales** enabled: the `locales` upgrade compiled all of them (~40 min). Never reboot
while `dpkg --audit` reports unconfigured packages.

### 3.3 Free ports 80/443
Traefik's ServiceLB needs 80/443 on every node. Host nginx/php-fpm were removed (backups in
`~/web-backup-2026-11-07.tgz` on pi3 and pi4-8gb), and the host cloudflared on pi3 was replaced by the
in-cluster one.

### 3.4 pi3 firewall
pi3 runs ufw. k3s needs node-to-node and pod traffic allowed:
```bash
sudo ufw allow from 192.168.40.0/24; sudo ufw allow from 10.42.0.0/16; sudo ufw allow from 10.43.0.0/16
```

### 3.5 (Todo) SSD on pi4-8gb
```bash
sudo mkfs.ext4 -L ssd /dev/sda          # DESTROYS data on /dev/sda
echo 'LABEL=ssd /mnt/ssd ext4 defaults,noatime 0 2' | sudo tee -a /etc/fstab
sudo mkdir -p /mnt/ssd && sudo mount -a && sudo mkdir -p /mnt/ssd/k3s-storage
```
Then move the `local-path` volumes (Postgres, Redis, VMSingle) and pass
`--default-local-storage-path /mnt/ssd/k3s-storage` to the k3s server.

## 4. Install k3s

```bash
# server
ssh pi4-8gb 'curl -sfL https://get.k3s.io | sh -s - server --write-kubeconfig-mode 600 \
  --tls-san pi4-8gb --tls-san 192.168.40.250 --node-ip 192.168.40.250 --node-label role=data \
  --kubelet-arg=system-reserved=cpu=250m,memory=512Mi'

# agents
TOKEN=$(ssh pi4-8gb 'sudo cat /var/lib/rancher/k3s/server/node-token')
ssh pi4-4gb "curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.250:6443 K3S_TOKEN=$TOKEN sh -s - agent \
  --node-ip 192.168.40.251 --node-label role=apps --kubelet-arg=system-reserved=memory=256Mi"
ssh pi3 "curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.250:6443 K3S_TOKEN=$TOKEN sh -s - agent \
  --node-ip 192.168.40.252 --node-label role=light --node-taint role=light:NoSchedule \
  --kubelet-arg=system-reserved=memory=128Mi"

# kubectl on the Mac
ssh pi4-8gb 'sudo cat /etc/rancher/k3s/k3s.yaml' | sed 's/127.0.0.1/192.168.40.250/; s/: default/: homelab/' \
  > ~/.kube/homelab.yaml && chmod 600 ~/.kube/homelab.yaml
echo 'export KUBECONFIG=~/.kube/homelab.yaml' >> ~/.zshrc     # otherwise every kubectl needs it
brew install kubectl helm k9s
```
Everything after this point is applied by Flux from git (section 6), not by hand.

## 5. What runs where (pointers)

| Piece | Manifest / values | Notes |
|---|---|---|
| Namespaces | `k8s/namespaces/` | `prune: false` |
| CloudNativePG operator | `k8s/releases/cnpg.yaml` | HelmRelease |
| PostgreSQL 17 `pg` | `k8s/data/postgres.yaml` | 10Gi on pi4-8gb; creates secret `data/pg-app`; host `pg-rw.data.svc:5432` |
| Redis 8.2 | `k8s/data/redis.yaml` | AOF, `noeviction` (queue must never drop jobs), 2Gi on pi4-8gb |
| cloudflared | `k8s/infra/cloudflared.yaml` | 1 replica on pi4-8gb; routing in [docs/domains.md](docs/domains.md) |
| Traefik customisation | `k8s/infra/traefik-config.yaml` | trusts `X-Forwarded-*` from pods |
| Monitoring stack | `k8s/releases/vm-stack.yaml` + `vm-stack-values.yaml` | section 7 |
| Extra scrapes, Grafana HTTPS, dashboards | `k8s/monitoring/` | |
| Apps | `k8s/apps/<app>/` | TicketLite, landing, notes |

`apps/app-env` (shared app config: `DATABASE_URL`, `REDIS_URL`, `REDIS_PASSWORD`) is built from the
generated secrets:
```bash
kubectl -n apps create secret generic app-env \
  --from-literal=DATABASE_URL="$(kubectl -n data get secret pg-app -o jsonpath='{.data.fqdn-uri}' | base64 -d)" \
  --from-literal=REDIS_PASSWORD="$(kubectl -n data get secret redis -o jsonpath='{.data.password}' | base64 -d)" \
  --from-literal=REDIS_URL="redis://:$(kubectl -n data get secret redis -o jsonpath='{.data.password}' | base64 -d)@redis.data.svc.cluster.local:6379/0"
```

### Adding an app
1. Code in `apps/<app>/` with a Dockerfile whose build stage uses `--platform=$BUILDPLATFORM`
   (native on the amd64 runner) and whose runtime stage has no `RUN` steps (no slow emulation).
2. Workflow `.github/workflows/<app>.yml` calling `build-image.yml` and `bump-tag.yml` (copy `notes.yml`).
3. Manifests in `k8s/apps/<app>/` with `imagePullSecrets: [{ name: ghcr-pull }]` and an `images:` entry
   in its `kustomization.yaml`; add the folder to `k8s/apps/kustomization.yaml`.
4. Public hostname: [docs/domains.md → Add a public subdomain](docs/domains.md#add-a-public-subdomain-for-a-new-app).
5. Planned patterns not built yet: KEDA autoscaling of workers on Redis stream lag; CronJobs for batch work.

## 6. GitOps with Flux

Flux v2.10.0 (namespace `flux-system`) pulls this repo every minute over SSH with a **read-only**
deploy key. A push to `main` is a deploy.

```text
clusters/homelab/
  kustomization.yaml       entry point Flux reads
  flux-system/             Flux components (generated) + GitRepository/Kustomization
  platform.yaml            one Flux Kustomization per k8s/ folder, with dependsOn order
k8s/
  namespaces/   releases/   infra/   data/   apps/   monitoring/
```
Order: `namespaces → releases → data → apps`, `namespaces → infra`, `releases → monitoring`.
`namespaces` and `data` have `prune: false` (deleting a file never deletes the database). Each
`k8s/<folder>/kustomization.yaml` lists its files; a new manifest must be added there.

**How it was installed (no `flux bootstrap`, which needs a write key):**
1. `flux install --export > clusters/homelab/flux-system/gotk-components.yaml`, applied with kubectl.
2. An ed25519 key stored only in secret `flux-system/flux-system` (`identity`, `identity.pub`,
   `known_hosts`); public half added on GitHub as a read-only deploy key.
3. `kubectl apply -k clusters/homelab/flux-system`. The existing Helm releases `cnpg` and `vm` were
   adopted by HelmReleases with the same `releaseName`.

**Flux CLI:** runs from its container (alias in README → Usage). `brew install fluxcd/tap/flux` failed
on GitHub 504s, and `brew install flux` is an **unrelated tool**. The container needs `--network host`.

### Secrets kept out of git (create once per cluster)

| Secret | Namespace | How |
|---|---|---|
| `flux-system` (deploy key) | flux-system | section 6, step 2 |
| `tunnel-creds` | infra | header of `k8s/infra/cloudflared.yaml` |
| `pg-app` | data | generated by CNPG |
| `redis` | data | header of `k8s/data/redis.yaml` |
| `app-env` | apps | section 5 |
| `ticketlite` (`SESSION_SECRET`) | apps | `apps/ticketlite/README.md` → Kubernetes |
| `ghcr-pull` | apps | below; used by every app |
| `grafana-admin` | monitoring | header of `k8s/releases/vm-stack-values.yaml` |
| `grafana-lan-tls` | monitoring | header of `k8s/monitoring/grafana-https.yaml` |

**`ghcr-pull`** (GHCR images are private). Create a **classic** token with only `read:packages`:
https://github.com/settings/tokens/new?scopes=read:packages&description=homelab-ghcr-pull.
Fine-grained tokens log in but get `403 Forbidden` on the image (seen 2026-11-09). Then, in your own
terminal so the token stays out of logs and chats:
```bash
read -s GHCR_TOKEN && kubectl -n apps create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io --docker-username=hienmv --docker-password="$GHCR_TOKEN"; unset GHCR_TOKEN
```
The current token expires **2027-02**; when pulls fail with 401/403, create a new one and recreate the secret.

Todo: encrypt them into git with SOPS + age so a rebuilt cluster needs no manual steps.

## 7. Observability

| Part | Where | Notes |
|---|---|---|
| VictoriaMetrics single | pi4-8gb | 30d retention, 10Gi |
| vmagent | pi4-8gb | scrapes every 30s; extra targets in `k8s/monitoring/scrapes.yaml` (CNPG, cloudflared, TicketLite) |
| Grafana | pi4-4gb | https://grafana.hien0ethan.com (public, admin login; add Cloudflare Access, see docs/domains.md) and https://grafana.lan (LAN); dashboards incl. **TicketLite** (`k8s/monitoring/dashboards/`); memory limit 768Mi (384Mi was OOMKilled) |
| node-exporter | all 3 nodes | tolerates pi3's taint |

Not scraped: scheduler, controller-manager, etcd, kube-proxy (k3s binds them to localhost).
Alerting (vmalert + Alertmanager) is off until a notification channel is chosen.

### Grafana on the Mac (LAN name)
1. `echo "192.168.40.250 grafana.lan" | sudo tee -a /etc/hosts`
2. Open https://grafana.lan, user `admin`, password:
   `kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d`
3. The certificate comes from a private **mkcert** CA trusted only by this Mac
   (`mkcert -install`; key in `~/Library/Application Support/mkcert/rootCA-key.pem`, never copy it).
   The certificate expires **2029-02-08**; reissue with the commands in `k8s/monitoring/grafana-https.yaml`.

### Troubleshooting: `curl` works, the browser does not
- Browser silently upgrades to `https://`: solved since Grafana now has HTTPS.
- Chrome treats bare `grafana.lan` as a search: type `https://grafana.lan/`.
- Chrome DNS cache: `chrome://net-internals/#dns` → Clear host cache. DNS-over-HTTPS and iCloud Private
  Relay bypass `/etc/hosts`.
- **Chrome `ERR_ADDRESS_UNREACHABLE` for every LAN IP** while Terminal works: macOS **Local Network**
  permission. After an auto-update Chrome runs from a temporary copy that the permission does not
  cover; toggling the setting does not help. **Fix: open `chrome://restart`**, then allow the
  local-network prompt. Fallbacks: reboot the Mac, or `tccutil reset LocalNetwork com.google.Chrome`.
- The Mac's System keychain also had an unrelated old mkcert CA (`NTQ-SOLUTION\sy.le@d-os3-sylt`,
  SHA-256 `DA130CC5…`); remove with `sudo security delete-certificate -Z <sha256> /Library/Keychains/System.keychain`.

## 8. Backups (deferred, 2026-11-08)

**No backups run.** Accepted risk: if pi4-8gb's SD card dies, Postgres, Redis, metrics and the k3s
state are lost; everything in git survives. Revisit before storing real data.

| Part | Recreatable? | Planned method |
|---|---|---|
| Postgres data | **no** | CNPG backup to Cloudflare R2 (nightly base backup + WAL, 14 days), plus a restore test |
| Secrets | by hand; **tunnel creds: no** | SOPS + age in git |
| k3s `state.db` + token | mostly, via Flux | nightly copy to R2 |
| Redis | yes, if jobs can be re-queued | AOF copy if needed |
| Mac: `~/.ssh/id_homelab`, kubeconfig | SSH key lost = locked out of the Pis | password manager |

3-2-1 rule: 3 copies, 2 devices, 1 off-site (R2: S3-compatible, 10 GB free, free egress).

## 9. Status

Done (details in git history):
- Static IPs, cgroups, k3s on all 3 nodes, kubeconfig on the Mac
- Postgres (CNPG) + Redis; Flux GitOps; cloudflared in-cluster; Traefik forwarded-header trust
- VictoriaMetrics + Grafana (LAN, mkcert HTTPS), TicketLite dashboard
- Apps: TicketLite, landing (hien0ethan.com), notes (notes.hien0ethan.com, migrated from hienmv.github.io)
- CI: shared `build-image.yml` / `bump-tag.yml`, dependency layer cached, Node 24 actions

Open:
- [ ] pi3 power supply; SSD on pi4-8gb (then move volumes)
- [ ] Backups + restore test (section 8)
- [ ] SOPS-encrypted secrets
- [ ] Alerting (choose a channel: Telegram, Discord, Slack, email)
- [ ] KEDA worker autoscaling; batch CronJobs
- [ ] Archive hienmv.github.io once the notes site is confirmed (user)

## 10. Migration: CF-SV9 as k3s server + data node (in progress, 2026-11-10)

Names from phase 2 on (inventory = OS hostname = Kubernetes node): `node-workstation` (CF-SV9,
was `sv9`), `node-8gb` (was `pi4-8gb`), `node-4gb` (was `pi4-4gb`), `node-1gb` (was `pi3`).
Ansible connects by IP from the inventory.

Decision: a Panasonic CF-SV9 (amd64, 16 GB RAM, SSD ~80 GB usable, Ubuntu 26.04 LTS) stays always on at
home as **k3s server**, **`role=data` node** and **admin workstation**. The Mac only SSHes into it.
Why: control plane and Postgres/Redis leave the SD cards; the laptop battery acts as a UPS. This
reverses the 2026-11-08 "no admin box in the cluster" decision (README → Operations).

Target: `node-workstation` `.249` server + `role=data` (PG, Redis, VMSingle, cloudflared follow the label, no
manifest change); `node-8gb` and `node-4gb` agents `role=apps`; `node-1gb` unchanged. The 64 GB SD card
moves from node-4gb to node-8gb (32 GB the other way).

Plan: (0) prepare node-workstation, no downtime; (1) export secrets + `pg_dump`; (2) uninstall k3s, swap SD cards,
fix hostnames/IPs, install server on node-workstation, join Pis, restore secrets, Flux rebuilds, restore PG;
(3) verify, update README diagrams and this guide. App images must be multi-arch (amd64 + arm64).

### 10.1 Prepare node-workstation (Ansible, phase 0)

Everything runs from Ansible (`ansible/`, see [ansible/README.md](ansible/README.md)). `hien` is an
Ubuntu **Administrator** (sudo group) and **keeps its sudo password** (node-workstation is a desktop that browses
the web; passwordless sudo stays on the Pis only).

All LAN addresses live in `ansible/inventory.yml` (hosts' `static_ip`, gateway, DNS); playbooks and
scripts read them from there. node-workstation's `lan_link` picks the link holding `.249`: **`wifi` (current)** or
`ethernet` (change the value, re-run the playbook; `ansible/files/net-static.sh` moves the address and
the playbook follows node-workstation to it). Wi-Fi is acceptable here: the profile is system-wide (connects at
boot without a login) and power saving is off. Costs: higher latency and short drops; the AP uses
DFS channel 124 (5.6 GHz), where radar detection can move the AP and cut Wi-Fi for about a minute,
long enough for agents to show `NotReady`. If that shows up, pick a non-DFS 5 GHz channel (36-48) on
the router or switch to `ethernet`.

Manual, once:
1. At node-workstation: `sudo apt install -y openssh-server && hostname -I` (Ubuntu Desktop has no SSH server).
2. Mac: give the Mac key a passphrase and keep it in the Keychain, then copy it:
   `ssh-keygen -p -f ~/.ssh/id_sv9 && ssh-add --apple-use-keychain ~/.ssh/id_sv9 && ssh-copy-id -i ~/.ssh/id_sv9 hien@<that IP>`
3. Mac: `cd ansible && ansible-playbook playbooks/workstation-prepare.yml -e workstation_ip=<that IP>`
   (asks the node-workstation sudo password on every run, `become_ask_pass`; the first run also asks a passphrase
   for the new node-workstation keys).

What it does: hostname `node-workstation`, `/etc/hosts` names, upgrade, SSH server keys-only, never sleep (logind + masked sleep
targets), Wi-Fi power saving off, static IP on the `lan_link` link, kubectl/helm/k9s/flux/sops/age
(pinned in `ansible/group_vars/all.yml`), passphrase-protected SSH keys node-workstation -> Pis (added to their
`authorized_keys`) and node-workstation -> GitHub, cached 8 h in an ssh-agent (GNOME's on the desktop, a fixed
`~/.ssh/agent.sock` one in SSH sessions),
a GitHub deploy key `sv9-admin` (write, this repo only, added with `gh` from the Mac), kubeconfig
`~/.kube/homelab.yaml`, repo clone at `~/workspace/source/homelab`. Reboots once on first run.

BIOS (F2 at boot), by hand: enable a battery charge limit / economy mode if present.

### 10.2 Back up secrets and PostgreSQL (phase 1, done 2026-11-11)

`ansible/playbooks/backup.yml` (script `ansible/files/backup.sh`), runnable any time, no downtime:

```bash
cd ansible && ANSIBLE_BECOME_ASK_PASS=false ansible-playbook playbooks/backup.yml   # -e workstation_ip=... while on Wi-Fi
```

- Exports the hand-made Secrets (list in the playbook, plus `data/pg-app`, whose password is inside
  `apps/app-env`'s `DATABASE_URL`) one file each, and every other Secret as `all-secrets.json`.
- `pg_dumpall --globals-only` + `pg_dump -Fc` per database (today: `app`).
- Plaintext only in `$XDG_RUNTIME_DIR` (tmpfs); the bundle is encrypted with **age** to
  `~/homelab-backup/homelab-<date>.tar.gz.age` on node-workstation and copied to `~/homelab-backups/` on the Mac.
- Key: `~/.config/sops/age/keys.txt` on node-workstation (also the future SOPS key). **Copy it to the password
  manager**: without it no backup can be decrypted.
- Restore: `age -d -i ~/.config/sops/age/keys.txt <file> | tar -xzf - -C <dir>`.
  Check a dump without restoring: `... | tar -xOzf - ./postgres/app.dump | kubectl -n data exec -i pg-1 -c postgres -- pg_restore -l`.
- Not backed up: Redis (TicketLite resyncs its gates from Postgres), metrics history, Grafana's own DB
  (dashboards are in git).

Multi-arch: `build-image.yml` builds `linux/amd64,linux/arm64`, so app pods can run on node-workstation too.

### 10.3 Rebuild with node-workstation as server (phase 2, ready, not run yet)

One command, about 1 h of downtime, run in a real terminal (prompts):

```bash
cd ansible && ansible-playbook playbooks/phase2-migrate.yml      # -e sd_swap=false: no SD swap
```

Prompts: node-workstation sudo password, `REBUILD`, Enter after the SD swap. Steps (header of the playbook has the
detail): fresh backup → uninstall k3s (agents, then the old server: its volumes are deleted) →
Pi identities → SD swap by hand → verify → `k3s.yml` → `restore.yml`.

- **Names**: inventory name = SSH name = OS hostname = Kubernetes node name (`node-workstation`, `node-8gb`,
  `node-4gb`, `node-1gb`; before: `sv9`/`pi4-8gb`/`pi4-4gb`/`pi3`, OS `hien-pi-4b`, `hien-pi-4b-4gb`, `hien-pi-3b`).
- **SD swap**: before power-off, each Pi 4 card is pre-set to the identity of the board it moves to
  (hostname, static IP, SSH host keys), so after the swap the name, IP and host key still match;
  `known_hosts` on node-workstation and the Mac stays valid. Step 6 checks hostname, IP, RAM size and SD size
  against `ram_gb` / `sd_gb` in the inventory and stops if a card is in the wrong Pi.
- **cloud-init** (Raspberry Pi OS, NoCloud from `/boot/firmware/user-data`) re-applies the hostname at
  every boot (`preserve_hostname: false`). The playbook disables it (`/etc/cloud/cloud-init.disabled`)
  and updates `hostname:` in `user-data`.
- **k3s settings** come from the inventory: groups `k3s_servers` / `k3s_agents`, per host
  `k3s_labels`, `k3s_taints`, `k3s_reserved`; written to `/etc/rancher/k3s/config.yaml`; version
  `k3s_version` in `group_vars/all.yml`, installer pinned to that tag.
- **Restore**: Flux components, hand-made Secrets (not `pg-app`: CNPG makes a new one), Flux sync,
  wait for Postgres, rebuild `apps/app-env` from the new `pg-app`, `pg_restore --clean` as the owner,
  row counts compared with the backup, apps restarted, `public_urls` checked.
- Run log 2026-11-11: step 4b failed (sudo-rs prompt, fixed with `ansible_become_exe: sudo.ws`);
  resumed with `--tags workstation,swap,verify,k3s,restore`. In the restore, the first Postgres start
  on node-workstation hung after crash recovery ("redo done", postmaster stuck in `starting`, readiness
  500); deleting pod `pg-1` fixed it, and restore.yml now does that once by itself after 5 min.
- Partial run: each playbook also runs alone (`k3s.yml`, `restore.yml -e backup_file=...`).
- Until phase 2 has run, `workstation-prepare.yml` cannot fetch the kubeconfig (`k3s_server` is already node-workstation).

## Uninstall / reset
```bash
ssh pi4-4gb /usr/local/bin/k3s-agent-uninstall.sh
ssh pi3     /usr/local/bin/k3s-agent-uninstall.sh
ssh pi4-8gb /usr/local/bin/k3s-uninstall.sh      # wipes the cluster state
```
