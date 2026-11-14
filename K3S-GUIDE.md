# Homelab k3s Guide

How this cluster was built, why it looks the way it does, and how to rebuild or extend it.
The **current state** lives in [README.md](README.md) (diagrams, components) and in the manifests
under `k8s/`; this guide links to them instead of copying them.

| Topic | Document |
|---|---|
| **Beginner handbook** (concepts, Ansible, GitOps, daily operations) | [docs/learn/](docs/learn/README.md) |
| Overview, diagrams, components, admin workstation | [README.md](README.md) |
| Hostnames, Cloudflare, tunnel, Ingress, redirects | [docs/domains.md](docs/domains.md) |
| TicketLite (Ktor/React, CI, metrics) | [apps/ticketlite/README.md](apps/ticketlite/README.md) |
| Landing page (site apex) | [apps/landing/README.md](apps/landing/README.md) |
| Notes (`notes.<domain>`) | [apps/notes/README.md](apps/notes/README.md) |

## 1. Hardware (audit 2026-11-07, updated 2026-11-12)

IPs and per-node settings: `ansible/inventory.yml` (source of truth); the table describes this site.

| | node-workstation | node-8gb | node-4gb | node-1gb |
|---|---|---|---|---|
| IP (static) | 192.168.40.249 (Wi-Fi) | 192.168.40.250 | 192.168.40.251 | 192.168.40.252 |
| Board | Panasonic CF-SV9 (amd64) | Pi 4B rev 1.5 | Pi 4B rev 1.1 | Pi 3B+ |
| CPU / RAM | 8 threads / 15 GiB | 4x A72 / 7.6 GiB | 4x A72 / 3.7 GiB | 4x A53 / 905 MiB |
| Disk | NVMe SSD (Ubuntu partition 94 GB; Windows kept) | 64 GB SD (swapped in 2026-11-12) | 32 GB SD | 32 GB SD |
| OS | Ubuntu 24.04.4 LTS | Debian 13 trixie arm64 | same | same |
| Power | battery = UPS | OK | under-voltage at boot only | **under-voltage under load** |

Open hardware issues: **node-1gb power** (keep it to light pods); the Pis still run from SD cards (USB
SSDs for node-8gb / node-4gb are the next buy, needed for an HA control plane).

## 2. Design decisions

| Decision | Why |
|---|---|
| Single k3s server (node-workstation since 2026-11-12, before node-8gb), SQLite datastore, no HA | HA needs 3 etcd servers with SSDs; until the Pi 4s get SSDs: rebuild from git + Flux + backups (`phase2-migrate.yml`) |
| Node labels `role=data` (node-workstation), `role=apps` (node-8gb, node-4gb), `role=light` + taint (node-1gb) | Pin stateful pods to the SSD node; keep the weak node-1gb nearly empty. Set in `ansible/inventory.yml` |
| Data pinned to the `role=data` node (`local-path` volumes on its NVMe SSD) | Simple and fast; HA comes from a CNPG replica later, not network storage |
| Redis = cache + queue (streams) | Enough for a homelab; no extra broker |
| Public access only through Cloudflare Tunnel | No open router ports, free TLS ([docs/domains.md](docs/domains.md)) |
| Images built by GitHub Actions for amd64 + arm64, stored in private GHCR | Reproducible; pods run on the laptop and the Pis |
| GitOps with Flux, read-only deploy key | `git push` is the only deploy path |
| Static IPs (NetworkManager), not DHCP reservations; all in `ansible/inventory.yml` | The HUMAX router has no reservations and a fixed pool `.2–.254`; statics use `.249–.252` |
| One-level hostnames (`<app>.<domain>`) | Cloudflare's free certificate does not cover deeper names |
| node-workstation = admin box + k3s server + data (2026-11-10) | See README → Admin workstation |
| Ubuntu 24.04 LTS on node-workstation, not 26.04 (2026-11-11) | 26.04's AppArmor default breaks containers on k3s; no host AppArmor changes (section 10.3) |
| Hosts managed by Ansible (`ansible/`), cluster contents by Flux | Repeatable rebuilds; one inventory for every IP and node setting |
| Backups deferred (2026-11-08) | User decision; risk accepted until real data exists (section 8) |

## 3. Prepare the Pis (initial build 2026-11-07; now `ansible/`)

> History, with this site's values at the time. Do not copy these commands: use the playbooks.

