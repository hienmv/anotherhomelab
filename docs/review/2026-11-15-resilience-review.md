# Resilience review (2026-11-15)

The homelab compared with how real, resilient distributed systems are built and run, aspect by
aspect. For each: what real systems do, what we have, the gap, what to do next. Written right after
the first rebuild drill (cluster wiped and rebuilt from git + config + backup).

Maturity scale: **1** ad hoc · **2** basic · **3** solid · **4** strong · **5** world class.

## Scorecard

| Aspect | Score | One line |
|---|---|---|
| Configuration as code | **4.5** | no site values in code; validated in CI; one source per layer |
| Documentation & runbooks | **4.5** | beginner handbook, guide, runbooks, postmortem, test catalog |
| Disaster recovery (rebuild) | **4** | full rebuild from git + config + backup, drilled; row counts checked |
| Deployment pipeline (CI/CD, GitOps) | **3.5** | tests in every build, immutable image tags, Flux, validation; no PR gate, no staging |
| Security | **3** | no open ports, Access, keys only, separated accounts; secrets by hand, no pod/network policies |
| Rollback | **2.5** | `git revert` works for apps and config; database changes are forward-only, no snapshot before them |
| Development workflow | **2.5** | unit tests, link checks; end-to-end tests by hand; no PR flow or preview |
| Data durability | **2** | encrypted, tested backups, but **manual and on the same disk as the database** |
| Observability & alerting | **2** | metrics and dashboards; no alerts, no central logs, no SLOs |
| Capacity & scaling | **2** | requests/limits set; no autoscaling, little headroom on the Pis |
| Infrastructure redundancy | **1.5** | one machine is control plane + data + tunnel + admin; Wi-Fi with packet loss |
| Dependency & upgrade management | **1.5** | versions pinned (good) but updated by hand; no automated update PRs |

**In short**: operated and documented like a serious system (score 4+), but **built like a single
server** (redundancy, data, alerting at 1.5–2). The next steps are about surviving failures without
a human, not about more documentation.

## 0. Before anything else: the repository is public

`hienmv/anotherhomelab` is **public** (it was private on 2026-11-07). No secret is in its history
(checked 2026-11-15: no private keys, age keys, tokens, tunnel credentials; the only `rpuak_` match
is the pattern that deletes it). What a stranger can read: LAN layout and IPs, account names, the
domain, the tunnel ID (not a credential), the Cloudflare Access team name, every decision and the
postmortem: useful reconnaissance, no keys.

