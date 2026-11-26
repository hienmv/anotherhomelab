# TicketLite

A small distributed ticket-selling system (a "light Ticketmaster") running on the homelab k3s cluster.
Public URL: **`https://ticketlite.<domain>`** (only this host; the apex is the separate landing page; live: README URL table)

- Browse concerts, with a **Hot tickets** section (high demand, small inventory, max 2 per person)
- The Buy button is disabled with the reason shown when you can't buy: sold out, or you already hold
  the per-person maximum ("You already have 2 tickets: the maximum for this concert"). The quantity
  picker never offers more than you're still allowed.
- Log in / log out with sample accounts (no registration): `alice/alice123`, `bob/bob123`, `carol/carol123`
- Buy tickets; see "My orders"

## Architecture

```text
 Browser ──HTTPS──> Cloudflare ──tunnel──> Traefik ──┬── /      ──> web (nginx + React)
                                                     └── /api   ──> api (Ktor) x2
                                                                     │  1. Redis Lua gate: reserve or 409 SOLD_OUT
                                                                     │  2. insert order PENDING (Postgres)
                                                                     │  3. XADD orders stream ──────┐
                                                                     ▼                               ▼
                                                             PostgreSQL (CNPG)  <── confirm ── worker (Ktor) x2
                                                             source of truth        in tx      consumer group "workers"
 Browser polls GET /api/orders/{id} until CONFIRMED / FAILED
```

### In the cluster: pods, nodes and data paths

