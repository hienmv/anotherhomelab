# Testing

Every part of the homelab and how it is tested: what a test proves, how to run it, when.
Status: ✅ automated (CI or a playbook), 🟡 by hand (documented command), ❌ missing (planned).

Quick health check of the running system (read-only, no sudo, about 2 min):

```bash
cd ansible && ansible-playbook playbooks/verify.yml          # as ops (sudo -iu ops)
#                                       ... --tags nodes,network,cluster,containers,data,apps,public,backup
```

**When to run `verify.yml`**: after every rebuild, migration, k3s or OS upgrade, node change, and
whenever something looks wrong. It must end with `<host>: all checks passed` for every host.

**Scheduled**: every 15 min (quick) and daily (full) by systemd timers, with Telegram messages
and a healthchecks.io dead man's switch: [K3S-GUIDE.md section 12](../K3S-GUIDE.md).

**How it reports**: every check runs, even after another one failed; each failing check adds a
one-line reason, and each host fails once at the end with all of them, e.g.
`2 problem(s) on node-workstation: pod: apps/landing-… Pending | config: unreplaced 1 ${DOMAIN}`.
Every Ansible run is also logged to `ansible/logs/ansible.log` (the file is git-ignored; the empty folder is kept in git, because without it Ansible silently does not log).

