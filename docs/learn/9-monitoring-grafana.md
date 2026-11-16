# 9. Monitoring: metrics and logs, where they are stored

Short version: Grafana shows **metrics** (numbers, as graphs) and, since 2026-11-16, **logs** (text
lines, searchable). Both are copied off every node within seconds and stored on node-workstation, so
they survive a pod being replaced or a Pi going down.

## Two kinds of information

| | **Metrics** | **Logs** |
|---|---|---|
| What | numbers measured every 30 s: CPU, memory, requests per second, error rate, tickets sold | lines of text a program prints: `GET /api/concerts 200`, `ERROR connection refused` |
| Answers | "**how much / how fast / is it OK?**" | "**what exactly happened?**" |
| Collected by | **vmagent** (asks every target every 30 s) | **vlagent** (one per node, reads the log files as they are written) |
| Stored in | **vmsingle** (VictoriaMetrics), 30 days | **vlsingle** (VictoriaLogs), 14 days or 8 GiB |
| Seen with | Grafana dashboards | Grafana **Explore → VictoriaLogs (DS)**; also `kubectl logs` |

## The picture

```text
 every node (4)                     node-workstation (SSD)                  a Pi (role=apps)
 ┌──────────────────────┐  scrape   ┌───────────────────────────────┐ query ┌──────────────────┐
 │ node-exporter, apps' ├◄──every───┤ vmagent ──► vmsingle (metrics)│◄──────┤ Grafana          │
 │ /metrics, Postgres.. │   30 s    │                     30 days   │       │ graphs + log     │
 │                      │           │                               │       │ search           │
 │ /var/log/pods/...    │           │                               │       │ stores nothing   │
 │   └─ vlagent ────────┼──push────►│ vlsingle (logs)     14 days   │◄──────┤ (chapter 8)      │
 └──────────────────────┘ seconds   └───────────────────────────────┘       └──────────────────┘
```

1. Every container's output goes to a file on its node: `/var/log/pods/<namespace>_<pod>_<id>/...`.
2. **vlagent** (a DaemonSet: one copy on every node, node-1gb included) reads those files as they
   grow and sends each line to **vlsingle** within seconds. It adds where the line came from:
   `kubernetes.pod_namespace`, `kubernetes.pod_name`, `kubernetes.container_name`, the pod's labels.
3. **vmagent** asks every metrics target for its numbers every 30 s and writes them to **vmsingle**.
4. **Grafana** asks vmsingle and vlsingle and shows the answers. It keeps no data itself.

All of it is configured in `k8s/releases/vm-stack-values.yaml` (blocks `vmsingle`, `vmagent`,
`vlsingle`, `vlagent`, `grafana`).

## What happens when ...