History of the first manual build. Today the inventory and playbooks do this; section 10 has the
current procedure.

### 3.1 Access and static IPs
- Mac `/etc/hosts` maps `node-1gb`, `node-8gb`, `node-4gb`; `~/.ssh/config` sets `User hien`,
  `IdentityFile ~/.ssh/id_homelab`. Passwordless sudo via `/etc/sudoers.d/hien`.
- Static IP per Pi (connection name differs: `netplan-eth0` on the Pi 4s, `Wired connection 1` on node-1gb):
  ```bash
  sudo nmcli con mod netplan-eth0 ipv4.method manual ipv4.addresses 192.168.40.250/24 \
    ipv4.gateway 192.168.40.1 ipv4.dns 192.168.40.1
  sudo systemd-run --on-active=2 nmcli con up netplan-eth0   # apply after the SSH command returns
  ```
  Applying inside the live SSH session drops it; `systemd-run` avoids that.

### 3.2 Memory cgroup, upgrade, prerequisites
```bash
for h in node-1gb node-8gb node-4gb; do
  ssh $h 'sudo sed -i "1 s/\$/ cgroup_enable=memory/" /boot/firmware/cmdline.txt &&
          sudo apt-get update && sudo apt-get -y full-upgrade &&
          sudo apt-get -y install curl nfs-common open-iscsi jq && sudo reboot'
done
for h in node-1gb node-8gb node-4gb; do ssh $h 'echo $(hostname): $(cat /sys/fs/cgroup/cgroup.controllers)'; done  # must list memory
```
node-8gb had **509 locales** enabled: the `locales` upgrade compiled all of them (~40 min). Never reboot
while `dpkg --audit` reports unconfigured packages.

### 3.3 Free ports 80/443
Traefik's ServiceLB needs 80/443 on every node. Host nginx/php-fpm were removed (backups in
`~/web-backup-2026-11-07.tgz` on node-1gb and node-8gb), and the host cloudflared on node-1gb was replaced by the
in-cluster one.

### 3.4 node-1gb firewall
node-1gb runs ufw. k3s needs node-to-node and pod traffic allowed:
```bash
sudo ufw allow from 192.168.40.0/24; sudo ufw allow from 10.42.0.0/16; sudo ufw allow from 10.43.0.0/16
```

### 3.5 (Todo) SSD on node-8gb
```bash
sudo mkfs.ext4 -L ssd /dev/sda          # DESTROYS data on /dev/sda
echo 'LABEL=ssd /mnt/ssd ext4 defaults,noatime 0 2' | sudo tee -a /etc/fstab
sudo mkdir -p /mnt/ssd && sudo mount -a && sudo mkdir -p /mnt/ssd/k3s-storage
```
Then move the `local-path` volumes (Postgres, Redis, VMSingle) and pass
`--default-local-storage-path /mnt/ssd/k3s-storage` to the k3s server.

## 4. Install k3s

**Now:** `cd ansible && ansible-playbook playbooks/k3s.yml` installs or updates the whole cluster from
the inventory (server = group `k3s_servers`, agents = `k3s_agents`, labels/taints/reserved per host,
version `k3s_version`). The commands below are the original manual install (2026-11-08).

