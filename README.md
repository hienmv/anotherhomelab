# anotherhomelab

On-prem distributed system on 3 Raspberry Pis: k3s, PostgreSQL, Redis, web apps,
queue workers, batch jobs. Public via Cloudflare Tunnel:

| URL | App |
|---|---|
| https://hien0ethan.com | landing: intro, featured notes, homelab apps, about ([apps/landing](apps/landing/README.md)) |
| https://notes.hien0ethan.com | notes ([apps/notes](apps/notes/README.md)) |
| https://ticketlite.hien0ethan.com | TicketLite ([apps/ticketlite](apps/ticketlite/README.md)) |
| https://grafana.hien0ethan.com | Grafana (login; also `https://grafana.lan` on the LAN) |

Docs: **[docs/domains.md](docs/domains.md)** (how each hostname is wired, how to add one) ·
**[K3S-GUIDE.md](K3S-GUIDE.md)** (how the cluster was built, decisions, secrets, troubleshooting) ·
**[docs/testing.md](docs/testing.md)** (every test per part, `verify.yml` health check).

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
│ ┌─ k3s v1.37.2  (3 nodes) ──────────┼──────────────────────────────────────────────────────┐ │
│ │                                   ▼                   ┌─ ns monitoring ────────────────┐ │ │
│ │                    ┌─────────────────────────────┐    │ vmagent --scrape--> all pods   │ │ │
│ │                    │ cloudflared  (pod, pi4-8gb) │    │   & node-exporter (3 nodes)    │ │ │
│ │                    └──────────────┬──────────────┘    │ VMSingle: 30d, PVC 10Gi        │ │ │
│ │                                   │ svc traefik:80    │ Grafana: https://grafana.lan   │ │ │
│ │                                   ▼                   │ (LAN only, not public)         │ │ │
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
│ │                       │    PVC 10Gi, pi4-8gb     │    │  PVC 2Gi, AOF, pi4-8gb   │       │ │
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
┌─ pi4-8gb  .250 ────────────────┐  ┌─ pi4-4gb  .251 ────────────┐  ┌─ pi3  .252 ──────────────┐
│ Pi 4B, 8 GB RAM, SD 32 GB      │  │ Pi 4B, 4 GB RAM, SD 64 GB  │  │ Pi 3B+, 1 GB, SD 32 GB   │
│ k3s server (control plane)     │  │ k3s agent                  │  │ k3s agent                │
│ label role=data                │  │ label role=apps            │  │ label role=light         │
│                                │  │                            │  │ taint role=light:NoSched │
│ * traefik, coredns             │  │ * cnpg operator            │  │                          │
│ * metrics-server, local-path   │  │ * grafana, vm-operator     │  │ * node-exporter          │
│ * postgres pg-1                │  │ * node-exporter            │  │ * ufw firewall           │
│ * redis-0                      │  │ * app pods (k8s spreads)   │  │ - light pods only        │
│ * cloudflared (tunnel)         │  │ - KEDA autoscale (planned) │  │ ! under-voltage on load  │
│ * vmsingle, vmagent, ksm       │  │ - batch CronJobs (planned) │  │                          │
│ * node-exporter                │  │                            │  │                          │
│ * app pods (k8s spreads)       │  │                            │  │                          │
│ - SSD for PG/Redis (todo)      │  │                            │  │                          │
└────────────────────────────────┘  └────────────────────────────┘  └──────────────────────────┘

  * running    - planned / todo
