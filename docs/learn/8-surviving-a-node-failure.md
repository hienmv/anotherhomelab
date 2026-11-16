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
| Postgres, Redis | node-workstation | TicketLite down; landing and notes stay up. Fix later: Postgres replicas on another node (CNPG), roadmap |
| k3s server (control plane) | node-workstation | running pods keep running and serving, but nothing new can be scheduled or changed |
| metrics (vmsingle) | node-workstation | Grafana shows no data |
| Grafana | one Pi (its storage folder is on that Pi) | Grafana down until that Pi is back; see the Grafana PR |

So the honest result: **losing node-8gb or node-4gb no longer takes any site down; losing
node-workstation still takes TicketLite down.**

## Try it: the failover drill

On node-workstation as `ops` (the sites should stay up the whole time):

```bash
kubectl get pods -A -o wide | grep -E 'landing|notes|ticketlite-web|cloudflared|traefik'   # copies on 2 nodes?
kubectl drain node-4gb --ignore-daemonsets --delete-emptydir-data   # the PDBs make it go one copy at a time
# meanwhile, from any browser or: curl -sI https://<your domain> | head -1   -> 200
kubectl uncordon node-4gb                                           # node usable again
```

If Grafana lives on node-4gb, it stays `Pending` during the drain (its storage cannot move) and
starts again after the `uncordon`. That is the Grafana problem from the table above.

```bash
ansible-playbook playbooks/verify.yml                               # may report "spread": restart those deployments
```

Back to: [README](README.md)
