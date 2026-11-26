# 8. Surviving a node failure: copies, spread, disruption budgets

Until 2026-11-16 every public site ran as **one copy** (one pod). If the Pi running it froze, lost
power or was rebooted for an upgrade, the site was down until Kubernetes noticed and started a new
copy elsewhere, which takes **about 5 minutes** (that is how long Kubernetes waits before it declares
a node dead). This chapter explains the three settings that fix it.

## 1. Two copies (`replicas: 2`)

```yaml
spec:
  replicas: 2
```

A Deployment now runs 2 identical pods. The Service in front of them sends each request to a copy
that is **ready** (its `readinessProbe` passes). When one copy dies, the Service stops sending traffic
to it within seconds; the other copy keeps answering. Visitors notice nothing.

This works only for **stateless** apps: the copies hold no data of their own (landing and notes are
static files; TicketLite keeps its data in Postgres and Redis, not in the web or api pods).

## 2. Spread over nodes (`topologySpreadConstraints`)

Two copies on the **same** Pi die together. So we ask the scheduler to put them on different nodes:

```yaml
topologySpreadConstraints:
  - maxSkew: 1                           # the node counts may differ by at most 1 (1 + 1, not 2 + 0)
    topologyKey: kubernetes.io/hostname  # "different" means different node (hostname)
    whenUnsatisfiable: ScheduleAnyway    # soft: if only one node is left, still run both there
    labelSelector:
      matchLabels: { app.kubernetes.io/name: landing }   # which pods to count
```

`ScheduleAnyway` (soft) instead of `DoNotSchedule` (hard): with a hard rule, a node outage would leave
the second copy **pending** (not running at all). The catch with soft: when the node comes back,
Kubernetes does **not** move a copy back by itself. `verify.yml` therefore checks it (problem
`spread: ... all copies on one node`) and tells you the fix:

```bash
kubectl -n apps rollout restart deploy/landing   # new pods, spread again
```

Since 2026-11-22 the **descheduler** does that restart by itself
([design](../design/2026-11-21-descheduler.md)): every 10 minutes it looks for copies that break their
spread rule, evicts **one** of them (through the disruption budget, so the site stays up), and the
scheduler starts the new copy on the emptier node. Tested in a throwaway 2-node cluster: 2 copies on
one node -> 1 eviction -> one copy per node; the next run evicts nothing. Its log:
`kubectl -n kube-system logs job/$(kubectl -n kube-system get jobs -o name | grep descheduler | tail -1 | cut -d/ -f2)`.

## 3. Disruption budget (`PodDisruptionBudget`)

Failures are one thing; **you** also take nodes away on purpose: `kubectl drain` before maintenance,
a k3s upgrade, a reboot. A PodDisruptionBudget (PDB) is a promise Kubernetes keeps during those
*voluntary* disruptions:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: landing, namespace: apps }
spec:
  minAvailable: 1                        # never fewer than 1 running copy
  selector:
    matchLabels: { app.kubernetes.io/name: landing }
```

With 2 copies and `minAvailable: 1`, a drain evicts one copy, waits until its replacement is ready
elsewhere, and only then may the other one go. A PDB does **not** help when a node simply dies (that
is not voluntary); the 2 copies + spread cover that.

## 4. What users see during the minute it takes (503, retries)

Even a good failover leaves a gap: the database is gone for ~30-75 s. Real services make that gap
**honest and short**, rather than pretending it cannot happen
([design](../design/2026-11-24-graceful-db-outage.md)):

| HTTP answer | Means | Client should |
|---|---|---|
| 4xx (`409 SOLD_OUT`) | your request is wrong or impossible | show it; don't retry |
| **503 + `Retry-After: 5`** | "I'm fine, a dependency is briefly down, come back in 5 s" | retry later |
| 500 | a bug | show an error; retrying rarely helps |

- **Fail fast**: the api waits at most 5 s for a database connection (the default was 30 s, so every
  click hung half a minute), then answers 503.
- **Retry only what is safe**: a GET (reading) can be repeated any number of times, because it is
  *idempotent*. A POST (buying) cannot: if the first attempt actually worked and only the answer
  was lost, a retry buys twice. So the web app retries reads by itself, with a "Reconnecting…"
  banner, and tells the user about a failed purchase instead. Real shops make purchases retryable
  with an **idempotency key** (roadmap U4).
- **Don't let the database decide readiness**: if the api's readiness probe checked Postgres, a DB
  blip would take *every* api pod out of service at once. Pages that don't need the DB would fail
  too. That is a *cascading failure*.

## What got it (2026-11-16)

| Part | Copies | Why it matters |
|---|---|---|
| Traefik (k3s' router, `k8s/infra/traefik-config.yaml`) | 2 | every public request goes through it |
| cloudflared (the tunnel, `k8s/infra/cloudflared.yaml`) | 2 | the only way in from the internet; both copies connect to the same tunnel |
| landing, notes, ticketlite-web | 2 | the public sites |
| ticketlite-api, ticketlite-worker | 2 (already) | got their PDBs |

## What is still single (and why)

| Part | Where | If it fails |
|---|---|---|
| Postgres | **2 instances since 2026-11-25**: primary + streaming replica on node-8gb's SSD ([design](../design/2026-11-22-postgres-replica.md)) | the primary's pod, disk or (Pi) node fails: CNPG promotes the replica within about a minute (lab: ≤ 75 s), `pg-rw` follows. The CNPG operator, which does the promoting, runs as 2 copies on different nodes: in the lab a single copy died together with the primary's node and the failover waited ~6 min. During the failover users see "Reconnecting…" (section 4). node-workstation dies completely: no automatic failover yet (its control plane runs the operator: roadmap A3); the replica holds the data |
| Redis | node-workstation | TicketLite orders pause until it is back (roadmap A2) |
| k3s server (control plane) | node-workstation | running pods keep running and serving, but nothing new can be scheduled or changed |
| metrics (vmsingle) | node-workstation | Grafana shows no data |
| Grafana | 1 copy, **stateless** since 2026-11-16 (no storage) | restarts on the other apps Pi within ~5 min; you log in again |

So the honest result: **losing node-8gb or node-4gb no longer takes any site down; losing
node-workstation still takes TicketLite down**, but its data is now live on a second machine.

## Try it: the failover drill

On node-workstation as `ops` (the sites should stay up the whole time):

```bash
kubectl get pods -A -o wide | grep -E 'landing|notes|ticketlite-web|cloudflared|traefik'   # copies on 2 nodes?
kubectl drain node-4gb --ignore-daemonsets --delete-emptydir-data   # the PDBs make it go one copy at a time
# meanwhile, from any browser or: curl -sI https://<your domain> | head -1   -> 200
kubectl uncordon node-4gb                                           # node usable again
```

Grafana (1 copy) moves to the other Pi during the drain: a short gap, then you log in again.

```bash
ansible-playbook playbooks/verify.yml                               # may report "spread": restart those deployments
```

Back to: [README](README.md)
