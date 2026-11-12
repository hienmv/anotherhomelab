# anotherhomelab

On-prem distributed system on a CF-SV9 laptop (k3s server + data, SSD) and 3 Raspberry Pis: k3s, PostgreSQL, Redis, web apps,
queue workers, batch jobs. Public via Cloudflare Tunnel:

| URL | App |
|---|---|
| https://hien0ethan.com | landing: intro, featured notes, homelab apps, about ([apps/landing](apps/landing/README.md)) |
| https://notes.hien0ethan.com | notes ([apps/notes](apps/notes/README.md)) |
| https://ticketlite.hien0ethan.com | TicketLite ([apps/ticketlite](apps/ticketlite/README.md)) |
| https://grafana.hien0ethan.com | Grafana (Cloudflare Access email PIN, then Grafana login; also `https://grafana.lan` on the LAN) |

Docs: **[docs/domains.md](docs/domains.md)** (how each hostname is wired, how to add one) ·
**[K3S-GUIDE.md](K3S-GUIDE.md)** (how the cluster was built, decisions, secrets, troubleshooting) ·
**[docs/testing.md](docs/testing.md)** (every test per part, `verify.yml` health check) ·
**[docs/postmortems/](docs/postmortems/)** (incidents: [2026-11-11 migration](docs/postmortems/2026-11-11-phase2-migration.md)).

## System design

Request and data flow. Solid boxes are running; dashed boxes are planned.

<!-- diagram:flow:start -->
```text
                               ┌─────────────┐
                               │    Client   │
                               └──────┬──────┘
                                      │ HTTPS  hien0ethan.com, *.hien0ethan.com
                                      ▼
                       ┌─────────────────────────────┐
                       │       Cloudflare Edge       │
                       │ TLS, DNS: @ and * -> tunnel │
                       └──────────────┬──────────────┘
                                      │ Cloudflare Tunnel (outbound only, no open ports)
┌─ Home LAN 192.168.40.0/24 ──────────┼────────────────────────────────────────────────────────┐
│ ┌─ k3s v1.37.2  (4 nodes) ──────────┼──────────────────────────────────────────────────────┐ │
│ │                                   ▼                   ┌─ ns monitoring ────────────────┐ │ │
│ │                    ┌─────────────────────────────┐    │ vmagent --scrape--> all pods   │ │ │
│ │                    │  cloudflared (workstation)  │    │   & node-exporter (4 nodes)    │ │ │
│ │                    └──────────────┬──────────────┘    │ VMSingle: 30d, PVC 10Gi        │ │ │
│ │                                   │ svc traefik:80    │ Grafana: grafana.<domain>      │ │ │
│ │                                   ▼                   │ (Cloudflare Access login)      │ │ │
│ │                    ┌─────────────────────────────┐    └────────────────────────────────┘ │ │
│ │                    │       Traefik ingress       │                                       │ │
│ │                    └──────────────┬──────────────┘                                       │ │
│ │                                   │ Host rules                                           │ │
│ │           ┌───────────────────────┴───────────────────────┐                              │ │
│ │           ▼                                               ▼                              │ │
│ │  ┌─────────────────┐                        ┌───────────────────────────┐                │ │
│ │  │  landing (apex) │                        │  TicketLite web + api x2  │                │ │
│ │  │   + notes app   │                        │    ticketlite.<domain>    │                │ │
│ │  └─────────────────┘                        └─┬───────────────────┬─────┘                │ │
│ │                                               │                   │                      │ │
│ │                                               │ SQL               │ gate + XADD          │ │
│ │                                               ▼                   ▼                      │ │
│ │                       ┌──────────────────────────┐    ┌──────────────────────────┐       │ │
│ │                       │   PostgreSQL 17 (CNPG)   │    │ Redis 8.2 (queue/cache)  │       │ │
│ │                       │   pg-rw.data.svc:5432    │    │   redis.data.svc:6379    │       │ │
│ │                       │  10Gi SSD, workstation   │    │ 2Gi SSD AOF, workstation │       │ │
│ │                       └──────────────────────────┘    └──────────────────────────┘       │ │
│ │                         ▲           ▲                               ▲                    │ │
│ │                         │           │                               │ consume            │ │
│ │ ┌┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┴┄┐         │                 ┌─────────────┴────────────┐       │ │
│ │ ┆ batch CronJob (planned) ┆         │  confirm tx     │   TicketLite worker x2   │       │ │
│ │ ┆  nightly / one-off Jobs ┆         └─────────────────┤ stream 'orders' consumer │       │ │
│ │ └┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┘                           └──────────────────────────┘       │ │
│ └──────────────────────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```
<!-- diagram:flow:end -->

