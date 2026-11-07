# anotherhomelab

On-prem distributed system on 3 Raspberry Pis: k3s, PostgreSQL, Redis, web apps,
queue workers, batch jobs. Public entry: **https://hien0ethan.com** (Cloudflare Tunnel).

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
│                                     ▼                                                        │
│                      ┌─────────────────────────────┐                                         │
│                      │ cloudflared  (pi3, systemd) │                                         │
│                      └──────────────┬──────────────┘                                         │
│                                     │ http://192.168.40.250:80                               │
│ ┌─ k3s v1.37.2  (pi4-8gb server + pi4-4gb agent) ──────────────────────────────────────────┐ │
│ │                                   ▼                                                      │ │
│ │                    ┌─────────────────────────────┐                                       │ │
│ │                    │       Traefik ingress       │                                       │ │
│ │                    └──────────────┬──────────────┘                                       │ │
│ │                                   │ Host rules                                           │ │
│ │           ┌───────────────────────┴───────────────────────┐                              │ │
│ │           ▼                                               ▼                              │ │
│ │  ┌─────────────────┐                        ┌┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┐                │ │
│ │  │  whoami (demo)  │                        ┆     web app  (planned)    ┆                │ │
│ │  │ 2 pods, ns apps │                        ┆   ns apps, env: app-env   ┆                │ │
│ │  └─────────────────┘                        └┄┬┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┬┄┄┄┄┄┘                │ │
│ │                                               │                   │                      │ │
│ │                                               │ SQL               │ enqueue              │ │
│ │                                               ▼                   ▼                      │ │
│ │                       ┌──────────────────────────┐    ┌──────────────────────────┐       │ │
│ │                       │   PostgreSQL 17 (CNPG)   │    │ Redis 8.2 (queue/cache)  │       │ │
│ │                       │   pg-rw.data.svc:5432    │    │   redis.data.svc:6379    │       │ │
│ │                       │    PVC 10Gi, pi4-8gb     │    │  PVC 2Gi, AOF, pi4-8gb   │       │ │
│ │                       └──────────────────────────┘    └──────────────────────────┘       │ │
│ │                         ▲           ▲                               ▲                    │ │
│ │                         │           │                               │ consume            │ │
│ │ ┌┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┴┄┐         │                 ┌┄┄┄┄┄┄┄┄┄┄┄┄┄┴┄┄┄┄┄┄┄┄┄┄┄┄┐       │ │
│ │ ┆ batch CronJob (planned) ┆         │  write          ┆    workers  (planned)    ┆       │ │
│ │ ┆  nightly / one-off Jobs ┆         └─────────────────┤ KEDA: scale on queue len ┆       │ │
│ │ └┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┘                           └┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┘       │ │
│ └──────────────────────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```
<!-- diagram:flow:end -->

## Node placement

<!-- diagram:nodes:start -->
```text
┌─ pi4-8gb  .250 ────────────────┐  ┌─ pi4-4gb  .251 ────────────┐  ┌─ pi3  .252 ──────────────┐
│ Pi 4B, 8 GB RAM, SD 32 GB      │  │ Pi 4B, 4 GB RAM, SD 64 GB  │  │ Pi 3B+, 1 GB, SD 32 GB   │
│ k3s server (control plane)     │  │ k3s agent                  │  │ NOT in k3s yet           │
│ label role=data                │  │ label role=apps            │  │ ! under-voltage on load  │
│                                │  │                            │  │                          │
│ * traefik, coredns             │  │ * cnpg operator            │  │ * cloudflared (systemd)  │
│ * metrics-server, local-path   │  │ * whoami x1                │  │ * ufw firewall           │
│ * postgres pg-1                │  │ - web app (planned)        │  │ - k3s agent, taint light │
│ * redis-0                      │  │ - workers + KEDA (planned) │  │ - cloudflared -> k3s     │
│ * whoami x1                    │  │ - batch CronJobs (planned) │  │                          │
│ - SSD for PG/Redis (todo)      │  │                            │  │                          │
└────────────────────────────────┘  └────────────────────────────┘  └──────────────────────────┘

  * running    - planned / todo
```
<!-- diagram:nodes:end -->

## Components

| Component | Namespace | Runs on | Address (in-cluster) | Status |
|---|---|---|---|---|
| k3s v1.37.2 server | - | pi4-8gb | `https://192.168.40.250:6443` | running |
| k3s agent | - | pi4-4gb | - | running |
| Traefik ingress | kube-system | pi4-8gb | `:80` on node IPs (ServiceLB) | running |
| cloudflared tunnel | host (systemd) | pi3 | -> `192.168.40.250:80` | running, move to k3s |
| CloudNativePG operator 1.30 | cnpg-system | any | - | running |
| PostgreSQL 17 (`pg`) | data | pi4-8gb (`role=data`) | `pg-rw.data.svc:5432` | running, SD card |
| Redis 8.2 | data | pi4-8gb (`role=data`) | `redis.data.svc:6379` | running, SD card |
| whoami demo | apps | both | catch-all Ingress | running |
| Web app | apps | pi4-4gb (`role=apps`) | `*.hien0ethan.com` | planned |
| Queue workers + KEDA | apps | pi4-4gb | Redis list `jobs` | planned |
| Batch CronJobs | apps | pi4-4gb | - | planned |
| Backups (PG, Redis, k3s DB) | - | - | - | todo |

App config: secret `apps/app-env` provides `DATABASE_URL`, `REDIS_URL`, `REDIS_PASSWORD`.

## Nodes

| Node | IP (static) | Hardware | Role |
|---|---|---|---|
| pi4-8gb | 192.168.40.250 | Pi 4B, 8 GB, SD 32 GB | k3s server, data |
| pi4-4gb | 192.168.40.251 | Pi 4B, 4 GB, SD 64 GB | k3s agent, apps |
| pi3 | 192.168.40.252 | Pi 3B+, 1 GB, SD 32 GB | not joined (power issue) |

## Usage

```bash
export KUBECONFIG=~/.kube/homelab.yaml
kubectl apply -f k8s/data/ -f k8s/apps/
```

- Full setup guide and checklist: [K3S-GUIDE.md](K3S-GUIDE.md)
- Manifests: `k8s/<namespace>/`
- Secrets (DB/Redis passwords, tunnel credentials) are created in-cluster with
  `kubectl create secret` and are never stored in this repo.

## Updating the diagrams

Diagrams are generated so box edges stay aligned. Edit `scripts/diagrams.py`, then:

```bash
python3 scripts/diagrams.py          # rewrites the diagram blocks in this README
python3 scripts/diagrams.py --print  # preview only
```

Update them whenever infrastructure changes (new service, node, or route).
