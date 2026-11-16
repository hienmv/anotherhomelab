# 9. Monitoring: metrics and logs, where they are stored

Short version: Grafana shows **metrics** (numbers, as graphs) and, since 2026-11-16, **logs**
(structured JSON lines with a level and a request id, searchable). Both are copied off every node within seconds and stored on node-workstation, so
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
| **all errors, every app** | `level:=ERROR` |
| errors and warnings of one app | `service.name:=ticketlite-api level:in(ERROR, WARN)` |
| **one request, through api and worker** | `trace_id:="6571ab6e74c37e8cb6408496f38739e2"` (or `request_id:="..."`) |
| slow requests | `logger:=ticketlite.http duration_ms:>1000` |
| sold-out answers | `url.path:="/api/orders" http.response.status_code:=409` |
| one order | `order.id:="28d923da-ef06-4569-81e8-4fce1a1b5cfd"` |
| requests per status code, last hour | `_time:1h http.response.status_code:* \| stats by (service.name, http.response.status_code) count() requests` |
| everything from TicketLite's api | `kubernetes.container_name:=api` |
| everything one machine logged | `kubernetes.pod_node_name:=node-8gb` |
| one word anywhere, last hour (time picker) | `timeout` |
| lines per pod, last 15 min | `_time:15m \| stats by (kubernetes.pod_name) count() lines` |
| Postgres warnings (not JSON: search words) | `kubernetes.pod_namespace:=data AND (WARNING OR ERROR)` |

- `field:=value` means exactly equal; a bare word searches the message text.
- Click a line to see all its fields. To show one pod's lines only, click `+` next to a field
  (a filter is added).
- The LogsQL docs are at https://docs.victoriametrics.com/victorialogs/logsql/

### Reading one log entry

A real entry (2026-11-16), clicked open in Explore, shortened:

| Field | Value | Meaning |
|---|---|---|
| `line` (the message, `_msg`) | `06:23:21.028 INFO  i.k.s.Application - 200 OK: GET - /api/concerts in 13ms` | what the program printed (plain text, before 2026-11-16: see below) |
| `timeLocal` / `timeUtc` | `15:23:21` / `06:23:21` | when (JST = UTC + 9; the app itself prints UTC) |
| `kubernetes.pod_namespace` | `apps` | added by vlagent: where it came from ... |
| `kubernetes.pod_name` | `ticketlite-api-c7cdf7c65-vrxh8` | ... which pod (Deployment name + ReplicaSet hash + random) |
| `kubernetes.container_name` | `api` | ... which container in the pod |
| `kubernetes.pod_node_name` | `node-workstation` | ... which machine |
| `kubernetes.pod_labels.*` | `app.kubernetes.io/name=ticketlite`, `component=api` | the pod's labels: filter a whole app, all copies at once |
| `output_stream` | `stdout` | normal output (`stderr` = the error channel) |
| `_stream` | `{container_name="api",pod_name=...,pod_namespace="apps"}` | VictoriaLogs groups lines into **streams** (one per container); filtering on these is fastest |
| `logLevel` | `unknown` | the level was only a word in the text: fixed by structured logs, below |

So `kubernetes.pod_node_name:=node-8gb` shows everything one machine logged, and
`kubernetes.pod_labels.app.kubernetes.io/name:=ticketlite` shows both api copies and the workers.

## Structured logs (since 2026-11-16)

The apps used to print plain sentences. Now every line is a **JSON object**, so each part is a field
you can filter, count and graph. A real TicketLite request, then the worker processing that order:

```json
{"time":"2026-11-16T07:21:33.478Z","level":"INFO","logger":"ticketlite.http","message":"POST /api/orders -> 202 in 84 ms",
 "service.name":"ticketlite-api","service.namespace":"ticketlite","http.request.method":"POST","url.path":"/api/orders",
 "http.response.status_code":202,"duration_ms":84,"request_id":"trace-demo-1",
 "trace_id":"6571ab6e74c37e8cb6408496f38739e2","span_id":"e001ee546d6f5a6d"}
{"time":"2026-11-16T07:21:33.867Z","level":"INFO","logger":"ticketlite.worker","message":"order 9a71... -> CONFIRMED",
 "service.name":"ticketlite-worker","order.id":"9a71...","order.status":"CONFIRMED","order.reason":"-","order.quantity":1,
 "concert.id":2,"duration_ms":352,"request_id":"trace-demo-1","trace_id":"6571ab6e74c37e8cb6408496f38739e2","span_id":"b2e661d14eb7e003"}
```

Field names follow the **OpenTelemetry semantic conventions** (`service.name`, `http.request.method`,
`url.path`, `http.response.status_code`, ...): the same names as in the traces ([chapter 10](10-tracing.md)),
and the names any OpenTelemetry-aware tool expects. Only TicketLite's own things are ours
(`order.*`, `concert.id`).

| Who | How | Fields |
|---|---|---|
| TicketLite api + worker | logback + `logstash-logback-encoder` (`backend/src/main/resources/logback*.xml`, `RequestLog.kt`) | `time level logger message service.name service.namespace trace_id span_id` + `http.request.method url.path http.response.status_code duration_ms request_id` per request, `order.id order.status order.reason order.quantity concert.id` per order, `stack_trace` on errors |
| landing, notes, ticketlite-web | nginx `log_format json` (their `nginx.conf*`) | the same request fields, plus `http.response.body.size client.address user_agent.original http.request.header.referer` (no traces: static files) |
| Postgres, Traefik, Flux, ... | not ours | their own format (search words) |

### Log levels: one rule for every app

| Level | Means | Examples here | Who reacts |
|---|---|---|---|
| **ERROR** | the service **failed**; someone should look | 5xx answer, unhandled exception, crash at start | you (later: an alert) |
| **WARN** | **degraded**, but it still works or recovers by itself | request slower than 1 s, worker retrying after a Redis error | look if it repeats |
| **INFO** | **normal** events | every request (also 4xx), order confirmed or failed, start/stop | nobody; for investigating |
| **DEBUG** | details for a bug hunt | off; `LOG_LEVEL=DEBUG` on the Deployment to switch on | – |

Why is a 4xx (`409 SOLD_OUT`, `401 UNAUTHORIZED`) only INFO? The service answered **correctly**; a
sold-out concert is business, not a fault. If 4xx were WARN, a ticket rush would bury the real
warnings. Levels describe **the health of the service**, not whether the visitor was happy.

### One request, followed through the system: `request_id`

Every request gets an id, chosen in this order: the caller's `X-Request-ID` header, else
Cloudflare's `CF-Ray` (also visible in Cloudflare's dashboard), else a new random one. The api:

- writes it in its request line,
- sends it back to the browser as the `X-Request-ID` response header,
- puts it **into the Redis queue** with the order, so the **worker** logs the same id.

So `request_id:="..."` shows the whole story of one purchase across two programs (and from the
browser: DevTools → Network → the request → Response headers → `X-Request-ID`). Real systems call
this a **correlation id**. Since tracing ([chapter 10](10-tracing.md)) every line also has a
`trace_id`: the internal id, which opens the trace with the timing of every step.

### Guarded by a test

`LoggingTest.kt` (runs in every image build) fails if a field name the queries above rely on changes
(`level`, `message`, `request_id`, `http.response.status_code`, `duration_ms`, ...) or the level rule changes, the same
way `MetricsTest` protects the dashboard.

## `kubectl logs`

Still works and is quicker for "what is this pod doing right now":

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
