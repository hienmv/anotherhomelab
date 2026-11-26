# Postgres replica on node-8gb's SSD (roadmap A1)

Status: built 2026-11-22 (lab-tested; deployed by the PR that adds this file)

## Problem

TicketLite's database runs as **one** Postgres instance (`pg-1`) on node-workstation. The backups are
good (PITR drill: 54 s; off-site copy), but a dead disk or a broken Postgres there still means:
notice, decide, restore into a new cluster, point the apps at it: minutes to an hour of downtime, and
up to ~5 minutes of orders lost (the last unarchived WAL). Real systems keep a **live second copy**
that takes over in seconds.

## Goals / non-goals

Goals: a streaming replica on another machine (node-8gb, on its SSD, not the SD card); automatic
failover when the primary's pod or disk fails while Kubernetes itself is fine; the apps keep using
`pg-rw` (it always points at the primary); replication visible and alerted.

Non-goals: surviving node-workstation dying completely. Its control plane runs the CNPG operator that
performs failovers; with the only k3s server gone, nobody can promote the replica (roadmap A3: 3
control-plane nodes). Until then a manual promote is possible (below). Also not: read scaling,
synchronous replication.

## Options considered

| Option | For | Against |
|---|---|---|
| **CNPG `instances: 2`** (streaming replication, operator-managed failover) | the operator we already run; standard; `pg-rw`/`pg-ro` services switch automatically; PDBs, fencing, switchover built in | needs a second node with good storage |
| restore-on-failure only (today) | nothing new | RTO minutes-to-hour, RPO up to 5 min |
| a replica cluster from the backups (`replica: source`) | uses the store | replays WAL in 5-minute steps: minutes behind; manual promotion |
| synchronous replication | zero data loss on failover | every write waits for node-8gb over Wi-Fi; if the replica is down, writes stop (unless relaxed): slower and more fragile for a homelab |

## Decision

CNPG `instances: 2`, **asynchronous** replication (default). The replica is typically under a second
behind; a failover can lose that second. Synchronous mode can come later (`minSyncReplicas`) if needed.

## Design

```text
 node-workstation (NVMe)                       node-8gb (USB SSD /mnt/ssd/k8s)
 pg-1  PRIMARY  ── streaming replication ───►  pg-2  REPLICA (hot standby)
   ▲ pg-rw (apps write here)                     ▲ pg-ro (reads, if wanted)
   └── WAL archive + base backups -> SSD store (unchanged; only the primary archives)
 CNPG operator: primary unhealthy -> promotes pg-2, pg-rw follows; pg-1 rejoins as replica later
```

| Piece | Where | Why |
|---|---|---|
| node label `homelab/postgres=true` on node-workstation + node-8gb | inventory `k3s_labels`, applied by a new `node-labels.yml` in host GitOps (`kubectl label`, idempotent) | k3s applies `--node-label` only when a node first joins; labels must be manageable later |
| placement: `nodeAffinity` **role=data OR homelab/postgres=true**, plus **required** anti-affinity (one instance per node) | `k8s/data/postgres.yaml` | the OR keeps pg-1 schedulable even before the new label exists (Flux applies in ~1 min, host GitOps in ≤15 min: requiring the label alone would leave the primary unschedulable = outage) |
| storage class `local-ssd`: a second, independent local-path provisioner; node-8gb → `/mnt/ssd/k8s`, every other node → k3s' usual `/var/lib/rancher/k3s/storage` | `k8s/infra/local-ssd.yaml` | k3s' own `local-path` would put pg-2 on node-8gb's SD card; k3s re-applies its own provisioner config, so it is not edited |
| `reclaimPolicy: Retain` on `local-ssd` | same | a deleted PVC keeps the database files on disk (safer for databases) |
| existing `pg-1` keeps its `local-path` volume | CNPG only uses the new class for new volumes | no data migration |
| `primaryUpdateMethod: restart` (CNPG default, written out) | `postgres.yaml` | on spec changes the primary restarts in place instead of switching over to the Pi |
| `tolerationSeconds: 60` for unreachable/not-ready nodes | `postgres.yaml` | the dead primary's pod is removed after ~1.5 min instead of 5 (cleanup; the failover itself does not wait for it) |
| **CNPG operator: 2 copies, hard-spread over nodes** (leader election) | `k8s/releases/cnpg.yaml` | found in the lab: the operator is what promotes; with one copy on the primary's node, a dead node took the operator with it and failover waited ~6 min (below) |