```
<!-- diagram:nodes:end -->

## Components

| Component | Namespace | Runs on | Address (in-cluster) | Status |
|---|---|---|---|---|
| k3s v1.37.2 server | - | pi4-8gb | `https://192.168.40.250:6443` | running |
| k3s agent | - | pi4-4gb, pi3 (tainted `role=light`) | - | running |
| Traefik ingress | kube-system | pi4-8gb | `:80` on node IPs (ServiceLB) | running |
| cloudflared tunnel 2026.10.7 | infra | pi4-8gb (`role=data`), 1 replica | -> `traefik.kube-system.svc:80` | running |
| CloudNativePG operator 1.30 | cnpg-system | any | - | running |
| PostgreSQL 17 (`pg`) | data | pi4-8gb (`role=data`) | `pg-rw.data.svc:5432` | running, SD card |
| Redis 8.2 | data | pi4-8gb (`role=data`) | `redis.data.svc:6379` | running, SD card |
| TicketLite web + api x2 ([apps/ticketlite](apps/ticketlite/README.md)) | apps | Pi 4s (spread) | `https://ticketlite.hien0ethan.com` | running |
| Landing (Astro: intro, featured notes, Homelab menu/list, About) | apps | Pi 4 | `https://hien0ethan.com`; unknown subdomains 301 here | running |
| Notes (Astro, migrated from hienmv.github.io) | apps | Pi 4 | `https://notes.hien0ethan.com` | running |
| TicketLite worker x2 | apps | Pi 4s (spread) | Redis stream `orders`, group `workers` | running |
| KEDA worker autoscaling | apps | - | - | planned |
| Batch CronJobs | apps | pi4-4gb | - | planned |
| VictoriaMetrics (VMSingle 30d, vmagent) | monitoring | pi4-8gb | `vmsingle-vm-victoria-metrics-k8s-stack:8428` | running |
| Grafana (built-in + TicketLite dashboards) | monitoring | pi4-4gb | `https://grafana.hien0ethan.com` (public, login), `https://grafana.lan` (LAN, mkcert CA) | running |
| node-exporter, kube-state-metrics | monitoring | all nodes | - | running |
| Flux v2.10 (GitOps) | flux-system | Pi 4s | pulls this repo every 1m (read-only deploy key) | running |
| Backups (PG, Redis, k3s DB) | - | - | - | todo |

App config: secret `apps/app-env` provides `DATABASE_URL`, `REDIS_URL`, `REDIS_PASSWORD`.

CI/CD: per-app workflows (`ticketlite.yml`, `notes.yml`, `landing.yml`) call the shared
`build-image.yml` (arm64 image to private GHCR, tests run inside the Docker build) and `bump-tag.yml`
(commits the new tag to `k8s/apps/<app>/`); Flux deploys that commit.