```bash
# server
ssh node-8gb 'curl -sfL https://get.k3s.io | sh -s - server --write-kubeconfig-mode 600 \
  --tls-san node-8gb --tls-san 192.168.40.250 --node-ip 192.168.40.250 --node-label role=data \
  --kubelet-arg=system-reserved=cpu=250m,memory=512Mi'

# agents
TOKEN=$(ssh node-8gb 'sudo cat /var/lib/rancher/k3s/server/node-token')
ssh node-4gb "curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.250:6443 K3S_TOKEN=$TOKEN sh -s - agent \
  --node-ip 192.168.40.251 --node-label role=apps --kubelet-arg=system-reserved=memory=256Mi"
ssh node-1gb "curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.250:6443 K3S_TOKEN=$TOKEN sh -s - agent \
  --node-ip 192.168.40.252 --node-label role=light --node-taint role=light:NoSchedule \
  --kubelet-arg=system-reserved=memory=128Mi"

# kubectl on the Mac
ssh node-8gb 'sudo cat /etc/rancher/k3s/k3s.yaml' | sed 's/127.0.0.1/192.168.40.250/; s/: default/: homelab/' \
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
| PostgreSQL 17 `pg` | `k8s/data/postgres.yaml` | 10Gi on the `role=data` node (node-workstation SSD); creates secret `data/pg-app`; host `pg-rw.data.svc:5432` |
| Redis 8.2 | `k8s/data/redis.yaml` | AOF, `noeviction` (queue must never drop jobs), 2Gi on the `role=data` node |
| cloudflared | `k8s/infra/cloudflared.yaml` | 1 replica on the `role=data` node; routing in [docs/domains.md](docs/domains.md) |
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

### Site variables in manifests (since 2026-11-13)

Manifests contain no domain, registry or tunnel ID: they use `${DOMAIN}`, `${IMAGE_REGISTRY}`,
`${CLOUDFLARE_TUNNEL_ID}`, filled in by Flux from the ConfigMap `site-settings`
(`clusters/homelab/site-settings.yaml`) via `postBuild.substituteFrom` on every Kustomization in
`platform.yaml`. Rules (all learned the hard way):

- **Strict mode is on**: an unknown `${x}` fails the whole Kustomization (it keeps the last applied
  state; nothing breaks, but nothing deploys). Escape a literal as `$${x}` (e.g. Traefik's `$${1}`).
- Resources whose content belongs to another tool (Grafana dashboards: `${datasource}`) carry the
  annotation `kustomize.toolkit.fluxcd.io/substitute: disabled`.
- Plain `$VAR` (no braces) is left alone.
- **Images**: Deployments use a plain name (`image: landing`); `kustomization.yaml` maps it
  (`newName: ${IMAGE_REGISTRY}/landing`, `newTag`). Kustomize does not match an image name that
  contains `${...}`, so writing `image: ${IMAGE_REGISTRY}/landing` silently drops the tag.
- A value used inside a ConfigMap built from a file (e.g. `vm-stack-values.yaml`) is substituted
  too, comments included: a comment-only change there changes the ConfigMap.
- **Roll out in two commits** when introducing substitution in a Kustomization: first
  `substituteFrom`, then the variables. Otherwise a Kustomization may build the new manifests with
  its old spec and apply a raw `${VAR}`.
- `clusters/<cluster>/flux-system/gotk-sync.yaml` (the Git URL) stays literal: it is the bootstrap,
  read before any variable exists; it is per-cluster config by nature.

Prove a refactor before pushing: render every `k8s/` folder with `kubectl kustomize`, substitute
with `flux envsubst --strict` per resource (skipping `substitute: disabled`), and diff against the
render from before; for step C the 36 resources were byte-identical and no pod restarted.

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
  --docker-server=ghcr.io --docker-username=<github-owner> --docker-password="$GHCR_TOKEN"; unset GHCR_TOKEN
```
The current token expires **2027-02**; when pulls fail with 401/403, create a new one and recreate the secret.

Todo: encrypt them into git with SOPS + age so a rebuilt cluster needs no manual steps.

## 7. Observability

| Part | Where | Notes |
|---|---|---|
| VictoriaMetrics single | node-workstation (`role=data`) | 30d retention, 10Gi |
| vmagent | node-workstation (`role=data`) | scrapes every 30s; extra targets in `k8s/monitoring/scrapes.yaml` (CNPG, cloudflared, TicketLite) |
| Grafana | a `role=apps` node | `https://grafana.<domain>` (behind Cloudflare Access, then admin login; docs/domains.md) and https://grafana.lan (LAN); dashboards incl. **TicketLite** (`k8s/monitoring/dashboards/`); memory limit 768Mi (384Mi was OOMKilled) |
| node-exporter | all 4 nodes | tolerates node-1gb's taint |

Not scraped: scheduler, controller-manager, etcd, kube-proxy (k3s binds them to localhost).
Alerting (vmalert + Alertmanager) is off until a notification channel is chosen.

### Grafana on node-workstation (LAN name)
Since 2026-11-12 `https://grafana.lan` is used from node-workstation only (the public URL with
Cloudflare Access works everywhere). `workstation-prepare.yml` sets it up:
1. `/etc/hosts`: `grafana.lan` → the k3s server IP (`lan_names` in `ansible/group_vars/all.yml`).
2. Trust: the private CA's **public** cert `ansible/files/grafana-lan-ca.pem` goes into the system
   store (`update-ca-certificates`) and into Firefox via `/etc/firefox/policies/policies.json`
   (Firefox on Linux has its own store). Restart Firefox after the first run.
