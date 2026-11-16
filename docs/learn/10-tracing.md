# 10. Tracing: following one request through the whole system

Metrics say **that** something is slow; logs say **what** happened. A **trace** says **where the
time went**: every step of one request, across programs, with its duration. Since 2026-11-16
TicketLite is traced with **OpenTelemetry**, the industry standard.

## A real trace (docker compose, 2026-11-16)

One ticket purchase. Each line is a **span** (one step); indentation = "called by"; `+` = when it
started, then how long it took:

```text
POST /api/orders                     ticketlite-api      +0.0    86.5 ms   <- the HTTP request
  SELECT app.concerts                ticketlite-api      +7.5     0.5 ms   <- Postgres
  EVAL                               ticketlite-api     +17.9     0.0 ms   <- Redis: reserve (Lua script)
  INSERT app.orders                  ticketlite-api     +26.4     1.0 ms
  orders publish                     ticketlite-api     +68.5     6.1 ms   <- put the order in the queue
    XADD                             ticketlite-api     +72.6     2.0 ms
    orders process                   ticketlite-worker +120.7   356.0 ms   <- ANOTHER program, same trace
      payment (simulated)            ticketlite-worker +122.6   207.0 ms   <- most of the time is here
      SELECT app.orders              ticketlite-worker +462.3     2.2 ms
      UPDATE app.concerts            ticketlite-worker +469.9     0.4 ms
      UPDATE app.orders              ticketlite-worker +470.4     0.3 ms
      XACK                           ticketlite-worker +476.5     0.3 ms   <- message done
```

Read it like this: the api answered in 87 ms; the worker picked the order up 50 ms later and spent
207 of its 356 ms in the (simulated) payment. Without the trace, you would only know "orders take
~0.5 s".

## Words

| Word | Meaning |
|---|---|
| **trace** | everything one request caused, in all programs. Has a `trace_id` (32 hex characters) |
| **span** | one step of it: name, start, duration, attributes (`url.path`, `db.statement`, ...), status. Has a `span_id` |
| **parent / child** | a span started inside another one. This makes the tree above |
| **span kind** | `SERVER` (handles a request), `CLIENT` (calls Postgres, Redis), `PRODUCER` / `CONSUMER` (puts in / takes from a queue), `INTERNAL` |
| **context propagation** | passing `trace_id` + parent span to the next program, so it continues the same trace. Over HTTP: the W3C header `traceparent: 00-<trace_id>-<span_id>-01` |
| **instrumentation** | the code that creates spans. Here mostly automatic (the agent) |
| **sampling** | deciding which traces to keep. **Tail** sampling decides after the trace is complete |

## The pipeline

```text
 ticketlite-api / -worker                 node-workstation (role=data)
 ┌──────────────────────────┐  OTLP     ┌──────────────────────┐  OTLP   ┌──────────────────┐   ┌─────────┐
 │ your code + Java agent   ├──────────►│ otel-collector       ├────────►│ VictoriaTraces   │◄──┤ Grafana │
 │ (spans for HTTP, SQL,    │  batches  │ drops noise (tail    │  retry  │ (vtsingle)       │   │ Explore │
 │  Redis, + our 3 spans)   │  every 5 s│ sampling), batches   │  queue  │ 7 days / 4 GiB   │   └─────────┘
 └──────────────────────────┘           └──────────────────────┘         └──────────────────┘
```

| Part | What | Where in the repo |
|---|---|---|
| **OpenTelemetry Java agent** 2.33.1 | attaches to the JVM (`-javaagent`), creates spans for every Ktor request, JDBC query and Jedis call **without code changes**, and puts `trace_id` / `span_id` into every log line | `apps/ticketlite/backend/Dockerfile` (downloaded, checksum-pinned) |
| **our code** | only what the agent cannot know: carry the trace **through the Redis queue** (`traceparent` in the message), the worker's `orders process` span, `payment (simulated)` | `Tracing.kt`, `RedisGate.enqueue`, `Worker.handle` |
| **settings** | `OTEL_SERVICE_NAME`, where to send (`OTEL_EXPORTER_OTLP_ENDPOINT`), metrics and logs **not** via OTLP (we already have them) | `k8s/apps/ticketlite/backend.yaml` (`OTEL_*` env) |
| **OpenTelemetry Collector** 0.164.0 | receives, filters, batches, forwards; buffers for 5 min if VictoriaTraces is down | `k8s/monitoring/otel-collector.yaml` + `otel-collector-config.yaml` |
| **VictoriaTraces** v0.12.1 | stores spans; speaks the Jaeger API that Grafana understands | `k8s/releases/vm-stack-values.yaml` (`vtsingle`) |