## Node placement

<!-- diagram:nodes:start -->
```text
┌─ node-workstation  .249 ────────────────────┐  ┌─ node-8gb  .250 ────────────────────────────┐
│ CF-SV9, 8 CPU, 16 GB, NVMe SSD              │  │ Pi 4B, 8 GB RAM, SD 64 GB                   │
│ Ubuntu 24.04 LTS, Wi-Fi (static IP)         │  │ Debian 13, Ethernet                         │
│ k3s SERVER (control plane)                  │  │ k3s agent                                   │
│ label role=data                             │  │ label role=apps                             │
│                                             │  │                                             │
│ * postgres pg-1, redis-0 (SSD)              │  │ * traefik ingress, svclb                    │
│ * vmsingle, vmagent (metrics)               │  │ * grafana                                   │
│ * cloudflared (tunnel), coredns             │  │ * flux source + kustomize controllers       │
│ * cnpg operator, local-path, svclb          │  │ * app pods (k8s spreads)                    │
│ * app pods (k8s spreads)                    │  │ - USB SSD + control plane (todo)            │
│ * also the admin desktop (Ansible)          │  │                                             │
│ ! single point of failure (HA: todo)        │  │                                             │
│                                             │  │                                             │
└─────────────────────────────────────────────┘  └─────────────────────────────────────────────┘
┌─ node-4gb  .251 ────────────────────────────┐  ┌─ node-1gb  .252 ────────────────────────────┐
│ Pi 4B, 4 GB RAM, SD 32 GB                   │  │ Pi 3B+, 1 GB RAM, SD 32 GB                  │
│ Debian 13, Ethernet                         │  │ Debian 13, Ethernet, ufw                    │
│ k3s agent                                   │  │ k3s agent                                   │
│ label role=apps                             │  │ label role=light                            │
│                                             │  │ taint role=light:NoSchedule                 │
│ * ticketlite api / web / worker             │  │                                             │
│ * vm-operator, kube-state-metrics           │  │ * node-exporter only                        │
│ * app pods (k8s spreads)                    │  │ ! under-voltage on load                     │
│ - USB SSD + control plane (todo)            │  │ - later: outside uptime probe               │
│                                             │  │                                             │
└─────────────────────────────────────────────┘  └─────────────────────────────────────────────┘

  * running    - planned / todo    ! risk
```
<!-- diagram:nodes:end -->

## Components