3. Login: user `admin`, password
   `kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d`.

The CA (mkcert, made on the Mac) had its **private key deleted on 2026-11-12**: nothing can sign new
certificates with it. The `grafana.lan` cert (secret `monitoring/grafana-lan-tls`) is valid until
**2029-02-08**; before then, make a new CA + cert (`mkcert`, commands in
`k8s/monitoring/grafana-https.yaml`), replace `grafana-lan-ca.pem`, and re-run `workstation-prepare.yml`.

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

## 8. Backups (by hand since 2026-11-11; scheduled: todo)

`ansible/playbooks/backup.yml` (details in 10.2): hand-made Secrets + Postgres (`pg_dump`, exact row
counts), age-encrypted, on node-workstation only (the Mac copy was deleted 2026-11-12, owner's decision:
**backups and live data share one disk**). `restore.yml` restored the cluster
on 2026-11-12 with matching row counts. **Not scheduled yet:** a dead NVMe between manual backups
loses the changes since the last one.

| Part | Today | Planned |
|---|---|---|
| Postgres data | `backup.yml` by hand (dump + row counts) | CNPG backup to Cloudflare R2 (nightly base backup + WAL, 14 days), monthly restore drill |
| Secrets | `backup.yml` by hand (encrypted) | SOPS + age in git (same age key) |
| k3s state | rebuilt by `k3s.yml` + Flux | etcd snapshots to R2 once HA |
| Redis | not backed up (TicketLite resyncs from Postgres) | AOF copy if needed |
| age key, SSH keys | age key in the password manager; SSH keys recreated by `workstation-prepare.yml` | – |

3-2-1 rule: 3 copies, 2 devices, 1 off-site (R2: S3-compatible, 10 GB free, free egress).

## 9. Status

Done (details in git history):
- Static IPs, cgroups, k3s on all 4 nodes (server node-workstation since 2026-11-12), Ansible for every host
- Postgres (CNPG) + Redis; Flux GitOps; cloudflared in-cluster; Traefik forwarded-header trust
- VictoriaMetrics + Grafana (public behind Cloudflare Access, plus LAN mkcert HTTPS), TicketLite dashboard
- Encrypted backups + tested restore; `verify.yml` health checks ([docs/testing.md](docs/testing.md))
- Apps: TicketLite, landing (hien0ethan.com), notes (notes.hien0ethan.com, migrated from hienmv.github.io)
- CI: shared `build-image.yml` / `bump-tag.yml`, dependency layer cached, Node 24 actions

Open:
- [ ] node-1gb power supply; USB SSDs for node-8gb / node-4gb, then HA control plane + Postgres replica
- [ ] Scheduled backups to R2 + monthly restore drill (section 8)
- [ ] SOPS-encrypted secrets
- [ ] Alerting (choose a channel: Telegram, Discord, Slack, email)
- [ ] KEDA worker autoscaling; batch CronJobs
- [ ] Archive hienmv.github.io once the notes site is confirmed (user)

## 10. Migration: CF-SV9 as k3s server + data node (2026-11-10 to 2026-11-12, done)

Names (inventory = OS hostname = Kubernetes node): `node-workstation` (CF-SV9), `node-8gb`,
`node-4gb`, `node-1gb`. Ansible connects by IP from the inventory.

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
   `ssh-keygen -p -f ~/.ssh/id_homelab_workstation && ssh-add --apple-use-keychain ~/.ssh/id_homelab_workstation && ssh-copy-id -i ~/.ssh/id_homelab_workstation hien@<that IP>`
3. Mac: `cd ansible && ansible-playbook playbooks/workstation-prepare.yml -e workstation_ip=<that IP>`
   (asks the node-workstation sudo password on every run, `become_ask_pass`; the first run also asks a passphrase
   for the new node-workstation keys).

What it does: hostname `node-workstation`, `/etc/hosts` names, upgrade, SSH server keys-only, never sleep (logind + masked sleep
targets), Wi-Fi power saving off, static IP on the `lan_link` link, kubectl/helm/k9s/flux/sops/age
(pinned in `ansible/group_vars/all.yml`), passphrase-protected SSH keys node-workstation -> Pis (added to their
`authorized_keys`) and node-workstation -> GitHub, cached 8 h in an ssh-agent (GNOME's on the desktop, a fixed
`~/.ssh/agent.sock` one in SSH sessions),
a GitHub deploy key `homelab-workstation-admin` (write, this repo only, added with `gh` from the Mac), kubeconfig
`~/.kube/homelab.yaml`, repo clone at `~/workspace/source/homelab`. Reboots once on first run.