## 1. Development (application code)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| TicketLite backend unit tests | config parsing, limits, metric names (`MetricsTest` guards the dashboard), JSON log fields + level rule (`LoggingTest` guards the log queries), outage errors → 503 + Retry-After, bugs → 500 (`UnavailableTest`), read routing decisions, LSN order, unknown token, session format unchanged both ways (`ReadRouterTest`), request context + `TL_RYW` cookie (`ReadConsistencyTest`) | `gradle test`, inside the Docker build | every push / PR touching `apps/ticketlite/` | ✅ CI |
| TicketLite web tests (38) | every Atomic Design level, `buyState`, API errors, ConcertsPage flow; brief outages: reads retried (Retry-After, backoff, 90 s, banner), writes never retried, 503 vs 504 buy messages | `npm test` (Vitest + RTL), inside the Docker build | same | ✅ CI |
| nginx JSON access logs (landing, notes, ticketlite-web) | config valid, one JSON line per request, 5xx = ERROR, probes not logged | `nginx -t` + curl in the image (manual, 2026-11-16); in the cluster: Grafana `service.name:=notes` | after changing an `nginx.conf*` | ✅ manual |
| Tracing end to end (local) | api + worker spans form ONE trace through the Redis queue; collector drops probes, scrapes and housekeeping, keeps real requests | docker compose: buy a ticket, look up the trace in VictoriaTraces ([docs/learn/10](learn/10-tracing.md)) | after changing `Tracing.kt` or `otel-collector-config.yaml` | ✅ manual (2026-11-16) |
| GitOps secrets tooling | `alerting.yml` writes encrypted files, re-run leaves them identical, no plain value in them, decrypt round trip, kustomize + kubeconform valid, gitleaks clean | throwaway container with a test age key (manual, 2026-11-18) | after changing `scripts/secrets/` or `alerting.yml` | ✅ manual |
| GitOps secrets (cluster) | Flux's decryption key exists; `secrets` Kustomization Ready (Flux readiness check) | `verify.yml` | always | ✅ |
| Backup alert rules | WAL waiting, archive failing (not on a restart), base/job stale, job failed, job missing; exact Telegram text | `scripts/test-slo-rules.sh` (`backup-rules.test.yaml`), CI | every PR | ✅ CI |
| Backup metrics files | valid Prometheus text format; a failed run keeps the last success time | `promtool check metrics` + test runs (manual, 2026-11-19) | after changing `backup-metrics.sh` | ✅ manual |
| SLO alert rules | fake traffic in → the right burn-rate alerts (and none for healthy or low traffic); exact Telegram text | `scripts/test-slo-rules.sh` (vmalert-tool unittest), CI `validate` | every PR | ✅ CI |
| Alertmanager config | the rendered config is valid | `amtool check-config` on a rendered template (manual, 2026-11-18) | after changing `alertmanager.yml.j2` | ✅ manual |
| SLO pipeline (cluster) | Traefik metrics arrive, Alertmanager has its config; lists firing SLO alerts | `verify.yml --tags apps` (`slo:` problems) | always | ✅ |
| Tracing pipeline (cluster) | one test request's trace reaches VictoriaTraces (agent → collector → store) | `verify.yml --tags apps` (`traces:` problem) | always | ✅ |
| landing / notes checks | type check, unit tests, build, **no broken links** | `npm run check && npm test && npm run build && npm run check:links` in the Docker build | every push / PR touching the app | ✅ CI |
| TicketLite end-to-end | real Postgres + Redis, order flow | docker compose by hand ([apps/ticketlite](../apps/ticketlite/README.md#tests)) | before risky backend changes | 🟡 |
| TicketLite with Postgres down (e2e) | Postgres stopped: 503 + `Retry-After: 5` within ≤ 5 s through nginx (not a 30 s hang + 500), metric counted; Postgres back → 200 | docker compose by hand (2026-11-24; [design](design/2026-11-24-graceful-db-outage.md#test-plan)) | after changing error handling or the DB pool | ✅ manual |
| **Read routing (e2e)** | real primary + streaming replica: reads on the replica; read-your-writes right after a buy; replica paused → own reads from the primary (`not_caught_up`), anonymous after 10 s (`lagging`); replica down → breaker (1 s once, then ms); **primary down → reads still 200 from the replica**, buying 503 | `docker-compose.replica.yml` + script (by hand, 2026-11-25; [design](design/2026-11-25-read-routing.md#results-2026-11-25)) | after changing ReadRouter/Repo routing | ✅ manual |
| **Read routing: rolling deploy (e2e)** | purchase on the OLD image (no token) while the replica lacks the order → poll on the NEW image: 200 via primary (`no_token`), token set; roll back to the OLD image: still logged in | old + new image with `docker-compose.replica.yml` (by hand, 2026-11-26; [design §8](design/2026-11-25-read-routing.md#8-testing)) | after changing sessions/cookies or ReadRouter | ✅ manual |
| TicketLite rush test | 30 parallel buys on 5 tickets: exactly 5 sold, per-user limit holds, nothing stuck | by hand ([apps/ticketlite](../apps/ticketlite/README.md)) | after queue/limit changes | 🟡 |

A failing test fails the image build, so a broken image never reaches GHCR or the cluster.

## 2. Delivery (CI/CD, GitOps)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Image builds for both platforms | the image runs on the Pis (arm64) and node-workstation (amd64) | `build-image.yml`, `platforms: linux/amd64,linux/arm64` | every app build | ✅ CI |
| Deploy reaches the cluster | the bumped tag is applied | Flux kustomizations Ready (`verify.yml --tags cluster`) + new pods running the tag | after every deploy | ✅ playbook |
| Site settings valid | schema (format of every key), `GIT_REPO_SSH` = `GIT_REPO_SLUG`, `gotk-sync.yaml` URL = slug, `IMAGE_REGISTRY` = `ghcr.io/<repo owner>`, slug = this repo, `substituteFrom` on every Kustomization | `scripts/validate-config.py` (workflow `validate`) | every PR / push touching `k8s/`, `clusters/`, `ansible/`, `scripts/` | ✅ CI |
| Manifests render like Flux | every `k8s/` folder builds, strict substitution (unknown `${x}` fails), `substitute: disabled` honored | `scripts/render-k8s.py` (workflow `validate`) | same | ✅ CI |
| Manifests valid Kubernetes | kubeconform on the rendered output, CRDs from the datree catalog (36/36 valid, 0 skipped) | workflow `validate` | same | ✅ CI |
| Playbooks parse | `ansible-playbook --syntax-check` for every playbook | workflow `validate` | same | ✅ CI |
| No secrets in git | gitleaks over the whole history (false positives: `.gitleaks.toml`) | workflow `validate`, job `secrets` | every PR and push | ✅ CI |
| Checks before deploy | `main` accepts changes only through PRs whose `validate` jobs pass | ruleset `.github/rulesets/main.json` | always | ✅ (once applied) |

## 3. Infrastructure (machines, OS, network)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Identity | hostname and static IP match the inventory | `verify.yml --tags nodes` | always | ✅ |
| Disk space | root filesystem ≥ 15 % free | `verify.yml --tags nodes` | always | ✅ |
| Host GitOps apply | last `site.yml` run ok and < 7 h old | `verify.yml --tags cluster` (`gitops:`) | always | ✅ |
| Apply decisions (local) | change → 🔧 message; same commit skipped; failure → one message, retried silently; recovery → 🟢; new commit without changes → silent | simulation with fake ansible/curl (manual, 2026-11-18) | after changing `homelab-apply.sh` | ✅ manual |
| Off-site copy (R2) | last encrypted copy + cryptcheck < 26 h ago | `verify.yml --tags nodes` (`offsite:`) + alerts `BackupJobFailed/Stale` | always | ✅ (first success 2026-11-21) |
| Off-site job (local) | copy + cryptcheck, re-run, names unreadable, no plain text off-site, local delete does not delete off-site, decrypts | two Garage containers as store and R2 stand-in (manual, 2026-11-19) | after changing `homelab-offsite.sh` | ✅ manual |
| Garage rights reconcile (local) | read-only key granted read; a hand-given write is taken back | Garage container (manual, 2026-11-19) | after changing key rights | ✅ manual |
| Backup store (Garage) | service running + healthy on node-8gb | `verify.yml --tags nodes` (`backup-store:`) | always | ✅ |
| Backup in the store | newest archive < 26 h old locally AND in `ssd:archives` | `verify.yml --tags backup` | always | ✅ |
| Store + rclone + least privilege (local) | upload, list, age filter, retention, key refused on the other bucket | Garage v2.5.0 + rclone v1.75.2 containers (manual, 2026-11-18) | after changing `backup-store.yml` | ✅ manual |
| USB SSD (node-8gb) | mounted, SMART PASSED with 0 failing sectors / CRC errors, < 80 % full, ≤ 2 USB resets since boot | `verify.yml --tags nodes` (`ssd:` problems); setup `playbooks/ssd.yml` | always | ✅ |
| Pi power | no under-voltage, throttling or temperature limit, now **or since boot** (`vcgencmd get_throttled` as root; an unreadable state is a problem too) | `verify.yml --tags nodes` | always | ✅ |
| Hardware after an SD swap | name, IP, RAM size and SD size per board | `phase2-migrate.yml` step 6 (`ram_gb`, `sd_gb` in the inventory) | after moving SD cards | ✅ |
| Node-to-node network | every node reaches every node and the gateway (10 pings: >= 50 % loss fails, any loss is reported); HTTPS to the internet | `verify.yml --tags network` | always | ✅ |
| Workstation base setup | static IP, never sleeps, SSH keys only, tools, keys | `workstation-prepare.yml` is idempotent: a re-run must report no changes | after a reinstall or config change | ✅ |

## 4. Platform (k3s, containers, Flux)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Nodes Ready, names = inventory | cluster membership | `verify.yml --tags cluster` | always | ✅ |
| Flux + Helm releases Ready | git state is applied | `verify.yml --tags cluster` | always | ✅ |
| No stuck or crash-looping pods | every pod Running/Succeeded (pods being deleted are ignored) | `verify.yml --tags cluster` | always | ✅ |
| Site variables filled in | no raw `${KEY}` of `site-settings` in any live Deployment, Ingress, ConfigMap, Middleware, … | `verify.yml --tags cluster` | always, and after every change to `k8s/` or `site-settings` | ✅ |
| **Container canary** | a Postgres pod starts, checkpoints and reloads (signals work) on **every schedulable node**; waits for old canary pods first, allows 6 min (nodes may still pull images after a deploy) | `verify.yml --tags containers` (pods created and deleted) | **before moving data to a new or changed node**, after OS/k3s upgrades | ✅ |
| No container AppArmor denials | the Ubuntu 26.04 class of bug (K3S-GUIDE 10.3) is absent | `verify.yml --tags nodes` (`journalctl -k -b`) | always | ✅ |
| Host logs | every machine in the inventory sent journal lines in the last 15 min | `verify.yml --tags apps` (`logs: no host log lines from …`); apply smoke test reaches 10.43.0.50:9428 | always | ✅ |
| Workstation LAN link | `.249` on the `lan_link` port, flannel on the same port, Wi-Fi off and no rollback marker when ethernet | `verify.yml --tags network` (`network:` problem) | always | ✅ |
| Link switch logic (Wi-Fi ↔ Ethernet) | switch confirmed; gateway unreachable → rollback + marker; no flapping; retry after the marker is removed; back to Wi-Fi; idempotent | `scripts/test-net-static.sh` (fake NetworkManager), in CI | after changing `net-static.sh` | ✅ CI |
| Host logs path (local) | VictoriaLogs v1.53 accepts the journal at /insert/journald/upload, real systemd-journal-upload delivers with host/unit/level fields | containers (manual, 2026-11-21) | after changing host-logs.yml | ✅ manual |
| Central logs | a log collector is ready on every node; VictoriaLogs received lines in the last 15 min | `verify.yml --tags apps` (`logs:` problem) | always | ✅ |
| Descheduler re-spread (local) | 2 copies stacked on one node -> exactly 1 eviction -> one per node; second run 0 evictions | throwaway 2-node k3s v1.37.2 in Docker (manual, 2026-11-22) | after changing `k8s/releases/descheduler.yaml` | ✅ manual |
| Postgres replica (local) | 1 instance -> 2: pg-1 keeps its `local-path` volume, pg-2 lands on the other node on `local-ssd` at the mapped SSD path, rows copied, streaming; switchover; **failover** (primary's node stopped): the replica is promoted, `pg-rw` follows, no rows lost | throwaway 2-node k3s v1.37.2 + CNPG 1.30.1 in Docker (manual, 2026-11-22; [design](design/2026-11-22-postgres-replica.md#lab-results)) | after changing `k8s/data/postgres.yaml` or `k8s/infra/local-ssd.yaml` | ✅ manual |
| Postgres replication | ready instances = instances, `instances - 1` replicas streaming, instances on different nodes | `verify.yml --tags data` (`postgres:` problem) | always | ✅ |
| Postgres alerts | `PostgresReplicaNotStreaming` (no streaming replica 15 min), `PostgresReplicationLag` (> 5 min behind for 10 min) fire and stay quiet as intended | `scripts/test-slo-rules.sh` (`k8s/monitoring/postgres-rules.test.yaml`, in CI) | always | ✅ |
| Accepted risks | an accepted, unexpired risk is a warning (no alert); expired or none = a problem | Ansible test of the power result (manual, 2026-11-22) | after changing the logic | ✅ manual |
| Copies spread | Traefik, cloudflared, landing, notes, ticketlite-web/api, the CNPG operator each have copies on **2 nodes** | `verify.yml --tags apps` (`spread:` problem, with the fix) | always | ✅ |
| Failover (drain) | losing node-8gb or node-4gb keeps every site up (PDBs: one copy at a time) | `kubectl drain`, curl the sites, `uncordon` ([learn/8](learn/8-surviving-a-node-failure.md#try-it-the-failover-drill)) | after changes to replicas/PDBs, every ~3 months | ✅ procedure, ❌ not run yet |

## 5. Data (Postgres, Redis, backups)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Postgres healthy | CNPG `Cluster in healthy state`, query works, row counts printed | `verify.yml --tags data` | always | ✅ |
| Redis answers | `PING` → `PONG` with the password | `verify.yml --tags data` | always | ✅ |
| Data on the data node | every `data` pod runs on the `role=data` node; Postgres instances on `homelab/postgres=true` nodes | `verify.yml --tags data` | always | ✅ |
| Backup is fresh and decryptable | newest backup decrypts with the age key; warns when older than 26 h (nightly timer) | `verify.yml --tags backup` | always | ✅ |
| Backup content | each dump starts with `PGDMP`; exact row counts recorded | `backup.yml` (fails on a bad dump) | every backup | ✅ |
| Postgres continuous backup | WAL archiving works, newest base backup < 26 h | `verify.yml --tags data` (`pg-backup:`) | always | ✅ |
| **PITR drill** | restore to a chosen moment T into a temporary cluster, row counts identical to the live database at T | `playbooks/pitr-drill.yml` (log in docs/learn/13) | monthly, after backup changes | ✅ passed 2026-11-19 (restore 54 s) |
| **Rebuild drill** | the whole cluster rebuilds from git + config + backup, row counts match, everything healthy | `rebuild.yml` (K3S-GUIDE section 11, drill log there) | every ~3 months, after big changes | ✅ playbook, ❌ schedule |
| Scheduled backups | backups happen without a human | `homelab-backup.timer` nightly (archive → SSD store); Postgres WAL continuously + base nightly (CNPG) | continuous / nightly | ✅ |

## 6. Applications (running system)

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Deployments fully available | desired replicas = available replicas in `apps` | `verify.yml --tags apps` | always | ✅ |
| `grafana.lan` (LAN) | answers `302` with a certificate the workstation trusts (private CA, valid to 2029-02-08) | `verify.yml --tags apps` | always | ✅ |
| Public URLs | all sites answer `200` through Cloudflare (`public_urls` in `group_vars/all.yml`) | `verify.yml --tags public`; also at the end of `restore.yml` | always | ✅ |

## 7. Edge and security

| Test | Proves | How | When | Status |
|---|---|---|---|---|
| Grafana behind Cloudflare Access | `302` to `*.cloudflareaccess.com`, never Grafana directly | `verify.yml --tags public` | always | ✅ |
| Unknown subdomains | `301/302/308` to the landing page | `verify.yml --tags public` | always | ✅ |
| SSH keys only | password login refused on every host | `ssh -o PubkeyAuthentication=no <user>@<host>` → `Permission denied (publickey)` | after access changes | 🟡 (checked 2026-11-13, all 4) |
| Automation account | `ops` logs in with its key and gets root via sudo, on every Pi | `access.yml` (last play asserts it) | after access changes | ✅ |
| Image / dependency scanning | known CVEs in images | Trivy or Renovate in CI | – | ❌ |

## 8. Operations (procedures)

| Procedure | Built-in checks | Where |
|---|---|---|
| Migration / rebuild | `REBUILD` confirmation, fresh backup first, hardware check after SD swap, row counts after restore, URLs | `phase2-migrate.yml` (tags per step) |
| Reinstall node-workstation | decrypt test of every backup before use | [K3S-GUIDE 10.4](../K3S-GUIDE.md) |
| Any risky change | **canary first**: join or change one node, run `verify.yml --tags containers`, then move data | lesson from the [2026-11-11 migration](../K3S-GUIDE.md) |

## Gaps, in order (roadmap)

1. Scheduled backups to R2 + a monthly automated restore drill.
2. Metric-based alerts (vmalert + Alertmanager) between the 15-minute checks.
3. TicketLite end-to-end and rush test in CI (docker compose job).
4. Failover tests once the control plane is HA.
5. Image scanning (Trivy) and automated dependency updates (Renovate).