| Component | Namespace | Runs on | Address (in-cluster) | Status |
|---|---|---|---|---|
| k3s v1.37.2 server | - | node-workstation | `https://192.168.40.249:6443` | running |
| k3s agent | - | node-8gb, node-4gb, node-1gb (tainted `role=light`) | - | running |
| Traefik ingress | kube-system | node-8gb | `:80` on node IPs (ServiceLB) | running |
| cloudflared tunnel 2026.10.7 | infra | node-workstation (`role=data`), 1 replica | -> `traefik.kube-system.svc:80` | running |
| CloudNativePG operator 1.30 | cnpg-system | any | - | running |
| PostgreSQL 17 (`pg`) | data | node-workstation (`role=data`) | `pg-rw.data.svc:5432` | running, NVMe SSD |
| Redis 8.2 | data | node-workstation (`role=data`) | `redis.data.svc:6379` | running, NVMe SSD |
| TicketLite web + api x2 ([apps/ticketlite](apps/ticketlite/README.md)) | apps | spread (any node except node-1gb) | `https://ticketlite.hien0ethan.com` | running |
| Landing (Astro: intro, featured notes, Homelab menu/list, About) | apps | any node | `https://hien0ethan.com`; unknown subdomains 301 here | running |
| Notes (Astro, migrated from hienmv.github.io) | apps | any node | `https://notes.hien0ethan.com` | running |
| TicketLite worker x2 | apps | spread | Redis stream `orders`, group `workers` | running |
| KEDA worker autoscaling | apps | - | - | planned |
| Batch CronJobs | apps | node-4gb | - | planned |
| VictoriaMetrics (VMSingle 30d, vmagent) | monitoring | node-workstation | `vmsingle-vm-victoria-metrics-k8s-stack:8428` | running |
| Grafana (built-in + TicketLite dashboards) | monitoring | node-8gb | `https://grafana.hien0ethan.com` (public, login), `https://grafana.lan` (LAN, mkcert CA) | running |
| node-exporter, kube-state-metrics | monitoring | all nodes | - | running |
| Flux v2.10 (GitOps) | flux-system | any node | pulls this repo every 1m (read-only deploy key) | running |
| Backups (Secrets + PG, age-encrypted) | - | node-workstation only (no off-machine copy) | `ansible/playbooks/backup.yml` | by hand; off-site + scheduled: todo |

App config: secret `apps/app-env` provides `DATABASE_URL`, `REDIS_URL`, `REDIS_PASSWORD`.

CI/CD: per-app workflows (`ticketlite.yml`, `notes.yml`, `landing.yml`) call the shared
`build-image.yml` (arm64 image to private GHCR, tests run inside the Docker build) and `bump-tag.yml`
(commits the new tag to `k8s/apps/<app>/`); Flux deploys that commit.