BIOS (F2 at boot), by hand: enable a battery charge limit / economy mode if present.

### 10.2 Back up secrets and PostgreSQL (phase 1, done 2026-11-11)

`ansible/playbooks/backup.yml` (script `ansible/files/backup.sh`), runnable any time, no downtime:

```bash
cd ansible && ansible-playbook playbooks/backup.yml   # as ops
```

- Exports the hand-made Secrets (list in the playbook, plus `data/pg-app`, whose password is inside
  `apps/app-env`'s `DATABASE_URL`) one file each, and every other Secret as `all-secrets.json`.
- `pg_dumpall --globals-only` + `pg_dump -Fc` per database (today: `app`).
- Plaintext only in `$XDG_RUNTIME_DIR` (tmpfs); the bundle is encrypted with **age** to
  `~/homelab-backup/homelab-<date>.tar.gz.age` on node-workstation (a copy goes to `~/homelab-backups/` on the
  machine running Ansible when that is another machine; none since 2026-11-12).
- Key: `~/.config/sops/age/keys.txt` on node-workstation (also the future SOPS key). **Copy it to the password
  manager**: without it no backup can be decrypted.
- Restore: `age -d -i ~/.config/sops/age/keys.txt <file> | tar -xzf - -C <dir>`.
  Check a dump without restoring: `... | tar -xOzf - ./postgres/app.dump | kubectl -n data exec -i pg-1 -c postgres -- pg_restore -l`.
- Not backed up: Redis (TicketLite resyncs its gates from Postgres), metrics history, Grafana's own DB
  (dashboards are in git).

Multi-arch: `build-image.yml` builds `linux/amd64,linux/arm64`, so app pods can run on node-workstation too.

### 10.3 Rebuild with node-workstation as server (phase 2, done 2026-11-12)

One command, about 1 h of downtime, run in a real terminal (prompts):

```bash
cd ansible && ansible-playbook playbooks/phase2-migrate.yml      # -e sd_swap=false: no SD swap
```

Prompts: node-workstation sudo password, `REBUILD`, Enter after the SD swap. Steps (header of the playbook has the
detail): fresh backup → uninstall k3s (agents, then the old server: its volumes are deleted) →
Pi identities → SD swap by hand → verify → `k3s.yml` → `restore.yml`.

- **Names**: inventory name = SSH name = OS hostname = Kubernetes node name (`node-workstation`, `node-8gb`,
  `node-4gb`, `node-1gb`).
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
- Decision 2026-11-11: node-workstation is reinstalled with **Ubuntu 24.04 LTS** instead of a host
  AppArmor change. Ubuntu 26.04's apparmor package sets
  `kernel.apparmor_restrict_unprivileged_unconfined = 1` (`/usr/lib/sysctl.d/10-apparmor.conf`), which
  stacks the container label and breaks containerd's signal rule (containerd issue #14485, no fixed
  release yet); 24.04's package (`4.0.1really4.0.1-0ubuntu0.24.04.9`) sets only
  `restrict_unprivileged_userns`. The custom profile (f431be6) was reverted, and `sudo.ws` removed
  (24.04's sudo is classic sudo). Before moving data: test Postgres pod + `journalctl -k | grep DENIED`.
- Partial run: each playbook also runs alone (`k3s.yml`, `restore.yml -e backup_file=...`).
- Until phase 2 has run, `workstation-prepare.yml` cannot fetch the kubeconfig (`k3s_server` is already node-workstation).

### 10.4 Runbook: (re)install node-workstation (done 2026-11-12, Ubuntu 24.04.4 LTS)

Use this for a reinstall, a replacement laptop, or a disk failure. Before wiping: backups must be on
another machine (`scp 'node-workstation:homelab-backup/*.age' ~/homelab-backups/` on the Mac) and the
age key in the password manager.

| # | Where | Step | Why |
|---|---|---|---|
| 1 | CF-SV9 | Install **Ubuntu 24.04 LTS** desktop from USB (F2 → Boot → USB first). User `hien`, computer name `node-workstation`, Wi-Fi | not 26.04: its AppArmor default breaks containers (10.3) |
| 2 | CF-SV9 | `sudo apt install -y openssh-server tmux` | Ubuntu Desktop ships without an SSH server. `systemctl is-active ssh` says `inactive` until the first login: 24.04 starts sshd on demand via `ssh.socket` (check that one) |
| 3 | Mac | `ssh-keygen -R <workstation static_ip>` (and the DHCP IP), then `ssh-copy-id -i ~/.ssh/id_homelab_workstation <admin_user>@<DHCP IP>` | the new install has new SSH host keys; the Mac key gets in once with the password |
| 4 | Mac | `gh repo deploy-key delete <id> -R <GIT_REPO_SLUG>` for the old `homelab-workstation-admin` (`gh repo deploy-key list ...`) | its private key died with the old disk; the playbook stops if the title exists with another key |
| 5 | workstation (as `ethan`) | create the automation user first: `sudo groupadd -g 2000 ops && sudo useradd -m -u 2000 -g 2000 -s /bin/bash -c 'homelab automation' ops && echo 'ops ALL=(ALL) NOPASSWD:ALL' \| sudo tee /etc/sudoers.d/ops`; copy the repo from the Mac (`scp -r`, then `sudo cp -r … /home/ops/workspace/source/homelab && sudo chown -R ops: …`); then `sudo -iu ops`, `cd ~/workspace/source/homelab/ansible && ansible-playbook playbooks/workstation-prepare.yml -e workstation_ip=<DHCP IP>` | Ansible runs as `ops`; key passphrase for the new keys |
| 6 | workstation | paste the age key into `~/.config/sops/age/keys.txt`, `chmod 600`; `age-keygen -y` shows the public key | needed to decrypt backups (and SOPS later) |
| 6b | workstation | restore `id_homelab` into `/home/ethan/.ssh/` and `id_ops` into `/home/ops/.ssh/` (+ `.pub`, `chmod 600`, owned by each user) from the password manager **before** workstation-prepare creates new ones | the Pis accept only these keys (10.5) |
| 7 | Mac | `scp ~/homelab-backups/homelab-*.tar.gz.age node-workstation:homelab-backup/`, then decrypt-test each file | restore.yml reads them from the workstation |

Then the cluster: `phase2-migrate.yml --tags teardown,k3s` (Pis leave the old cluster, new empty
cluster), canary test (below), `--tags restore`.

Issues met on the way (all fixed in the playbooks): logind drop-in directory missing; `pause` vs
`vars_prompt` for the passphrase; `python3-bcrypt` not preinstalled on 24.04 (needed to encrypt keys);
sudo-rs on 26.04 (gone with 24.04).

### 10.5 Access model (since 2026-11-13)

Two roles, the same on every host (inventory `admin_user`, `automation_user`); write them as
`user@host`. Beginner explanation (passwordless, sudo, `sudo -iu`): [docs/learn/5](docs/learn/5-accounts-and-ssh.md).

| Account | Logs in with | sudo | Holds | Used for |
|---|---|---|---|---|
| `maihien@Mac` | Mac password | – | `~/.ssh/id_homelab_workstation` (passphrase, Keychain) | `ssh node-workstation` |
| **`ethan@node-workstation`** (1000) | desktop password, or the Mac key over SSH | with password | `id_homelab` (→ `ethan@<Pi>`) | **you**; `sudo -iu ops` for automation |
| **`ops@node-workstation`** (2000) | none (no password, no SSH key): only `sudo -iu ops` | passwordless | `id_ops`, `id_github`, kubeconfig, age key, backups, repo checkout | **automation** (Ansible, scheduled jobs) |
| `ethan@<Pi>` (1000) | only `id_homelab`, only from node-workstation's IP | passwordless (owner's choice) | nothing | you: debugging, learning |
| `ops@<Pi>` (2000) | only `id_ops` (no passphrase), only from node-workstation's IP; password locked | passwordless | nothing | Ansible |

