# TicketLite

A small distributed ticket-selling system (a "light Ticketmaster") running on the homelab k3s cluster.
Public URL: **https://tickets.hien0ethan.com**

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
1. On PR: build both images (backend image build runs `gradle test`, web runs `tsc` + `vite build`).
2. On push to `main`: build `linux/arm64` images and push to `ghcr.io/hienmv/ticketlite-{backend,web}`
   tagged `sha-<7>` and `main`.
3. Commit the new tag into `k8s/apps/ticketlite/kustomization.yaml` (`[skip ci]`).
4. Flux applies that commit within ~1 minute.

Build stages run natively on the amd64 runner (JVM bytecode and static JS are architecture-independent);
only the final arm64 runtime image is assembled for the Pis, so no slow emulation.

## Kubernetes

Manifests: `k8s/apps/ticketlite/` (namespace `apps`). Secrets (not in git):
- `app-env`: `DATABASE_URL`, `REDIS_URL` (existing, section 7.2 of the guide)
- `ticketlite`: `SESSION_SECRET` (`kubectl -n apps create secret generic ticketlite --from-literal=SESSION_SECRET="$(openssl rand -hex 32)"`)
- `ghcr-pull`: only if the GHCR packages are private

## Limits / next steps

- No payment, seat maps, or registration (by design).
- Scale workers with KEDA on stream lag.
- Rate limiting per user/IP for real rushes.
