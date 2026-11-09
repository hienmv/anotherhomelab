# TicketLite

A small distributed ticket-selling system (a "light Ticketmaster") running on the homelab k3s cluster.
Public URL: **https://hien0ethan.com** (also `tickets.hien0ethan.com`; any unknown subdomain redirects to the main app)

- Browse concerts, with a **Hot tickets** section (high demand, small inventory, max 2 per person)
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

| Component | Tech | Role |
|---|---|---|
| `web` | React 18 + Vite + TypeScript, nginx | UI, Atomic Design |
| `api` | Kotlin, Ktor 3 (CIO), JDBC + HikariCP, Flyway | auth, concerts, accept orders (202) |
| `worker` | same image, `ROLE=worker` | consume Redis stream, confirm orders in Postgres |
| Postgres | CloudNativePG cluster `pg` (existing) | users, concerts, orders: source of truth |
| Redis | existing `redis` | inventory gate (`inventory:<id>`) + stream `orders` |

### How hot-ticket rushes are handled

1. **Fast reject in Redis.** `inventory:<concertId>` counts tickets that can still be reserved. A Lua
   script checks and decrements it atomically. When it hits 0, buyers get `409 SOLD_OUT` without
   touching Postgres.
2. **Queue.** Accepted requests become a `PENDING` order plus a message on the Redis stream `orders`.
   The API answers `202` immediately, so a spike doesn't hold HTTP connections open.
3. **Authoritative confirm.** Workers (consumer group, each message to one worker) lock the order and
   the concert row (`SELECT ... FOR UPDATE`), re-check stock and the per-person limit, then commit.
   On failure the Redis gate gets the tickets back.
4. **Crash safety.** A message read but never acknowledged (worker died) is reclaimed by another
   worker after 60s (`XAUTOCLAIM`). Orders that are no longer `PENDING` are skipped, so reprocessing is safe.

Result: Postgres can never oversell, even if Redis is wrong (e.g. after a Redis restart the gate is
reloaded from Postgres with `SET NX`).

Tested locally (2026-11-08): 30 simultaneous requests from 3 users on a hot concert with 5 tickets left →
3 accepted, 27 rejected instantly, exactly 5 sold (2/2/1, per-person limit held), gate = DB = 0.

Tested on the cluster via https://tickets.hien0ethan.com (2026-11-09): same 30-request burst over the
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
| GET | `/api/concerts` | - | list; `available` = what can still be reserved |
| POST | `/api/orders` | yes | `{concertId, quantity}` → `202 PENDING`, or 409 `SOLD_OUT` / `LIMIT` |
| GET | `/api/orders` | yes | my last 50 orders |
| GET | `/api/orders/{id}` | yes | poll status: `PENDING` → `CONFIRMED` / `FAILED` |

## Run locally

```bash
cd apps/ticketlite
docker compose up --build        # http://localhost:8080 (api also on :8081)
docker compose down -v
```
Needs Docker with buildx (`brew install docker-buildx`, plus `cliPluginsExtraDirs` in `~/.docker/config.json`).

## Configuration (environment)

| Variable | Used by | Default |
|---|---|---|
| `ROLE` | backend | `api` (`api` or `worker`) |
| `DATABASE_URL` | backend | `postgresql://app:app@localhost:5432/app` |
| `REDIS_URL` | backend | `redis://localhost:6379/0` |
| `SESSION_SECRET` | api | random (hex; sessions reset on restart if unset) |
| `COOKIE_SECURE` | api | `false` (`true` in k8s) |
| `WORKER_DELAY_MS` | worker | `200` (simulated payment step) |
| `API_UPSTREAM` | web | `http://api:8080` (nginx `/api` proxy, used by compose) |

## CI/CD

`.github/workflows/ticketlite.yml`:
1. On PR: build both images. The backend build runs `gradle test`; the web build runs `vitest` (22 tests), `tsc` and `vite build`. A failing test fails the image build.
2. On push to `main`: build `linux/arm64` images and push to `ghcr.io/hienmv/ticketlite-{backend,web}`
   tagged `sha-<7>` and `main`.
3. Commit the new tag into `k8s/apps/ticketlite/kustomization.yaml` (`[skip ci]`).
4. Flux applies that commit within ~1 minute.

**If CI fails:** check whether it's a download error (e.g. `504 Gateway Time-out` from
github.com) before suspecting code. GitHub release downloads were flaky on 2026-11-08/09 (Flux CLI,
Kotlin compiler). Gradle now retries downloads 5x (`backend/gradle.properties`); otherwise use
"Re-run failed jobs" in the Actions tab.

Build stages run natively on the amd64 runner (JVM bytecode and static JS are architecture-independent);
only the final arm64 runtime image is assembled for the Pis, so no slow emulation.

## Kubernetes

Manifests: `k8s/apps/ticketlite/` (namespace `apps`). Secrets (not in git):
- `app-env`: `DATABASE_URL`, `REDIS_URL` (existing, section 7.2 of the guide)
- `ticketlite`: `SESSION_SECRET` (`kubectl -n apps create secret generic ticketlite --from-literal=SESSION_SECRET="$(openssl rand -hex 32)"`)
- `ghcr-pull`: GHCR packages are private, so the cluster needs a pull token (classic PAT, only `read:packages`).
  Run in your own terminal so the token stays out of logs/chat:
  ```bash
  read -s GHCR_TOKEN && kubectl -n apps create secret docker-registry ghcr-pull \
    --docker-server=ghcr.io --docker-username=hienmv --docker-password="$GHCR_TOKEN"; unset GHCR_TOKEN
  ```
  The token expires: when pulls start failing with 401/403, create a new one and recreate the secret.
  **Must be a classic token.** GHCR does not accept fine-grained tokens: they log in but get
  `403 Forbidden` on the image (seen 2026-11-09). Classic token form, pre-filled:
  https://github.com/settings/tokens/new?scopes=read:packages&description=homelab-ghcr-pull

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
| `ticketlite_inventory_available{concert,hot}` | api | Redis gate per concert |
| `ticketlite_queue_lag`, `ticketlite_queue_pending` | worker | stream backlog / unacked |
| `ktor_http_server_requests_seconds` | api | HTTP latency histogram by route |

`MetricsTest` fails the build if a metric the dashboard uses is renamed. Counters are pre-registered at 0:
otherwise the first burst after a pod start is invisible to `increase()` (dashboard showed 8 of 18).

## Tests

- Backend (JUnit, `gradle test`): config parsing, limits, metric names.
- Frontend (Vitest + React Testing Library, `npm test`): one or more per Atomic Design level
  (atom Price, molecule QuantityPicker, organisms ConcertCard + LoginForm), API client errors,
  and the ConcertsPage flow (hot/normal split, login redirect, confirmed/failed orders).
- Not automated: end-to-end against real Postgres/Redis (done by hand with docker compose and the
  rush test above).

## Limits / next steps

- No payment, seat maps, or registration (by design).
- Scale workers with KEDA on stream lag.
- Rate limiting per user/IP for real rushes.
- **Known gap: per-person limit under parallel requests (seen 2026-11-09).** 60 simultaneous buys by
  3 users on a hot concert: 18 were *accepted* (202) though only 6 were allowed. The API's early check
  reads Postgres, so same-instant requests all pass it. The worker still enforced the rule (6 CONFIRMED,
  12 FAILED with `LIMIT`), so nothing is oversold, but users see "accepted" then "failed".
  Fix: an atomic per-user counter in Redis (`hold:<user>:<concert>`) checked in the same Lua script
  as the inventory gate.
