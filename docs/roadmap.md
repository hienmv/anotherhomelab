# Roadmap

One list of what is done and what is next, in the order it makes sense. It replaces the roadmap in
[review/2026-11-15-resilience-review.md](review/2026-11-15-resilience-review.md) (kept as the
snapshot of that day) and the to-do list in K3S-GUIDE. Update this file when a stage is done.

## Done

| Stage | What it gave | Since | Read |
|---|---|---|---|
| Alerting | 15-min checks (`verify.yml`), Telegram, healthchecks.io dead man's switch, expiry reminders | 2026-11-15 | K3S-GUIDE 12 |
| Repo + secret scanning | public repo; gitleaks in CI, GitHub push protection | 2026-11-16 | [learn/7](learn/7-git-workflow.md) |
| Protected `main` + PR flow | every change through required CI checks | 2026-11-15 | [learn/7](learn/7-git-workflow.md) |
| 2 copies of each public site | one Pi can die without a site going down (Traefik, cloudflared, apps, PDBs); Grafana stateless | 2026-11-16 | [learn/8](learn/8-surviving-a-node-failure.md) |
| Observability | metrics; central logs (VictoriaLogs); structured JSON logs; OpenTelemetry traces | 2026-11-16 | [learn/9](learn/9-monitoring-grafana.md), [learn/10](learn/10-tracing.md) |
| SLOs | availability 99.5 % + latency targets, burn-rate alerts, unit-tested rules | 2026-11-18 | [learn/11](learn/11-slo.md) |
| Everything through git | SOPS-encrypted secrets, instant chart upgrades, Flux → Telegram | 2026-11-18 | [learn/12](learn/12-gitops-secrets.md) |
| Host GitOps | the machines follow `main` by themselves (`homelab-apply.timer`) | 2026-11-18 | [learn/14](learn/14-host-gitops.md) |
| **Backups, stage 1** | SSD store (Garage) on node-8gb; Postgres point-in-time restore (drill: 54 s); nightly archive; encrypted off-site copy to R2; 3-2-1 met; backup dashboard + alerts | 2026-11-21 | [learn/13](learn/13-ssd-and-backups.md) |

## Next: hardware (only a person can do it)

| # | Item | Why | Status |
|---|---|---|---|
| H1 | **Official power supplies**: node-8gb 5.1 V / 3 A, node-1gb (Pi 3B+) 5.1 V / 2.5 A | under-voltage on node-8gb **during** the backup jobs; risks the SD card and the SSD | **urgent** |
| H2 | Ethernet for node-workstation | Wi-Fi packet loss; needed for stage A3 | open |
| H3 | A UPS for the router + Pis | power cuts (node-workstation has its laptop battery) | open |
| H4 | An SSD for node-4gb | stage A3 (etcd needs fast disks on every control-plane node) | open |

## Next: availability (survive a dead machine without restoring)

| # | Stage | Gives | Needs |
|---|---|---|---|
| A1 | **Postgres replica** on node-8gb's SSD (CNPG `instances: 2`) | node-workstation's disk dies → a live copy is ready, no restore | nothing new |
| A2 | Redis replica | the order queue survives one machine | nothing new |
| A3 | **3 k3s servers** (control plane on node-workstation, node-8gb, node-4gb; embedded etcd) | **automatic** failover: one machine dies, nothing stops | H2, H4 |
| A4 | k3s upgrades node by node (drain → upgrade → uncordon) | upgrades without downtime; `k3s.yml` can join host GitOps | A3 helps |

## Next: operations and safety

| # | Stage | Gives | Needs |
|---|---|---|---|
| O1 | **Host logs in Grafana** (journald → VictoriaLogs) | backup jobs, SSH, kernel, under-voltage messages searchable next to app logs | nothing |
| O2 | Monthly restore drill on a timer (PITR drill + off-site check) | "0 restore errors" stays proven without anyone remembering | nothing |
| O3 | **Database rollback safety**: backward-compatible migrations, a backup point before each migration | a bad migration cannot lose data | habit + 1 h |
| O4 | **Renovate** (dependency update PRs) | outdated images and libraries, missed security fixes | 1 h |
| O5 | **Security baseline**: Pod Security admission, NetworkPolicies, image scanning (Trivy) | a compromised pod reaches less | 2-3 h |
| O6 | HTTPS for the backup store on the LAN | backups encrypted on the wire too (the off-site copy already is) | 1 h |
| O7 | Secret rotation routine (age key, R2 token, Telegram bot, GHCR token) | a leaked key is replaced by a practised routine, not an emergency | 1-2 h |

## Later / apps

| # | Item |
|---|---|
| L1 | KEDA autoscaling for the TicketLite workers; batch CronJobs |
| L2 | Browser tracing (OpenTelemetry JS) for TicketLite |
| L3 | Archive hienmv.github.io once the notes site is confirmed (user) |

## Suggested order

1. **H1** now: a real risk today, and cheap.
2. **O1**: small, and makes everything after it easier to debug.
3. **A1**: the biggest gain in availability with the hardware you have.
4. O2, O3, O4: habits that keep the gains.
5. H2 + H4, then A3 and A4: full automatic failover.