| | Metrics | Logs |
|---|---|---|
| a pod is replaced (deploy, crash, drain) | kept | **kept** in vlsingle (the node's own files are deleted, but the lines were already sent) |
| node-8gb / node-4gb / node-1gb shuts down | kept; the graph has a gap while it is off | **kept** up to the moment it stopped (at most a few seconds lost) |
| VictoriaLogs is briefly down (restart) | – | vlagent keeps reading and sends later; the node's files still hold the last ~50 MB per container |
| node-workstation's disk dies | **lost** (not in the backup) | **lost** (not in the backup) |

The last row is the honest limit: metrics and logs have **one copy**, on node-workstation. That is
fine for "what happened last night?". Real systems keep copies on several machines or in S3; here
that comes with the off-site backups (roadmap step 1).

## Searching logs in Grafana

Grafana → **Explore** (compass icon) → data source **VictoriaLogs (DS)** → type a query in
**LogsQL**, pick a time range, run.

| You want | LogsQL |
|---|---|
| everything from TicketLite's api | `kubernetes.container_name:=api` |
| errors in the `apps` namespace | `kubernetes.pod_namespace:=apps AND error` |
| everything one machine logged | `kubernetes.pod_node_name:=node-8gb` |
| one word anywhere, last hour (time picker) | `timeout` |
| lines per pod, last 15 min | `_time:15m \| stats by (kubernetes.pod_name) count() lines` |
| Postgres warnings | `kubernetes.pod_namespace:=data AND (WARNING OR ERROR)` |

- `field:=value` means exactly equal; a bare word searches the message text.
- Click a line to see all its fields. To show one pod's lines only, click `+` next to a field
  (a filter is added).
- The LogsQL docs are at https://docs.victoriametrics.com/victorialogs/logsql/

### Reading one log entry

A real entry (2026-11-16), clicked open in Explore, shortened:

| Field | Value | Meaning |
|---|---|---|
| `line` (the message, `_msg`) | `06:23:21.028 INFO  i.k.s.Application - 200 OK: GET - /api/concerts in 13ms` | what the program printed |
| `timeLocal` / `timeUtc` | `15:23:21` / `06:23:21` | when (JST = UTC + 9; the app itself prints UTC) |
| `kubernetes.pod_namespace` | `apps` | added by vlagent: where it came from ... |
| `kubernetes.pod_name` | `ticketlite-api-c7cdf7c65-vrxh8` | ... which pod (Deployment name + ReplicaSet hash + random) |
| `kubernetes.container_name` | `api` | ... which container in the pod |
| `kubernetes.pod_node_name` | `node-workstation` | ... which machine |
| `kubernetes.pod_labels.*` | `app.kubernetes.io/name=ticketlite`, `component=api` | the pod's labels: filter a whole app, all copies at once |
| `output_stream` | `stdout` | normal output (`stderr` = the error channel) |
| `_stream` | `{container_name="api",pod_name=...,pod_namespace="apps"}` | VictoriaLogs groups lines into **streams** (one per container); filtering on these is fastest |
| `logLevel` | `unknown` | see below |

So `kubernetes.pod_node_name:=node-8gb` shows everything one machine logged, and
`kubernetes.pod_labels.app.kubernetes.io/name:=ticketlite` shows both api copies and the workers.

**Why `logLevel: unknown`?** The app prints plain text; the word `INFO` is just part of the
sentence, so Grafana cannot tell it is a level. Real systems log **structured JSON**
(`{"level":"info","msg":"200 OK: GET /api/concerts","duration_ms":13}`): then the level, the duration and
the path become fields you can filter, count and graph (`level:=error | stats count()`). Until then,
search the word: `kubernetes.pod_namespace:=apps AND ERROR`.

`kubectl logs` still works and is quicker for "what is this pod doing right now":

```bash
kubectl -n apps logs deploy/ticketlite-api --tail=50       # last 50 lines
kubectl -n apps logs deploy/ticketlite-api --since=1h -f   # last hour, then follow live
kubectl -n apps logs <pod> --previous                      # the copy that crashed just before
```

## Using Grafana

- Open: `https://grafana.<domain>` (Cloudflare login, then Grafana login) or `https://grafana.lan` on
  node-workstation. Admin password: Secret `monitoring/grafana-admin` (K3S-GUIDE, secrets).
- Useful dashboards: **Kubernetes / Views / Global** (whole cluster), **Node Exporter Full** (one
  machine: CPU, memory, disk, temperature), **Apps → TicketLite** (orders, latency, errors). Names
  can differ slightly by chart version: use the search box.
- Top right: time range ("Last 6 hours") and refresh. Click and drag on a graph to zoom in.
- To change a dashboard for good: edit in the UI, **Export → JSON**, save it under
  `k8s/monitoring/dashboards/`, add it to `k8s/monitoring/kustomization.yaml`, PR. A change made only
  in the UI is lost when Grafana restarts.
- The logs data source is a Grafana **plugin**, downloaded from grafana.com every time Grafana starts
  (pinned version in `vm-stack-values.yaml`). If grafana.com is unreachable at that moment, the logs
  data source is missing until the next restart; metrics still work.

## Checked by

`verify.yml` (every 15 min through the timers): a collector is ready on **every** node, and
VictoriaLogs received log lines in the last 15 minutes. Otherwise a `logs:` problem (and a Telegram
message).

Back to: [README](README.md)