```
maihien@Mac --(id_homelab_workstation)--> ethan@node-workstation --(id_homelab)--> ethan@<Pi>
                                                 | sudo -iu ops (your password)
                                                 v
                                          ops@node-workstation --(id_ops, Ansible)--> ops@<Pi>
```

- Set up by `ansible/playbooks/access.yml` (idempotent; its bootstrap play connects as `admin_user`).
- **Fixed IDs** (inventory `admin_uid`, `automation_uid`): `ethan` = 1000, `ops` = 2000 on every
  host, so a file owner means the same account everywhere.
- **History**: until 2026-11-15 the human was `hien` everywhere and `hien@node-workstation` also ran
  all automation; renamed then (section 10.6).
- **Both keys exist only on node-workstation, and the Pis accept them only from its IP.** Keep a copy
  of `~/.ssh/id_homelab` and `~/.ssh/id_ops` (+ `.pub`) in the password manager. After a reinstall
  (same IP), put them back before running `access.yml`; without them, only the console (keyboard +
  monitor on each Pi) can add a new key.
- Why no passphrase on the ops key: scheduled jobs (verify, alerts) must run unattended, and whoever
  controls node-workstation already controls the Pis through the cluster-admin kubeconfig, so a
  passphrase would add little. The IP restriction makes a leaked copy useless elsewhere.
