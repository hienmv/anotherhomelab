# 1. The big picture

## The machines

| Name | What it is | Its job |
|---|---|---|
| **node-workstation** | Panasonic CF-SV9 laptop, Ubuntu 24.04, always on at home | the **brain** of the cluster (k3s server), the **data** (Postgres, Redis on its SSD), and your **admin desk** (Ansible, keys, backups) |
| **node-8gb**, **node-4gb** | Raspberry Pi 4 (8 GB, 4 GB RAM) | run the **apps** |
| **node-1gb** | Raspberry Pi 3 (1 GB RAM, weak power supply) | runs almost nothing (only monitoring) |
| **Mac** | your laptop | only `ssh node-workstation`; nothing of the homelab lives on it |

All four homelab machines together form one **cluster**: Kubernetes decides on which machine each
program runs. If you ask for "2 copies of TicketLite", it places them, restarts them when they
crash, and moves them when a machine disappears.

## The layers, from bottom to top

```
  Layer             What                                      Managed by
  ─────────────     ──────────────────────────────────────    ─────────────────────────────
  6  Apps           landing, notes, TicketLite                 code in apps/, built by CI
  5  Platform       Postgres, Redis, monitoring, tunnel        manifests in k8s/, applied by Flux
  4  Kubernetes     k3s: schedules and runs containers         ansible/playbooks/k3s.yml
  3  OS + accounts  Ubuntu / Debian, users, SSH, network       ansible/playbooks/*-prepare, access
  2  Machines       the laptop and three Pis                   you (hardware, cables)
  1  Network        home router, Wi-Fi/Ethernet, Cloudflare    router; Cloudflare dashboard
```

Each layer has **one tool** that owns it. That is the main idea of the whole setup: if you want to
change something, first find its layer, then change it with that layer's tool.

## How a web request travels

When someone opens `https://notes.<domain>`:

1. **Cloudflare** answers first (DNS, TLS certificate). Your home router has **no open ports**.
2. Cloudflare sends the request through the **tunnel**, a connection that the `cloudflared`
   program inside the cluster opened *outwards* to Cloudflare.
3. `cloudflared` hands it to **Traefik**, the cluster's front door (the "ingress").
4. Traefik looks at the hostname (`notes.…`) and sends it to the **notes** app.
5. The notes app (nginx serving static HTML) answers.

Details: [docs/domains.md](../domains.md).

## How a code change travels (GitOps)

When you change, say, a note:

1. You edit `apps/notes/...`, `git commit`, `git push`.
2. **GitHub Actions** (CI) builds a new container image, runs the tests inside the build, and pushes
   the image to the registry (GHCR).
3. CI writes the new image version into `k8s/apps/notes/kustomization.yaml` and commits that.
4. **Flux**, running inside the cluster, checks the repo every minute, sees the commit and applies
   it: Kubernetes starts the new version and stops the old one.

You never run `kubectl apply` by hand for normal changes. Git is the single source of truth. Chapter 3
explains this.

## How the machines themselves are set up

Kubernetes needs machines that are already prepared: users, SSH, network, k3s installed. That is
**Ansible**'s job (chapter 2). Ansible runs on node-workstation and reaches the Pis over SSH.

## What makes it "world class" (and what is still missing)

| Have | Missing (roadmap) |
|---|---|
| everything in git (code, config, docs) | high availability: today node-workstation is a single point of failure |
| automated builds, tests, deploys | scheduled off-site backups |
| health checks (`verify.yml`), validation in CI | alerting (someone gets told when it breaks) |
| encrypted backups, tested restore | secrets encrypted in git (SOPS) |
| documented decisions and a postmortem | |

Next: [2. Ansible](2-ansible.md)