The diagram above is logical. This one shows where the pods run and which way the data flows (since
A1 + D1; [replica design](../../docs/design/2026-11-22-postgres-replica.md),
[read routing](../../docs/design/2026-11-25-read-routing.md),
[sync flows](../../docs/learn/15-data-intensive.md#how-pg-1-and-pg-2-stay-in-sync-the-flows)):

```text
 Browser ─HTTPS─► Cloudflare ─tunnel─► cloudflared x2 ─► Traefik x2 ─┬─ /    ─► web x2 (nginx, static)
                                                                      └─ /api ─► api x2
 app pods (web, api, worker: 2 copies each) are spread over node-workstation / node-8gb / node-4gb

 ┌─ node-workstation (NVMe) ─────────────────────┐        ┌─ node-8gb (USB SSD /mnt/ssd) ──────────────────┐
 │                                               │        │                                                │
 │  pod pg-1  PRIMARY ◄─────────── pg-rw ◄───────┼── W ───┤  api: writes, sale checks (stock, limits)      │
 │   │ WAL                                       │        │  worker: confirm orders                        │
 │   ├─ walsender ════ streaming replication ════╪═══════►│  pod pg-2  REPLICA (hot standby)               │
 │   │   (async, usually < 1 s; slot _cnpg_pg_2, │        │   walreceiver → replay → readable              │
 │   │    keeps ≤ 3 GB WAL for pg-2)             │        │      ▲                                         │
 │   │                                           │        │  pg-ro ◄──── R ── api: reads (concert list,    │
 │   └─ WAL + base backups ──────────────────────┼───────►│      login, my orders: if caught up with the   │
 │                                               │        │      user's LSN token, else via pg-rw)         │
 │  pod redis-0 (gate + orders stream)           │        │  Garage (backup store, host service)           │
 │  CNPG operator (1 of 2, leader election)      │        │      └── encrypted copy nightly → R2           │
 │                                               │        │  CNPG operator (other copy)                    │
 └───────────────────────────────────────────────┘        └────────────────────────────────────────────────┘
 W = write path (pg-rw, always the primary)   R = read path (pg-ro, the replica when correct)
 After a failover the roles swap: pg-2 is the primary, pg-rw follows; pg-1 rejoins as the replica.
```

The same, as a flow (GitHub draws it):

```mermaid
flowchart LR
    B[Browser] --> CF[Cloudflare tunnel] --> T[Traefik x2]
    T -->|/| WEB[web x2]
    T -->|/api| API[api x2]
    API -->|1 reserve: Lua gate| R[(redis-0)]
    API -->|2 insert PENDING<br/>sale checks| RW{{pg-rw}}
    API -->|3 XADD orders| R
    R -->|consume| WK[worker x2]
    WK -->|4 confirm in tx<br/>FOR UPDATE| RW
    API -.->|reads: list, login,<br/>my orders| RO{{pg-ro}}
    API -.->|"read not caught up / lag > 10 s / replica down"| RW
    RW --> P[(pg-1 PRIMARY<br/>node-workstation)]
    RO --> S[(pg-2 REPLICA<br/>node-8gb SSD)]
    P ==>|WAL streaming, async| S
    P -->|WAL archive + base backups| G[(Garage on node-8gb)]
    G -->|nightly, encrypted| R2[(Cloudflare R2)]
    OP[CNPG operator x2] -.->|health, failover,<br/>points pg-rw / pg-ro| P
    OP -.-> S
```

### One purchase, end to end (which database each step uses)

```mermaid
sequenceDiagram
    autonumber
    participant U as Browser
    participant A as api
    participant RD as redis-0
    participant P as pg-1 (pg-rw)
    participant W as worker
    participant S as pg-2 (pg-ro)
    U->>A: POST /api/orders {concert, qty}
    A->>P: concert, stock, user's holds (linearizable: primary)
    A->>RD: Lua gate: reserve stock + per-person limit (atomic)
    A->>P: INSERT order PENDING, COMMIT
    A->>P: pg_current_wal_lsn() → token in the session cookie
    A->>RD: XADD orders
    A-->>U: 202 PENDING (+ cookie with the token)
    P-)S: WAL streamed + replayed (async, usually < 1 s)
    RD->>W: next message (consumer group)
    W->>P: lock order + concert, re-check, CONFIRMED, COMMIT
    P-)S: WAL streamed + replayed
    loop poll every 0.5 s
        U->>A: GET /api/orders/{id} (cookie with token)
        A->>S: replayed past the token? (pg_last_wal_replay_lsn)
        alt yes
            S-->>A: the order (PENDING, later CONFIRMED)
        else no (just written, or replica lagging/down)
            A->>P: read from the primary instead
        end
        A-->>U: status
    end
```

During a failover:
- step 1 (`POST`) answers 503 "not placed" ([U1](../../docs/design/2026-11-24-graceful-db-outage.md))
- the poll keeps working from pg-2 until it is promoted, then from the new primary

| Component | Tech | Role |
|---|---|---|
| `web` | React 18 + Vite + TypeScript, nginx | UI, Atomic Design |
| `api` | Kotlin, Ktor 3 (CIO), JDBC + HikariCP, Flyway | auth, concerts, accept orders (202) |
| `worker` | same image, `ROLE=worker` | consume Redis stream, confirm orders in Postgres |
| Postgres | CloudNativePG cluster `pg`: pg-1 primary + pg-2 streaming replica | users, concerts, orders: source of truth (writes `pg-rw`, reads `pg-ro` when correct) |
| Redis | existing `redis` | inventory gate (`inventory:<id>`) + stream `orders` |

### How hot-ticket rushes are handled

1. **Fast reject in Redis.** `inventory:<concertId>` counts tickets that can still be reserved and
   `hold:<userId>:<concertId>` counts what each user holds or waits for. One Lua script checks the
   per-person limit and the stock and reserves both atomically: `409 LIMIT` / `409 SOLD_OUT` without
   touching Postgres. (Until 2026-11-09 the limit was read from Postgres first; 60 parallel requests
   from 3 users got 18 accepted for a limit of 6. Now exactly 6.)
2. **Queue.** Accepted requests become a `PENDING` order plus a message on the Redis stream `orders`.
   The API answers `202` immediately, so a spike doesn't hold HTTP connections open.
3. **Authoritative confirm.** Workers (consumer group, each message to one worker) lock the order and
   the concert row (`SELECT ... FOR UPDATE`), re-check stock and the per-person limit, then commit.
   On failure the Redis gate gets the tickets and the user's hold back; on `SOLD_OUT` it is also reset
   to Postgres' number, so a stale gate corrects itself after one failed order.
4. **Crash safety.** A message read but never acknowledged (worker died) is reclaimed by another
   worker after 60s (`XAUTOCLAIM`). Orders that are no longer `PENDING` are skipped, so reprocessing is safe.

Result: Postgres can never oversell, even if Redis is wrong (e.g. after a Redis restart the gate is
reloaded from Postgres with `SET NX`).

Tested locally (2026-11-08): 30 simultaneous requests from 3 users on a hot concert with 5 tickets left →
3 accepted, 27 rejected instantly, exactly 5 sold (2/2/1, per-person limit held), gate = DB = 0.

Tested on the cluster via https://tickets.hien0ethan.com, its former host (2026-11-09): same 30-request burst over the
internet → 3 accepted, 27 rejected, exactly 5 sold (2/1/2), gate = DB = 0, nothing stuck PENDING;
both workers (one per Pi 4) processed orders. Login cookie arrives with `Secure; HttpOnly; SameSite=Lax`.
Idle footprint: api ~100 MiB each, worker ~80 MiB each, web 4 MiB.

### Frontend (Atomic Design)

```text
web/src/components/
  atoms/       Button, Badge, Input, Price, Spinner
  molecules/   FormField, QuantityPicker, ConcertMeta, StatusMessage
  organisms/   Header, ConcertCard, ConcertGrid, LoginForm, OrderTable
  templates/   PageLayout
web/src/pages/ ConcertsPage, LoginPage, OrdersPage
```

## API

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/auth/login` | - | `{username, password}` → sets `TL_SESSION` cookie (HttpOnly, signed, Secure in k8s) |
| POST | `/api/auth/logout` | - | clears the cookie |
| GET | `/api/auth/me` | yes | current user or 401 |
| GET | `/api/concerts` | - | list; `available` = what can still be reserved; `userHeld` = your tickets (pending + confirmed) when logged in |
| POST | `/api/orders` | yes | `{concertId, quantity}` → `202 PENDING`, or 409 `SOLD_OUT` / `LIMIT` |
| GET | `/api/orders` | yes | my last 50 orders |
| GET | `/api/orders/{id}` | yes | poll status: `PENDING` → `CONFIRMED` / `FAILED` |

Errors: 4xx = your request (`SOLD_OUT`, `LIMIT`, `UNAUTHORIZED`, …). **503 `UNAVAILABLE` + `Retry-After`** =
Postgres or Redis is briefly down (failover, restart): try again later; the web app retries reads by
itself and never repeats an order ([design](../../docs/design/2026-11-24-graceful-db-outage.md)).
500 `INTERNAL` = a bug.

## Run locally

```bash
cd apps/ticketlite
docker compose up --build        # http://localhost:8080 (api also on :8081); SITE_TIMEZONE=... to override UTC
docker compose -f docker-compose.yml -f docker-compose.replica.yml up --build   # with a streaming replica, like the cluster
docker compose down -v
```
Needs Docker with buildx (`brew install docker-buildx`, plus `cliPluginsExtraDirs` in `~/.docker/config.json`).

### Backend in an IDE (IntelliJ) or without Docker

```bash
cd apps/ticketlite/backend
./gradlew test                   # the wrapper downloads Gradle 8.14.6 (checksum-verified)
```
Gradle 8.14 must run on **JDK 21** (as CI), not 25+ ("JDK 25 isn't compatible with Gradle ..."):
- `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=21`): Gradle runs its daemon on an installed JDK 21 whatever `JAVA_HOME` says. IntelliJ uses it as the "Gradle JVM".
- `.java-version` (`21`): jenv switches to 21 in this directory.
- Needs a JDK 21 installed (`brew install openjdk@21`). If IntelliJ still complains: Settings → Build Tools → Gradle → Gradle JVM: 21.

Versions in step: the wrapper, `gradle-daemon-jvm.properties` and the Dockerfile image (`gradle:8.14.6-jdk21`) change together.

## Configuration (environment)

| Variable | Used by | Default |
|---|---|---|
| `ROLE` | backend | `api` (`api` or `worker`) |
| `DATABASE_URL` | backend | `postgresql://app:app@localhost:5432/app` |
| `REDIS_URL` | backend | `redis://localhost:6379/0` |
| `SESSION_SECRET` | api | random (hex; sessions reset on restart if unset) |
| `COOKIE_SECURE` | api | `false` (`true` in k8s) |
| `WORKER_DELAY_MS` | worker | `200` (simulated payment step) |
| `DB_CONNECT_TIMEOUT_MS` | backend | `5000` (wait for a database connection; then 503) |
| `DATABASE_READ_HOST` | api | unset = every query on the primary; `pg-ro.data.svc.cluster.local` in k8s: reads on the replica when correct ([design](../../docs/design/2026-11-25-read-routing.md)) |
| `DB_READ_MAX_LAG_SECONDS` | api | `10` (a replica further behind serves no reads) |
| `API_UPSTREAM` | web | `http://api:8080` (nginx `/api` proxy, used by compose) |

## CI/CD

`.github/workflows/ticketlite.yml`:
1. On PR: build both images. The backend build runs `gradle test`; the web build runs `vitest` (38 tests), `tsc` and `vite build`. A failing test fails the image build.
2. On push to `main`: build `linux/amd64` + `linux/arm64` images and push to `ghcr.io/<repo owner>/ticketlite-{backend,web}`
   tagged `sha-<7>` and `main`.
3. Commit the new tag into `k8s/apps/ticketlite/kustomization.yaml` (`[skip ci]`).
4. Flux applies that commit within ~1 minute.

**Dependency caching (fixed 2026-11-09).** The backend Dockerfile downloads every jar, including the
57 MB Kotlin compiler, in its own layer (`gradle resolveDependencies`), cached by CI until
`build.gradle.kts`/`settings.gradle.kts` change. The compile step then runs `--offline`, so code-only
changes download nothing. Before this, `gradle dependencies` fetched only `.pom` metadata and every run
re-downloaded everything; two runs failed on a GitHub `504` because repo.maven.apache.org redirected
the compiler jar to github.com. Google's Maven Central mirror is now tried first.
If CI still fails on a download error, use "Re-run failed jobs".

Build stages run natively on the amd64 runner (JVM bytecode and static JS are architecture-independent);
only the final arm64 runtime image is assembled for the Pis, so no slow emulation.

## Kubernetes

Manifests: `k8s/apps/ticketlite/` (namespace `apps`). Secrets (not in git):
- `app-env`: `DATABASE_URL`, `REDIS_URL`, `REDIS_PASSWORD` (shared; [K3S-GUIDE.md section 5](../../K3S-GUIDE.md#5-what-runs-where-pointers))
- `ticketlite`: `SESSION_SECRET` (`kubectl -n apps create secret generic ticketlite --from-literal=SESSION_SECRET="$(openssl rand -hex 32)"`)
- `ghcr-pull`: shared image pull secret for all apps, see [K3S-GUIDE.md → Secrets](../../K3S-GUIDE.md#secrets-kept-out-of-git-create-once-per-cluster)

## Observability

`GET /metrics` on api and worker pods (Prometheus format, in-cluster only: the Ingress routes just
`/api` and `/`). Scraped by VictoriaMetrics (`k8s/monitoring/scrapes.yaml`), shown in the Grafana
dashboard **TicketLite** (`k8s/monitoring/dashboards/ticketlite.json`, loaded automatically).

| Metric | From | Meaning |
|---|---|---|
| `ticketlite_order_requests_total{result}` | api | accepted / sold_out / limit / invalid |
| `ticketlite_orders_processed_total{status,reason}` | worker | CONFIRMED / FAILED after the DB check |
| `ticketlite_tickets_sold_total` | worker | confirmed tickets |
| `ticketlite_order_process_seconds` | worker | histogram, time per order |
| `ticketlite_dependency_unavailable_total{dependency}` | api | requests answered 503 because postgres / redis was briefly down |
| `ticketlite_db_reads_total{target,reason}` | api | where each database read went (replica / primary) and why: ok, write_path, not_caught_up, lagging, replica_down, no_replica |
| `ticketlite_inventory_available{concert,hot}` | api | Redis gate per concert |
| `ticketlite_queue_lag`, `ticketlite_queue_pending` | worker | stream backlog / unacked |
| `ktor_http_server_requests_seconds` | api | HTTP latency histogram by route |

`MetricsTest` fails the build if a metric the dashboard uses is renamed. Counters are pre-registered at 0:
otherwise the first burst after a pod start is invisible to `increase()` (dashboard showed 8 of 18).

## Logs

JSON, one object per line (`src/main/resources/logback*.xml`, request lines from `RequestLog.kt`),
OpenTelemetry field names: `time level logger message service.name service.namespace trace_id span_id`,
per request `http.request.method url.path http.response.status_code duration_ms request_id`, per order
`order.id order.status order.reason order.quantity concert.id duration_ms request_id`. The api takes the
request id from `X-Request-ID` / `CF-Ray` (or makes one), returns it as `X-Request-ID` and passes it to
the worker through the Redis stream. Levels: 5xx/crash = ERROR, slow (>1 s) or retry = WARN, rest INFO.
`LOG_FORMAT=text` for readable local output, `LOG_LEVEL=DEBUG` for more. `LoggingTest` guards the field
names. Searching them in Grafana: [docs/learn/9](../../docs/learn/9-monitoring-grafana.md).

## Traces

The OpenTelemetry Java agent (pinned in the `Dockerfile`) traces every HTTP request, SQL query and
Redis call; `Tracing.kt` carries the trace through the Redis stream (`traceparent` field), so api and
worker form ONE trace (`orders publish` → `orders process` → `payment (simulated)` → SQL). Settings:
`OTEL_*` env (cluster: `k8s/apps/ticketlite/backend.yaml`); `OTEL_JAVAAGENT_ENABLED=false` switches
it off. `docker compose up` includes the collector and VictoriaTraces (http://localhost:10428/select/vmui/).
Details: [docs/learn/10](../../docs/learn/10-tracing.md).

## Tests

- Backend (JUnit, `gradle test`): config parsing, limits, metric names.
- Frontend (Vitest + React Testing Library, `npm test`, 31 tests): one or more per Atomic Design level
  (atom Price, molecule QuantityPicker, organisms ConcertCard + LoginForm), `buyState` (why Buy is
  disabled: limit reached / sold out / remaining allowance), API client errors, and the ConcertsPage
  flow (hot/normal split, login redirect, confirmed/failed orders).
- Not automated: end-to-end against real Postgres/Redis (done by hand with docker compose and the
  rush test above).

## Limits / next steps

- No payment, seat maps, or registration (by design).
- Scale workers with KEDA on stream lag.
- Rate limiting per user/IP for real rushes.