| Choice | Do |
|---|---|
| Keep it public (portfolio, learning in the open) | fine as long as secrets never enter git: add secret scanning (GitHub's own, plus `gitleaks` in CI) |
| Make it private | `gh repo edit --visibility private`; nothing else changes (Flux uses its deploy key) |

**Decided 2026-11-16: keep it public.** Guards: `gitleaks` scans the whole history on every PR
(required check `secrets`), and GitHub **secret scanning + push protection** are on (a push that
contains a known token type is refused before it lands; 0 alerts when enabled). Rule stays: secrets
live in the password manager and on node-workstation, never in git.

## 1. Failure scenarios: what happens today

RTO = time until service is back. RPO = how much data can be lost.

| Failure | What happens | RTO | RPO | Real systems |
|---|---|---|---|---|
| **node-workstation disk dies** | cluster, data, tunnel, admin gone; **backups were on the same disk** | ~3 h (reinstall + rebuild) | **everything** (no off-site copy) | minutes; RPO ≈ 0 (replica + off-site WAL) |
| node-workstation off (power, Wi-Fi, reboot) | all sites down: it is control plane + database + tunnel | until it is back | 0 | no single machine can take everything down |
| one Pi dies | its pods move after ~5 min; Grafana is down (its volume is pinned to node-8gb) | 5 min / Grafana: until the Pi returns | 0 (Grafana settings: its volume) | seconds, no pinned state on app nodes |
| SD card of a Pi dies | as above, plus reinstall that Pi (~1 h) | ~1 h | 0 | nodes are cattle: replaced automatically |
| Wi-Fi drops (seen: 5–10 % loss) | slow or failed requests, nodes may flap `NotReady` | seconds–minutes | 0 | wired, bonded links |
| power cut | Pis and router off; the laptop runs on battery but is unreachable | minutes after power returns (k3s starts by itself) | 0 | UPS + generators, several sites |
| internet / Cloudflare outage | public sites down; cluster keeps running | the provider's | 0 | several providers or regions |
| GitHub down | no deploys; running pods fine; new pods on another node may not pull images | the provider's | 0 | mirrored registry, pinned digests |
| bad app release | Flux deploys it; 1-replica apps are down until fixed | ~5 min with `git revert` | 0 | canary releases, automatic rollback |
| bad database migration | Flyway runs forward; no snapshot first | restore from the last manual backup | **since the last backup** | expand/contract migrations, snapshot first, PITR |
| someone deletes something with `kubectl` | Flux recreates it within minutes (except data) | minutes | data: since backup | same, plus admission policies |
| node-workstation compromised | one cluster-admin credential, SSH to every Pi: everything | rebuild | – | short-lived credentials, MFA, least privilege, audit |
| keys lost | documented recovery (password manager, SD card) | 15 min–1 h | 0 | central identity, SSH CA |
| certificate / token expiry | GHCR pull token **2027-02**, `grafana.lan` cert **2029-02-08**: nothing warns | until noticed | 0 | expiry alerts |

## 2. Aspect by aspect

### Infrastructure redundancy (1.5)
- **Real systems**: N+1 everywhere: 3 control-plane nodes (etcd quorum), database primary + replica
  with automatic failover, 2+ copies of every stateless service spread over nodes, redundant power
  and network, nothing important on one machine.
- **Here**: node-workstation is the single point of failure for the control plane, Postgres, Redis,
  VictoriaMetrics and cloudflared. It is on Wi-Fi with measured packet loss. Pis run from SD cards;
  only the laptop has a battery.
- **Next**: Ethernet for node-workstation (`lan_link: ethernet`); a small UPS for router + Pis;
  USB SSDs for node-8gb/node-4gb, then a 3-node control plane and a Postgres replica (roadmap HA).

### Data durability (2)
- **Real systems**: 3-2-1 (3 copies, 2 media, 1 off-site), continuous backup (WAL archiving) for
  point-in-time recovery, automated, monitored, restore-tested on a schedule.
- **Here**: encrypted, complete backups with exact row counts, restore proven twice (migration and
  drill). But: **manual**, and **all copies on node-workstation's disk** (the Mac copy was deleted).
  Redis is not backed up (TicketLite recomputes from Postgres).
- **Next (most important item of this review)**: CNPG continuous backup to Cloudflare R2 (WAL +
  nightly base, 14 days); a nightly encrypted secrets backup to R2; `verify.yml` checks backup age;
  restore drill every 3 months.

### Deployment pipeline (3.5)
- **Real systems**: protected main branch, every change through a pull request with required checks,
  staging environment, progressive delivery (canary, automatic rollback), signed images.
- **Here**: tests inside every image build; immutable `sha-` tags; Flux GitOps; config validation and
  kubeconform in CI. But: **`main` is not protected** and every push deploys directly; `validate`
  only warns after the fact; no staging.
- **Next**: branch protection on `main` with `validate` + app builds as required checks, changes
  through PRs (even when alone: the checks run before anything deploys); later a staging namespace.

### Rollback (2.5)
- **Real systems**: one-click (or automatic) rollback of releases; database migrations backward
  compatible (expand → migrate → contract); a snapshot before risky changes; rollback runbooks.
- **Here**: apps and config: `git revert` + Flux, ~5 minutes, works. Cluster: `rebuild.yml` from
  backup. Database: Flyway migrations are forward-only, nothing snapshots the database before a new
  migration runs.
- **Next**: rule "migrations must be backward compatible" in the TicketLite README; a backup (or
  CNPG on-demand backup) before deploying a migration; a short "how to roll back" table per layer.

### Observability & alerting (2)
- **Real systems**: alerts to a person (on-call) based on user-facing SLOs, central logs, traces,
  dashboards, external probes ("is it up from the internet?").
- **Here**: VictoriaMetrics + Grafana with dashboards; `verify.yml` is excellent but **run by hand**;
  alerting is switched off; logs only via `kubectl logs`.
- **Next**: `verify.yml` + backup checks on a systemd timer as `ops`; alerts to Telegram/e-mail
  (vmalert + Alertmanager, a channel); an external uptime probe (node-1gb or a free service);
  later central logs (VictoriaLogs) and a simple SLO per site.

### Operations & disaster recovery (4)
- **Real systems**: runbooks, game days / DR drills, blameless postmortems, change history,
  automation for every routine task.
- **Here**: all of that exists: runbooks (guide 10–11), a drill done, a postmortem, every change in
  git, Ansible for every host task, health check with clear reasons.
- **Next**: schedule the routine (timers), record each drill's numbers in the drill log, and put
  expiry dates (GHCR token, certificate) into the alerting.

### Security (3)
- **Real systems**: zero trust access with MFA, short-lived credentials, least privilege, secrets in
  a vault or encrypted in git, pod security standards, network policies, image scanning and signing,
  audit logs.
- **Here**: no open router ports (tunnel), Cloudflare Access on Grafana, SSH keys only, IP-restricted
  keys, human vs automation accounts separated, stock AppArmor, no secrets in git. Gaps: secrets
  created by hand (SOPS planned), no pod security or network policies, no image scanning, one
  cluster-admin kubeconfig for everything, passwordless sudo for the human on the Pis (by choice),
  public repo (section 0).
- **Next**: SOPS; Pod Security "baseline" (later "restricted") per namespace; default-deny
  NetworkPolicies for `data`; Trivy in CI; secret scanning.

### Development workflow (2.5)
- **Real systems**: pull requests with review, automated unit + integration + end-to-end tests,
  preview environments per PR, fast local development.
- **Here**: unit tests and link checks in every build, local development documented. End-to-end and
  load ("rush") tests are manual; no PR flow; one developer.
- **Next**: PR flow (with branch protection); TicketLite end-to-end test in CI with docker compose.

### Capacity & scaling (2)
- **Real systems**: autoscaling, load tests, capacity planning, headroom for a node failure.
- **Here**: requests/limits on the workloads; landing, notes and TicketLite web run **1 replica**;
  node-1gb barely usable; no load testing in CI.
- **Next**: 2 replicas + PodDisruptionBudgets for the public apps; check that losing one Pi still fits
  (requests vs capacity); KEDA for the TicketLite workers (planned).

### Dependency & upgrade management (1.5)
- **Real systems**: automated update PRs (Renovate/Dependabot) for images, charts, actions and
  libraries, tested by CI, merged regularly.
- **Here**: everything pinned (good: reproducible), updated by hand when noticed.
- **Next**: Renovate (or Dependabot) for GitHub Actions, Helm charts, container images, npm, Gradle.

### Configuration as code (4.5) and documentation (4.5)
Already at the level of good real systems: one source per layer, validated in CI, no site values
in code, docs that explain why. Keep them current with every change (the habit matters more than
the files).

## 3. Roadmap (in order)

| # | Step | Fixes | Effort |
|---|---|---|---|
| 1 | **Off-site automated backups**: tier 1 Garage S3 on an SSD on node-8gb (continuous Postgres WAL + nightly base, secrets archives), tier 2 encrypted nightly copy to R2; checked by `verify.yml`. **Parked 2026-11-15 until the SSD is ready**; code on branch `wip/offsite-backup` | total data loss if node-workstation's disk dies | 1 day + SSD |
| 2 | **Alerting**: scheduled `verify.yml` as `ops`, alerts to a phone, external uptime probe, expiry reminders. **Built 2026-11-15** (K3S-GUIDE 12) | nobody notices an outage | half a day |
| 3 | **Repo visibility decision** + secret scanning. **Done 2026-11-16**: public; gitleaks in CI, GitHub secret scanning + push protection on | public reconnaissance, accidental leaks | 30 min |
| 4 | **Branch protection + PR flow** with required checks. **Ruleset as code 2026-11-15** (`.github/rulesets/`) | a broken push deploys at once | 30 min |
| 5 | **Ethernet** for node-workstation; UPS for router + Pis | packet loss, power cuts | hardware |
| 6 | **2 replicas + PodDisruptionBudgets** for the public apps; Grafana storage not pinned to one Pi. **Copies + PDBs + spread check 2026-11-16** (Traefik, cloudflared, landing, notes, ticketlite); Grafana stateless 2026-11-16 (no volume, dashboards from git) | one Pi or one pod takes a site down | 1–2 h |
| 7 | **Database rollback safety**: backward-compatible migrations, backup before a migration | a bad migration loses data | 1 h + habit |
| 8 | **Renovate** | outdated dependencies, missed security fixes | 1 h |
| 9 | **SOPS** secrets in git | manual secret steps on rebuild | 1–2 h |
| 10 | **Security baseline**: Pod Security, NetworkPolicies, Trivy | blast radius of a compromised pod | 2–3 h |
| 11 | **HA**: USB SSDs → 3 control-plane nodes, Postgres replica, 2 cloudflared | node-workstation as single point of failure | a day + hardware |

Steps 1–4 change the scores the most for the least effort: data (2 → 4), alerting (2 → 3.5),
pipeline (3.5 → 4).