## Risks and how they are handled

| Risk | Answer |
|---|---|
| applying the change restarts pg-1 (pod spec changes) | ~30 s without the database, once. Since U1 ([design](2026-11-24-graceful-db-outage.md)) users see "Reconnecting…" and pages retry by themselves; a purchase in that window gets 503 "not placed" |
| pg-2 on a Pi with a USB-2 SSD is slower | it only replays WAL; as primary (after a failover) TicketLite's load is tiny. A switchover back is one command |
| a failover moves the primary to node-8gb | documented; `kubectl cnpg promote pg pg-1` (or the CNPG switchover) moves it back |
| node-workstation dies completely | out of scope (A3); manual: on a working control plane promote pg-2; until A3 the backups + PITR remain the answer |
| node-8gb's SSD missing | pg-2 cannot start (its volume path is gone): CNPG reports the replica down, the primary is unaffected; alert below |
| the second provisioner hands out SD-card paths | only node-8gb is mapped to the SSD; other nodes get the same path k3s uses; pg-2 can only run on node-8gb (anti-affinity + labels) |
| replication silently broken or lagging | alerts `PostgresReplicaNotStreaming`, `PostgresReplicationLag` (unit-tested); `verify.yml` checks 2 ready instances, 1 streaming replica |
| verify's "data pods on role=data" check | updated: Postgres instances may run on any `homelab/postgres` node |
| the replica is away for days: its replication slot keeps all WAL, the primary's volume fills, the **primary** stops | `max_slot_wal_keep_size: 3GB` (10Gi volume, `max_wal_size` 1GB); beyond it pg-2 catches up from the WAL archive on Garage (`restore_command`). Found while documenting the sync flows (learn/15), 2026-11-26 |
| the operator dies with the primary's node | 2 operator copies on different nodes; verify's spread check includes it |
| a failover loses the last writes | asynchronous: what the replica had not received yet (normally < 1 s). The lab lost nothing |

## Test plan

Before merging (throwaway 2-node k3s v1.37.2 in Docker, CNPG chart 0.29.1 as in production):
1. today's cluster (1 instance, local-path, role=data), some rows
2. apply the new manifests: pg-2 appears on the other node, its PVC is `local-ssd` at the mapped path,
   pg-1 keeps `local-path`, the rows are on pg-2, it streams
3. switchover to pg-2 and back
4. **failover**: stop the node of the primary (the agent), the operator promotes the other instance
   automatically, `pg-rw` follows, the rows are there (results below)

After deploying: `kubectl cnpg status pg` (2 instances, streaming), `verify.yml`, Grafana replication lag.

## Lab results

2026-11-22, throwaway 2-node k3s v1.37.2 in Docker, CNPG chart 0.29.1 (operator 1.30.1); the real manifests.

| Step | Result |
|---|---|
| 1 → 2 instances | pg-1 kept its `local-path` volume, untouched; pg-2 on the other node, PVC `local-ssd` at `/mnt/ssd/k8s/pvc-…`; 42 rows on pg-2; `pg_stat_replication`: streaming |
| switchover to pg-2 | done in seconds; a write on pg-2 replicated |
| failover, 1 operator copy (runs 1-4) | the primary's node stopped; its pod went `Ready: False` at ~45 s (as CNPG documents), but **promotion started only at ~370 s** |
| why | the operator's log began with "Operator configuration loaded" seconds before the failover: the operator had been running **on the stopped node**. Nobody was left to promote until Kubernetes evicted the operator pod (300 s default) and started it elsewhere. Shorter `tolerationSeconds` on the Postgres pods did not help (the operator pod has its own) |
| failover, 2 operator copies (run 5), worst case forced: the operator **leader** on the primary's node | promotion started at ~50 s, pg-1 primary by ≤ 75 s, `pg-rw` → pg-1, 43 of 43 rows |

Lesson: a controller that performs failover must not be a single copy. This is how real systems run
controllers (2+ copies, leader election, spread over failure domains). In production the primary
normally runs on node-workstation, which also runs the control plane (whose death is A3 anyway), so the
gap mattered mainly after a failover to node-8gb; it is closed either way.

## Rollback

Set `instances: 1` (CNPG removes pg-2; with `Retain` its files stay on the SSD until deleted by hand),
revert the affinity. The primary is never touched by a rollback.