- SSH servers: keys only, no root login (`/etc/ssh/sshd_config.d/10-homelab.conf`; it must sort
  before Raspberry Pi OS's `50-cloud-init.conf`, which turns passwords on: sshd uses the first value).
- node-workstation runs Ansible locally as `ops` (`sudo -iu ops` from `ethan`).
- **Decision 2026-11-14: human SSH to the Pis stays** (`hien`, key + passwordless sudo) for learning,
  simulation and debugging. Target later, when the homelab is "production": **no human SSH** on the
  Pis (`AllowUsers ops`, `hien` without key and sudo there, break-glass = console with a password in
  the password manager, debugging via `kubectl debug node/...` and Ansible ad-hoc as ops), with
  node-workstation as the only SSH entry point (bastion).
- Wi-Fi: node-workstation sees 5-10 % packet loss and latency spikes to ~570 ms (2026-11-13), the
  Pis on cable 0 %. `verify.yml` reports loss and fails only at >= 50 %. Ethernet would fix it.

### 10.6 Rename: hien -> ethan (human) and ops (automation) (done 2026-11-15)

Before: `hien` was the human everywhere, and `hien@node-workstation` also ran all automation and
held every key. After: the roles of 10.5. How it was done (each step re-runnable):

| # | Who, where | Step |
|---|---|---|
| 1 | you, node-workstation desktop | copy anything personal from `/home/hien` (Downloads, browser bookmarks) to `ethan`; **log out** of hien, **log in as ethan** |
| 2 | you, as ethan, terminal | `sudo bash /tmp/2026-11-15-rename-hien-to-ops.sh` (script: `scripts/one-off/`): ends hien's sessions, renames `hien` -> `ops` (UID/GID 2000, `/home/ops`), locks its password, hides it from the login screen, passwordless sudo, moves `id_homelab` and the Mac's login key to ethan |
| 3 | Mac | `~/.ssh/config`: `Host node-workstation` gets `User ethan`; `ssh node-workstation` -> ethan |
| 4 | you, as ethan | `sudo -iu ops`, `cd ~/workspace/source/homelab && git pull` |
| 5 | as ops | `cd ansible && ansible-playbook playbooks/workstation-prepare.yml` (Enter at the passphrase) |
| 6 | as ops | `ansible-playbook playbooks/access.yml`: on the Pis `hien` -> `ethan` (same UID 1000), Raspberry Pi Connect removed, keys IP-restricted |
| 7 | as ops | `ansible-playbook playbooks/verify.yml`; as ethan: `ssh-add ~/.ssh/id_homelab && ssh node-8gb whoami` = `ethan` |

Checked afterwards on all 3 Pis: `ethan` = 1000 with sudo, `hien` gone, Raspberry Pi Connect packages
and processes 0, no linger, the old Connect sign-in key gone from `/boot/firmware/user-data`, `ops` =
2000, sudo rules only `ethan` and `ops`, `ethan`'s key restricted to node-workstation's IP.
Gotcha: a terminal opened before `workstation-prepare.yml` added the ssh-agent lines to `~/.bashrc`
says "Could not open a connection to your authentication agent": `source ~/.bashrc` (or log in again).

## Uninstall / reset

Removes k3s from every node (agents first, then the server: **its volumes, i.e. Postgres, are
deleted**). Take a backup first (`backup.yml`). Rebuild: `k3s.yml`, then `restore.yml`.

```bash
cd ansible
ansible k3s_agents  -b -m command -a /usr/local/bin/k3s-agent-uninstall.sh
ansible k3s_servers -b -m command -a /usr/local/bin/k3s-uninstall.sh    # wipes the cluster state
```