Grafana access and browser troubleshooting: [K3S-GUIDE.md → Observability](K3S-GUIDE.md#7-observability).

## Nodes

| Node (= hostname = k8s node) | IP (static) | Hardware | OS | Role |
|---|---|---|---|---|
| node-workstation | 192.168.40.249 (Wi-Fi) | Panasonic CF-SV9, 8 CPU, 16 GB, NVMe SSD | Ubuntu 24.04 LTS | k3s server, `role=data`, admin workstation |
| node-8gb | 192.168.40.250 | Pi 4B, 8 GB, SD 64 GB | Debian 13 | k3s agent, `role=apps` |
| node-4gb | 192.168.40.251 | Pi 4B, 4 GB, SD 32 GB | Debian 13 | k3s agent, `role=apps` |
| node-1gb | 192.168.40.252 | Pi 3B+, 1 GB, SD 32 GB | Debian 13 | k3s agent, `role=light` (taint); weak PSU |

All addresses and per-node settings live in [ansible/inventory.yml](ansible/inventory.yml).

## Operations

How changes reach the cluster.

<!-- diagram:ops:start -->
```text
┌─ node-workstation (admin) ─────────┐                  ┌─ GitHub ─────────────────────────┐
│ repo ~/workspace/source/homelab    │    git push      │ hienmv/anotherhomelab (private)  │
│ Ansible: inventory = all IPs       ├─────────────────►│ Actions: amd64+arm64 -> GHCR     │
│ kubectl helm k9s flux sops age     │                  │   then bump tag in k8s/ (commit) │
│ kubeconfig ~/.kube/homelab.yaml    │                  └─────────────────┬────────────────┘
│ ssh id_homelab -> Pis (passphr.)   │               pull every 1m (SSH)  │
│ age key: backups (+ SOPS later)    │              read-only deploy key  │
│ Mac / laptop: ssh in, nothing else │                                    │
└───────┬─────────────────┬──────────┘                                    │
        │ kubectl         │ Ansible                                       │
        │                 │                                               ▼
        ▼                 ▼                             ┌─ k3s: ns flux-system ────────────┐
  k3s API :6443      Pis .250-.252                      │ source, kustomize, helm ctrls    │
  itself (.249)      (hien@, sudo)                      │ apply clusters/homelab ->        │
                                                        │ k8s/* in dependency order        │
                                                        │ (secrets stay out of git)        │
                                                        └──────────────────────────────────┘
```
<!-- diagram:ops:end -->

### Admin workstation (node-workstation)

node-workstation (CF-SV9, always on at home) is the admin box **and** the k3s server + data node.
The Mac only SSHes into it (`ssh node-workstation`, key `~/.ssh/id_sv9`).

| Role | What lives there | If it is lost |
|---|---|---|
| Git source | this repo; `git push` = deploy (Flux); deploy key `sv9-admin` (this repo only) | clone again from GitHub |
| Automation | `ansible/` playbooks; inventory = every IP and node setting | re-run them from any machine with the keys |
| Cluster admin | `~/.kube/homelab.yaml`, kubectl, helm, k9s, flux | written again by `k3s.yml` |
| Pi access | `~/.ssh/id_homelab` (passphrase, ssh-agent 8 h) | add a new key on each Pi by hand (console) |
| Backups | `~/homelab-backup/*.tar.gz.age` (**the only copies**, same disk as the database), age key `~/.config/sops/age/keys.txt` | **data and backups are lost together**; the age key is also in the password manager |
| `grafana.lan` (LAN) | trusts the private CA's public cert (`ansible/files/grafana-lan-ca.pem`); CA key deleted 2026-11-12, cert valid until 2029-02-08 | new CA + cert; the public Grafana URL does not need it |

Rebuild from scratch: [K3S-GUIDE.md → 10.4 runbook](K3S-GUIDE.md). Health check:
`ansible-playbook playbooks/verify.yml` ([docs/testing.md](docs/testing.md)).

**Decision (2026-11-10, replaces 2026-11-08 "no admin box in the cluster"):** the CF-SV9 is admin
workstation, k3s server and data node in one. Why: data and control plane leave the SD cards, and the
battery acts as a UPS. Cost: one machine holds every key and the whole control plane (single point of
failure, and a browser on the same box), mitigated by a sudo password, passphrase keys, keys-only
SSH, AppArmor left stock. **Ubuntu 24.04 LTS, not 26.04:** 26.04's AppArmor default breaks every
container on k3s today (K3S-GUIDE 10.3). Next steps toward HA: USB SSDs for the Pi 4s, 3 control-plane
nodes, a Postgres replica (roadmap in K3S-GUIDE).

## Usage (GitOps)

1. Edit manifests under `k8s/` (or Helm values in `k8s/releases/`).
2. `git commit` and `git push` to `main`.
3. Flux applies it within ~1 minute, in order:
   `namespaces → releases → data → apps`, `namespaces → infra`, `releases → monitoring`.

On node-workstation (`ssh node-workstation`; KUBECONFIG is set in `~/.bashrc`, flux CLI installed by
`workstation-prepare.yml`):

```bash
flux get kustomizations          # sync status
flux get helmreleases -A
flux reconcile source git flux-system   # pull now instead of waiting
flux reconcile helmrelease vm -n flux-system   # Helm values (k8s/releases/*-values.yaml): interval is 1h
```

- Layout: `clusters/homelab/` (Flux entry), `k8s/<folder>/` (what gets applied)
- Secrets are **not** in git; created once with `kubectl create secret` (list in [K3S-GUIDE.md → Secrets](K3S-GUIDE.md#secrets-kept-out-of-git-create-once-per-cluster)),
  exported encrypted by `ansible/playbooks/backup.yml`, re-created by `restore.yml`.
  `namespaces` and `data` have `prune: false`: deleting their files never deletes the database.

## Updating the diagrams

Diagrams are generated so box edges stay aligned. Edit `scripts/diagrams.py`, then:

```bash
python3 scripts/diagrams.py          # rewrites the diagram blocks in this README
python3 scripts/diagrams.py --print  # preview only
```

Update them whenever infrastructure changes (new service, node, or route).