## Why a collector, and what it drops

Measured in docker compose before the collector: with **no visitors at all**, the apps produced
traces anyway: Kubernetes probes (`GET /healthz` every 5 s), metrics scrapes (`GET /metrics` every
30 s), the worker asking an empty queue for work (`XREADGROUP` every 5 s), the database pool
checking its connections. In the cluster that is ~100 useless traces per minute, burying the real
ones. The collector waits until a trace is complete (10 s), then:

| Policy | Drops | Rule |
|---|---|---|
| `drop-probes-and-scrapes` | health checks, metrics scrapes | any span with `url.path` = `/healthz` or `/metrics` |
| `drop-background-client-calls` | queue polling, connection checks | the trace's **root** span is a `CLIENT` call: real work always starts with an incoming request (`SERVER`) or a queue message (`CONSUMER`) |
| `keep-everything-else` | – | all other traces are kept (100 %: the traffic is tiny) |

Large systems keep e.g. 10 % of normal traces and 100 % of errors and slow ones; the same file would
get a `probabilistic` and a `status_code` policy. Lesson learned while building it: a `drop` policy
drops only when **all** its sub-policies match, so each kind of noise is its own policy.

## Logs and traces, linked

Every log line written inside a span now carries `trace_id` and `span_id` (the agent adds them):

```json
{"level":"INFO","message":"POST /api/orders -> 202 in 84 ms","service.name":"ticketlite-api",
 "trace_id":"6571ab6e74c37e8cb6408496f38739e2","span_id":"e001ee546d6f5a6d","request_id":"trace-demo-1",...}
{"level":"INFO","message":"order 9a71... -> CONFIRMED","service.name":"ticketlite-worker",
 "trace_id":"6571ab6e74c37e8cb6408496f38739e2","span_id":"b2e661d14eb7e003",...}
```

Same `trace_id` in two programs. In Grafana:

- **trace → logs**: open a trace, click a span → **Logs for this span** (VictoriaLogs, `trace_id:="..."`).
- **log → trace**: open a log line with a `trace_id` → the link next to it opens the trace.

`request_id` stays: it is the id the **outside** world sees (Cloudflare's `CF-Ray`, the
`X-Request-ID` response header). `trace_id` is the internal one. A visitor's complaint with a
`CF-Ray` → `request_id:="..."` in the logs → the `trace_id` on that line → the trace.

## Using it

Grafana → **Explore** → data source **VictoriaTraces** →

- **Search**: Service `ticketlite-api`, Operation `POST /api/orders`, maybe `Min duration` 500ms → Run.
- **TraceID**: paste a `trace_id` from a log line.
- Click a span: its attributes (`db.statement` shows the SQL, `http.response.status_code`, ...).
- Errors: spans with status error are red (a 5xx answer, a failed SQL or Redis call, an exception in
  the worker).

Or try a purchase on TicketLite and look for your own trace: DevTools → Network → the `orders`
request → response header `X-Request-ID` → Grafana logs `request_id:="..."` → its `trace_id`.

## Names: OpenTelemetry semantic conventions

Logs and traces use the **same field names**, the standard ones (logs since 2026-11-16 too):
`service.name`, `http.request.method`, `url.path`, `http.response.status_code`, `client.address`,
`user_agent.original`, `db.statement`, `messaging.destination.name`. Our own: `order.id`,
`order.status`, `concert.id`. One query vocabulary for everything, and any tool that knows
OpenTelemetry understands the data.

## Limits (honest)

| Limit | Why / next step |
|---|---|
| traces start at the api, not in the browser or at Cloudflare | browser tracing (OpenTelemetry JS) is its own project |
| landing / notes / ticketlite-web (nginx) are not traced | static files: logs are enough |
| one collector, one VictoriaTraces copy (node-workstation) | like metrics and logs: off-site backups (roadmap step 1) |
| the agent costs ~60-100 MB per pod and a few seconds of startup | memory requests were raised by 64 Mi |
| metrics are still Prometheus-style (`/metrics`), not OTLP | both are standard; VictoriaMetrics reads either. Later: **exemplars** (a link from a latency graph to an example trace) |

## Checked by

`verify.yml` (every 15 min): calls `GET /api/concerts` once and waits until **its** trace shows up in
VictoriaTraces (~20 s). Otherwise a `traces:` problem (and a Telegram message). The check makes its
own request because idle traffic is filtered out: "no traces in the last hour" would prove nothing.

`LoggingTest` guards the log field names; docker compose (`apps/ticketlite/docker-compose.yml`) runs
the same collector config with a local VictoriaTraces (UI: http://localhost:10428/select/vmui/).

Back to: [README](README.md)
