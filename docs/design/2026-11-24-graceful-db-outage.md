# Brief outages without errors: 503 + Retry-After and automatic retries (roadmap U1)

Status: built 2026-11-24

## Problem

Postgres is briefly unavailable several times a year by design. Some examples:
- a CNPG failover (≤ 75 s, [postgres replica](2026-11-22-postgres-replica.md))
- a restart of the primary after a spec change (~30 s; PR #41 itself causes one)
- a switchover

Redis restarts in seconds.

What a TicketLite user saw during such a window (checked in the code, 2026-11-24):
- The page loads: the web pods don't need the database.
- Every action that needs the database **hangs 30 s**. That is HikariCP's default `connectionTimeout`: it waits for a connection that never comes.
- Then the action fails with **500 `INTERNAL`**, and the page says "Something went wrong (INTERNAL)". That reads like a bug, and the user has no idea whether retrying helps.
- An order that **was** accepted can still show this error, if the status poll (`GET /api/orders/{id}`) happens to land in the window.

## Goals / non-goals

Goals:
- A brief outage is answered fast and honestly: **503 Service Unavailable + `Retry-After`**. A 500 stays reserved for bugs.
- Reads retry by themselves while a banner says "Reconnecting…". Writes are never repeated blindly.
- The outage is visible: a metric and a log line naming the dependency.

Non-goals:
- A planned **maintenance page with a time window**: roadmap U2.
- A public status page: U3.
- Hiding the outage from the SLO. These 503s still count as errors in the availability SLI: users really could not buy for that minute. The budget (99.5 %) easily absorbs a few one-minute windows a month.

## Options considered

| Option | For | Against |
|---|---|---|
| **api: classify outage errors → 503 + Retry-After; short pool timeout; web: retry reads, never writes** | standard HTTP semantics; fast answer; the client decides what is safe to repeat | some code in both apps |
| readiness probe checks the database | Traefik stops sending traffic | when the DB blips, **all** api pods drop out at once: every request becomes Traefik's own 503, including those that don't need the DB (`/auth/me` with a session). This is a classic cascading failure |
| Traefik retry middleware | no app code | it retries POSTs too (double orders), and it can't tell an outage from a bug |
| retry writes with an idempotency key | buying would survive a failover too | needs a key column + dedup logic; worth it later if orders ever matter more (noted, not done) |

## Decision

Option 1. It is what real services do: 503 + `Retry-After` for "temporarily unavailable"; clients retry **idempotent** requests (GET) with backoff and never blindly repeat non-idempotent ones (POST).

## Design

```text
 Postgres failover / restart
   │
   ▼
 api: HikariCP connectionTimeout 5 s (was 30 s)        DB_CONNECT_TIMEOUT_MS
   │ error → unavailableDependency(e)?  (Unavailable.kt)
   │   SQLTransientConnectionException (pool timeout), SQLSTATE 08xxx (connection),
   │   57P01/57P02/57P03 (shutting down / starting), 25006 (demoted primary), JedisConnectionException
   ├─ yes → 503 {"error":"UNAVAILABLE"} + Retry-After: 5; metric ticketlite_dependency_unavailable_total{dependency}
   └─ no  → 500 INTERNAL (a bug: stack trace in the log, as before)
   ▼
 web api.ts
   GET  + 502/503/504/network error → wait Retry-After (else 1, 2, 4 … max 10 s), retry up to 90 s,
                                      banner "Reconnecting…" meanwhile (ConnectionBanner)
   POST → no retry. 503: "…briefly unavailable. Your order was not placed."
                    502/504: "…Check "My orders" before trying again." (the order may have worked)
```

Why 503 means "not placed" but 502/504 don't:
- **503** comes from the api, which then releases the Redis reservation and marks the order failed; or from Traefik when no api pod is ready, in which case the request never arrived.
- **502/504** come from a proxy whose connection broke or timed out. The api may have finished the order anyway.

Why 90 s of retries: it covers the measured failover (≤ 75 s) and a primary restart (~30 s).

## Risks and how they are handled

| Risk | Answer |
|---|---|
| a real bug classified as an outage (hidden as 503) | only connection-level errors are classified; query errors (syntax, constraint, …) stay 500. Unit-tested both ways |
| retries pile up and hit a recovering database | at most one request per open page every 5-10 s; tiny here |
| 5 s is too short under normal load | the pool has 5 connections; a healthy pool hands one out in milliseconds. The wait is configurable |
| the banner gets stuck | every finished read (answered or given up) clears it; unit-tested |

## Test plan

- backend unit tests: the classifier, both ways; a test app answers 503 + `Retry-After` for an outage and 500 for a bug; the metric is counted
- web unit tests: a read retries after 503 (honours `Retry-After`) and after a network error; the banner state goes true → false; a read gives up after 90 s with `UNAVAILABLE`; a write is never retried; a proxy 502 without JSON maps to `UNAVAILABLE`; the buy messages for 503 and 504
- e2e (docker compose, real Postgres stopped), 2026-11-24: first request 503 at once (`57P01` terminating connection), next one 503 after 5.0 s (pool timeout), `Retry-After: 5` passes through nginx, metric 2; Postgres back → 200 in ~4 s

## Rollback

Revert the PR. Nothing stored changes.
