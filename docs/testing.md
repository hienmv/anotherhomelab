# Testing

Every part of the homelab and how it is tested: what a test proves, how to run it, when.
Status: ✅ automated (CI or a playbook), 🟡 by hand (documented command), ❌ missing (planned).

Quick health check of the running system (read-only, no sudo, about 2 min):

```bash
cd ansible && ANSIBLE_BECOME_ASK_PASS=false ansible-playbook playbooks/verify.yml
#                                       ... --tags nodes,network,cluster,containers,data,apps,public,backup
```

**When to run `verify.yml`**: after every rebuild, migration, k3s or OS upgrade, node change, and
whenever something looks wrong. It must end with `failed=0` on every host.

## 1. Development (application code)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| TicketLite backend unit tests | config parsing, limits, metric names (`MetricsTest` guards the dashboard) | `gradle test`, inside the Docker build | every push / PR touching `apps/ticketlite/` | ✅ CI |
| TicketLite web tests (31) | every Atomic Design level, `buyState`, API errors, ConcertsPage flow | `npm test` (Vitest + RTL), inside the Docker build | same | ✅ CI |
| landing / notes checks | type check, unit tests, build, **no broken links** | `npm run check && npm test && npm run build && npm run check:links` in the Docker build | every push / PR touching the app | ✅ CI |
| TicketLite end-to-end | real Postgres + Redis, order flow | docker compose by hand ([apps/ticketlite](../apps/ticketlite/README.md#tests)) | before risky backend changes | 🟡 |
| TicketLite rush test | 30 parallel buys on 5 tickets: exactly 5 sold, per-user limit holds, nothing stuck | by hand ([apps/ticketlite](../apps/ticketlite/README.md)) | after queue/limit changes | 🟡 |

A failing test fails the image build, so a broken image never reaches GHCR or the cluster.

## 2. Delivery (CI/CD, GitOps)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Image builds for both platforms | the image runs on the Pis (arm64) and node-workstation (amd64) | `build-image.yml`, `platforms: linux/amd64,linux/arm64` | every app build | ✅ CI |
| Deploy reaches the cluster | the bumped tag is applied | Flux kustomizations Ready (`verify.yml --tags cluster`) + new pods running the tag | after every deploy | ✅ playbook |
| Manifests valid before merge | a bad YAML never breaks Flux | `kustomize build` + `kubeconform` per `k8s/` folder in CI | every PR touching `k8s/` | ❌ planned |

## 3. Infrastructure (machines, OS, network)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Identity | hostname and static IP match the inventory | `verify.yml --tags nodes` | always | ✅ |
| Disk space | root filesystem ≥ 15 % free | `verify.yml --tags nodes` | always | ✅ |
| Pi power | no under-voltage right now (`vcgencmd get_throttled`) | `verify.yml --tags nodes` | always | ✅ |
| Hardware after an SD swap | name, IP, RAM size and SD size per board | `phase2-migrate.yml` step 6 (`ram_gb`, `sd_gb` in the inventory) | after moving SD cards | ✅ |
| Node-to-node network | every node pings every node and the gateway; HTTPS to the internet | `verify.yml --tags network` | always | ✅ |
| Workstation base setup | static IP, never sleeps, SSH keys only, tools, keys | `workstation-prepare.yml` is idempotent: a re-run must report no changes | after a reinstall or config change | ✅ |

## 4. Platform (k3s, containers, Flux)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Nodes Ready, names = inventory | cluster membership | `verify.yml --tags cluster` | always | ✅ |
| Flux + Helm releases Ready | git state is applied | `verify.yml --tags cluster` | always | ✅ |
| No stuck or crash-looping pods | every pod Running/Succeeded | `verify.yml --tags cluster` | always | ✅ |
| **Container canary** | a Postgres pod starts, checkpoints and reloads (signals work) on **every schedulable node** | `verify.yml --tags containers` (pods created and deleted) | **before moving data to a new or changed node**, after OS/k3s upgrades | ✅ |
| No container AppArmor denials | the Ubuntu 26.04 class of bug (K3S-GUIDE 10.3) is absent | `verify.yml --tags nodes` (`journalctl -k -b`) | always | ✅ |
| Failover | the cluster survives losing one node | stop a node, watch workloads move | after HA (roadmap stage 2) | ❌ |

## 5. Data (Postgres, Redis, backups)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Postgres healthy | CNPG `Cluster in healthy state`, query works, row counts printed | `verify.yml --tags data` | always | ✅ |
| Redis answers | `PING` → `PONG` with the password | `verify.yml --tags data` | always | ✅ |
| Data on the data node | every `data` pod runs on the `role=data` node | `verify.yml --tags data` | always | ✅ |
| Backup is fresh and decryptable | newest backup decrypts with the age key; warns when older than 7 days | `verify.yml --tags backup` | always | ✅ |
| Backup content | each dump starts with `PGDMP`; exact row counts recorded | `backup.yml` (fails on a bad dump) | every backup | ✅ |
| **Restore drill** | a backup really restores: row counts after `pg_restore` = row counts in the backup | `restore.yml` (used for the 2026-11-12 rebuild) | after every rebuild; drill monthly once scheduled | ✅ playbook, ❌ schedule |
| Scheduled backups | backups happen without a human | CNPG → R2 (WAL + base), roadmap stage 1 | – | ❌ |

## 6. Applications (running system)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Deployments fully available | desired replicas = available replicas in `apps` | `verify.yml --tags apps` | always | ✅ |
| Public URLs | all sites answer `200` through Cloudflare (`public_urls` in `group_vars/all.yml`) | `verify.yml --tags public`; also at the end of `restore.yml` | always | ✅ |

## 7. Edge and security

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Grafana behind Cloudflare Access | `302` to `*.cloudflareaccess.com`, never Grafana directly | `verify.yml --tags public` | always | ✅ |
| Unknown subdomains | `301/302/308` to the landing page | `verify.yml --tags public` | always | ✅ |
| SSH keys only | password login refused on node-workstation | `ssh -o PubkeyAuthentication=no node-workstation` → `Permission denied (publickey)` | after workstation changes | 🟡 |
| Image / dependency scanning | known CVEs in images | Trivy or Renovate in CI | – | ❌ |

## 8. Operations (procedures)

| Procedure | Built-in checks | Where |
|---|---|---|
| Migration / rebuild | `REBUILD` confirmation, fresh backup first, hardware check after SD swap, row counts after restore, URLs | `phase2-migrate.yml` (tags per step) |
| Reinstall node-workstation | decrypt test of every backup before use | [K3S-GUIDE 10.4](../K3S-GUIDE.md) |
| Any risky change | **canary first**: join or change one node, run `verify.yml --tags containers`, then move data | lesson from the [2026-11-11 migration](../K3S-GUIDE.md) |

## Gaps, in order (roadmap)

1. Scheduled backups to R2 + a monthly automated restore drill.
2. `verify.yml` on a timer (systemd on node-workstation) with alerts (e.g. Telegram), plus an outside
   probe from node-1gb.
3. Manifest validation in CI (`kustomize build` + `kubeconform`).
4. TicketLite end-to-end and rush test in CI (docker compose job).
5. Failover tests once the control plane is HA.
6. Image scanning (Trivy) and automated dependency updates (Renovate).