Grafana access and browser troubleshooting: [K3S-GUIDE.md → Observability](K3S-GUIDE.md#7-observability).

## Nodes

| Node | IP (static) | Hardware | Role |
|---|---|---|---|
| pi4-8gb | 192.168.40.250 | Pi 4B, 8 GB, SD 32 GB | k3s server, data |
| pi4-4gb | 192.168.40.251 | Pi 4B, 4 GB, SD 64 GB | k3s agent, apps |
| pi3 | 192.168.40.252 | Pi 3B+, 1 GB, SD 32 GB | k3s agent, light pods only (taint); weak PSU |

## Operations

How changes reach the cluster.

<!-- diagram:ops:start -->
```text
┌─ Mac (admin workstation) ──────────┐                  ┌─ GitHub ─────────────────────────┐
│ repo ~/workspace/source/homelab    │    git push      │ hienmv/anotherhomelab (private)  │
│ kubectl, helm, k9s, flux (docker)  ├─────────────────►│ Actions: images -> GHCR (arm64)  │
│ kubeconfig ~/.kube/homelab.yaml    │                  │   then bump tag in k8s/ (commit) │
│ mkcert CA: signs grafana.lan       │                  └─────────────────┬────────────────┘
│ /etc/hosts: Pis + grafana.lan      │               pull every 1m (SSH)  │
│ ssh key id_homelab -> Pis          │              read-only deploy key  │
│ browser -> https://grafana.lan     │                                    │
└───────┬─────────────────┬──────────┘                                    │
        │ admin           │ OS, k3s                                       │
        │                 │                                               ▼
        ▼                 ▼                             ┌─ k3s: ns flux-system ────────────┐
  k3s API            Pis .250-.252                      │ source, kustomize, helm ctrls    │
  pi4-8gb .250       (hien@, sudo)                      │ apply clusters/homelab ->        │
                                                        │ k8s/* in dependency order        │
                                                        │ (secrets stay out of git)        │
                                                        └──────────────────────────────────┘
```
<!-- diagram:ops:end -->

### Admin workstation (Mac)

The Mac is part of the system, not just a laptop:

| Role | What lives there | If the Mac is lost |
|---|---|---|
| Git source | this repo; `git push` = deploy (Flux) | clone again from GitHub |
| Cluster admin | `~/.kube/homelab.yaml`, kubectl, helm, k9s, flux (docker) | re-copy `/etc/rancher/k3s/k3s.yaml` from pi4-8gb |
| Pi access | SSH key `~/.ssh/id_homelab`, ssh config, `/etc/hosts` names | **locked out** unless key is backed up |
| Private CA | mkcert CA key (signs `grafana.lan` cert, expires 2029-02-08) | new CA + new cert; old cert keeps working until expiry |
| LAN browser | trusts mkcert CA; Chrome needs Local Network permission | - |

Back up `~/.ssh/id_homelab` and the kubeconfig in a password manager. Never copy the mkcert CA
key to an always-on server: it can mint certificates this Mac trusts for any site.

With Flux, routine changes need only `git push` from any machine. The Mac is the
**break-glass** box: debugging, secrets, OS-level work on the Pis. Production setups use a
bastion host reached over VPN/SSO with audit logs; the homelab equivalent would be Tailscale
or Cloudflare Access for SSH (not set up).

**Decision (2026-11-08): no dedicated admin server.** Admin tasks (git, kubectl, ssh) need almost
no resources; the only heavy job is building arm64 app images, which an Apple Silicon Mac does
natively. A separate always-on admin box would add patching work and concentrate every key in one
target, while Flux already lets any machine deploy via `git push`. Scheduled ops (backups, cert
renewal) run in-cluster as CronJobs. Revisit if: several admins need audited access (bastion), or
remote admin is needed while the Mac is off (first try Tailscale). If added: small spec is fine,
keep it outside the cluster, not pi3. Hardware priority instead: SSD for pi4-8gb, pi3 PSU,
then a backup target (NAS/mini PC).

## Usage (GitOps)

1. Edit manifests under `k8s/` (or Helm values in `k8s/releases/`).
2. `git commit` and `git push` to `main`.
3. Flux applies it within ~1 minute, in order:
   `namespaces → releases → data → apps`, `namespaces → infra`, `releases → monitoring`.

```bash
export KUBECONFIG=~/.kube/homelab.yaml   # put this in ~/.zshrc, or every kubectl needs it
alias flux='docker run --rm -i --network host -v ~/.kube:/kube:ro -e KUBECONFIG=/kube/homelab.yaml ghcr.io/fluxcd/flux-cli:v2.10.0'
flux get kustomizations          # sync status
flux get helmreleases -A
flux reconcile source git flux-system   # pull now instead of waiting
```

- Layout: `clusters/homelab/` (Flux entry), `k8s/<folder>/` (what gets applied)
- Secrets are **not** in git; created once with `kubectl create secret` (list in [K3S-GUIDE.md → Secrets](K3S-GUIDE.md#secrets-kept-out-of-git-create-once-per-cluster)).
  `namespaces` and `data` have `prune: false`: deleting their files never deletes the database.

## Updating the diagrams

Diagrams are generated so box edges stay aligned. Edit `scripts/diagrams.py`, then:

```bash
python3 scripts/diagrams.py          # rewrites the diagram blocks in this README
python3 scripts/diagrams.py --print  # preview only
```

Update them whenever infrastructure changes (new service, node, or route).
