# 9. Monitoring: what Grafana shows, and where it is stored

Short version: **Grafana shows numbers (metrics), not logs.** Logs live on each node and are read with
`kubectl logs`. This chapter shows where every piece is, and what happens when a node shuts down.

## Two kinds of information

| | **Metrics** | **Logs** |
|---|---|---|
| What | numbers measured every 30 s: CPU, memory, requests per second, error rate, tickets sold | lines of text a program prints: `GET /api/concerts 200`, `ERROR connection refused` |
| Answers | "**how much / how fast / is it OK?**" | "**what exactly happened?**" |
| Seen with | Grafana (graphs) | `kubectl logs` (text) |
| Kept | 30 days, in one place | on the node running the pod, a few MB per container |

## The picture

```text
 every node                         node-workstation                        any Pi (role=apps)
 ┌──────────────────────┐  scrape   ┌───────────────────────────────┐ query ┌──────────────────┐
 │ node-exporter (CPU..)│◄──every───┤ vmagent  ──► vmsingle         │◄──────┤ Grafana          │
 │ apps' /metrics       │   30 s    │              (metrics DB,     │       │ draws the graphs │
 │ Postgres, cloudflared│           │               SSD, 30 days)   │       │ stores nothing   │
 │                      │           └───────────────────────────────┘       └──────────────────┘
 │ /var/log/pods/...    │◄── kubectl logs (read straight from the node, nothing collects them)
 └──────────────────────┘
```

1. **vmagent** visits every target every 30 s and copies its current numbers.
2. **vmsingle** (VictoriaMetrics) stores them on node-workstation's SSD for **30 days**.
3. **Grafana** asks vmsingle "CPU of node-8gb over the last 6 h?" and draws the answer. It keeps no
   data itself (the dashboards come from git, see [chapter 8](8-surviving-a-node-failure.md)).

## Where logs are

Each container's output goes to a file **on the node where the pod runs**:
`/var/log/pods/<namespace>_<pod>_<id>/<container>/0.log`. Kubernetes keeps up to **5 files of 10 MB**
per container (older lines are thrown away), and `kubectl logs` reads them from that node.

```bash
kubectl -n apps logs deploy/ticketlite-api --tail=50       # last 50 lines
kubectl -n apps logs deploy/ticketlite-api --since=1h -f   # last hour, then follow live
kubectl -n apps logs <pod> --previous                      # the copy that crashed just before
```

## What happens when a node shuts down

| | Metrics | Logs |
|---|---|---|
| node-8gb / node-4gb / node-1gb shuts down | **kept**: already copied to vmsingle. The graph has a gap while it is off | **unreachable** while it is off. When its pods are replaced elsewhere and the node comes back, their old log files are **deleted** |
| a pod is deleted or replaced (deploy, crash, drain) | kept | its logs are **gone** (only `--previous` of the last restart survives, while the pod exists) |
| node-workstation's disk dies | **lost**: vmsingle is not in the backup (metrics are nice to have; Postgres and secrets are what the backup protects) | its pods' logs lost |

So: **metrics survive a node; logs do not.** For "why did it break at 03:00 last night?" you have
the graphs, but often not the log lines any more.

## What real systems add (roadmap)

**Central logs**: a small collector on every node sends each log line to one log database, and
Grafana searches it (same screen as the graphs). The fitting tool here is **VictoriaLogs** (same family
as vmsingle). Then logs survive pod replacement and node loss, are kept e.g. 14 days, and can be
searched across all pods at once. Listed in the [review](../review/2026-11-15-resilience-review.md)
under observability.

## Using Grafana

- Open: `https://grafana.<domain>` (Cloudflare login, then Grafana login) or `https://grafana.lan` on
  node-workstation. Admin password: Secret `monitoring/grafana-admin` (K3S-GUIDE, secrets).
- Useful dashboards: **Kubernetes / Views / Global** (whole cluster), **Node Exporter Full** (one
  machine: CPU, memory, disk, temperature), **Apps → TicketLite** (orders, latency, errors). Names can differ slightly by chart version: use the search box.
- Top right: time range ("Last 6 hours") and refresh. Click and drag on a graph to zoom in.
- To change a dashboard for good: edit in the UI, **Export → JSON**, save it under
  `k8s/monitoring/dashboards/`, add it to `k8s/monitoring/kustomization.yaml`, PR. A change made only
  in the UI is lost when Grafana restarts.

Back to: [README](README.md)
