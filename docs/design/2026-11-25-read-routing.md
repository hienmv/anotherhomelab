# Reads on the replica, with read-your-writes (roadmap D1)

Status: built 2026-11-25 (e2e-tested with a real streaming replica; needs A1 deployed)

## Problem

Since A1 Postgres has 2 instances: pg-1 (primary, node-workstation) and pg-2 (streaming replica,
node-8gb). TicketLite still sends **every** query, reads included, to `pg-rw` (the primary). The
replica only waits to be promoted.

Two things follow:
- One node does all the database work while another, with a full copy of the data, sits idle.
- During a failover (≤ 75 s) nothing works, not even the concert list, although the replica holds all the data.

A data-intensive system routes each read to where it can be served correctly, and keeps the single
leader only for what needs it.

## Goals / non-goals

Goals:
- Reads that tolerate a little staleness go to the replica.
- A user always sees their own writes (**read-your-writes**) and never sees time go backwards (**monotonic reads**).
- Anything that decides a sale stays on the primary (**linearizable**).
- Automatic fallback to the primary when the replica is down, behind, or not caught up with the user.
- Read-only operation (concert list, login, own orders) while the primary is gone during a failover.
- Visible: a metric for where reads went, and why.

Non-goals:
- More replicas, or load balancing between replicas. With one replica, monotonic reads hold automatically.
- Writes on more than one node: single leader stays (why: D4).
- Synchronous replication: D2.

## Background: the consistency guarantees (DDIA ch. 5)

| Guarantee | Means | Breaks when |
|---|---|---|
| eventual | replicas converge if writes stop | always OK for the concert list |
| **read-your-writes** | after I write, my reads show it | I buy, the next read goes to a replica that has not replayed my insert yet: "order not found" |
| **monotonic reads** | I never see older data after newer data | read 1 from the primary (new), read 2 from a lagging replica (old): my CONFIRMED order turns back into PENDING |
| **linearizable** | behaves like one copy, the latest value | needed for "is there a ticket left?": stays on Redis + primary (the worker's row lock) |

## Options considered

| Option | For | Against |
|---|---|---|
| **app-level routing: 2 pools (`pg-rw`, `pg-ro`), each query marked with the guarantee it needs, read-your-writes via an LSN token in the session** | exact guarantees per read; standard technique (e.g. Rails/Django "database selection" with a recent-write window; GitHub, Shopify use LSN or time tokens) | code in the app |
| send reads to the replica only N seconds after a user's last write (time window) | simple | a guess: lag can exceed N during load or after a replica restart, and then the guarantee silently breaks |
| a proxy (Pgpool-II, ProxySQL-style) decides | no app code | it can't know a read's guarantee; Pgpool's own replication-delay check is coarse; one more component to run |
| synchronous `remote_apply` on the replica, then read anywhere | read-your-writes for everyone | every write waits for the Pi over Wi-Fi; if the replica is down writes stall (D2 handles durability with `preferred`, not this) |

## Decision

App-level routing with **LSN tokens**. An LSN (log sequence number) is the position in Postgres'
write-ahead log; every committed write has one, and a replica reports how far it has replayed. "The
replica has replayed past my token" is an exact check, not a guess.

## Design

```text
                       ┌─────────────────────────── api ───────────────────────────┐
 POST /orders ────────►│ write path: PRIMARY only (stock, limits, insert)           │──► pg-rw
                       │   after commit: token = pg_current_wal_lsn() → session      │
 GET  /concerts ──────►│ read: REPLICA if up, caught up with the token, lag ≤ 10 s   │──► pg-ro
 GET  /orders[/id]     │   else PRIMARY (then token = primary's current LSN:         │──► pg-rw
 POST /auth/login      │   monotonic: the next replica read must reach that point)   │
                       └────────────────────────────────────────────────────────────┘
 worker: PRIMARY only (it writes)
```

| Query | Guarantee | Goes to |
|---|---|---|
| concert list (anonymous), the `/metrics` inventory gauge | eventual, lag ≤ 10 s; `available` comes from the Redis gate anyway | replica |
| login (`findUser`) | eventual (accounts are seeded, no sign-up) | replica |
| concert list + "you hold N" (logged in) | read-your-writes | replica if caught up with the token |
| `GET /orders`, `GET /orders/{id}` | read-your-writes + monotonic | replica if caught up with the token |
| everything in `POST /orders` (`concert`, `available`, `userHeld`, insert, markFailed) | linearizable | primary |
| worker | writes | primary |

How it works:
- **Token:** after the api commits a write, it reads `pg_current_wal_lsn()` on the same connection and stores it in the session cookie. The cookie is signed, so a client can't fake it, and a fake would only change where that user's reads go anyway.
- **Replica check:** one cheap query per read, on the replica connection, `pg_last_wal_replay_lsn() >= token` plus the lag. The lag counts as 0 when the replica has replayed everything it received, i.e. the primary is just idle.
- **Fallback to the primary:** when the replica is not caught up, more than 10 s behind, or unreachable. A read from the primary moves the user's token to the primary's current LSN, so the next replica read must be at least that new: monotonic reads.
- **Circuit breaker:** after the replica fails to connect, skip it for 30 s. Without that, every read would first wait for a dead replica. The replica pool also has a short connect timeout (1 s).
- **The worker's confirmation** (PENDING → CONFIRMED) is written by the worker, not the user's request, so it carries no token. The status poll sees it once the replica replays it (normally < 1 s) and keeps polling until then: eventual, and the user never sees it go backwards.
- **Config:** `DATABASE_READ_HOST` (`pg-ro.data.svc.cluster.local` in `k8s/apps/ticketlite/backend.yaml`) is optional. It takes the user, password and database from `DATABASE_URL`, so no new secret is needed. Unset (local runs, tests) means every query goes to the primary, exactly as before. The replica pool is read-only and doesn't block startup: the api starts even when the replica is down.
- **Metric:** `ticketlite_db_reads_total{target, reason}`, where `target` is replica or primary. `reason` is one of:
  - `ok`: served by the replica
  - `write_path`: part of a write, primary by design
  - `not_caught_up`: the replica had not reached the user's token yet
  - `lagging`: the replica was more than 10 s behind
  - `replica_down`: the replica was unreachable (or skipped by the circuit breaker)
  - `no_replica`: `DATABASE_READ_HOST` is unset

### During a failover

The primary dies, and `pg-ro` (the replica) still answers:
- concert list, login and "my orders" keep working
- POST /orders gets 503 (U1)

After the promotion:
- `pg-ro` has no endpoints until the old primary rejoins as a replica, so the circuit breaker sends reads to the new primary.
- LSNs keep growing across the promotion (a new timeline continues from the old position), so existing tokens stay valid.

### Stale replica while disconnected

If the replica loses its stream but the primary is alive, it stops advancing:
- The lag reads 0, because it has replayed everything it received.
- Anonymous reads could then be stale beyond 10 s.
- Logged-in reads are still safe: their token check fails, so they fall back to the primary.

`PostgresReplicaNotStreaming` (A1) alerts after 15 min. Accepted: the concert list shows Redis' live `available` anyway.

## Risks

| Risk | Answer |
|---|---|
| a read that needs fresh data is sent to the replica | the table above is the review checklist; each Repo method declares its guarantee in code |
| every read pays an extra query on the replica | one `select` of two functions, microseconds; it replaces a full query on the primary |
| the cookie changes on writes and fallback reads | a few bytes; signed as before. Old cookies without a token still work (no token = nothing to wait for) |
| the replica is slower (Pi, USB SSD) | the lag limit and fallback cover it; TicketLite's reads are small |

## Test plan

- unit: routing decisions (no URL, ok, not caught up, lagging, down + circuit breaker, write path), LSN comparison, old session cookies without a token
- e2e (docker compose + a real streaming replica): reads land on the replica (metric); right after a buy the poll is served correctly; replica paused → reads fall back to the primary; replica stopped → circuit breaker, everything still works; primary stopped → concert list + my orders still answer, buying gets 503
- after deploy: metric shows `target="replica"` taking the reads; `verify.yml` still clean

## Results (2026-11-25)

e2e: `apps/ticketlite/docker-compose.replica.yml` (primary + a `pg_basebackup` streaming replica),
the real api, web and worker; counts from `ticketlite_db_reads_total`.

| Step | Result |
|---|---|
| normal reads | 15 of 15 on the replica (`ok`) |
| buy, then read the order at once | 200: read-your-writes (token in the cookie); CONFIRMED 2 s later |
| replica paused (`pg_wal_replay_pause()`), buy again | own new order 200 from the primary (`not_caught_up`); anonymous list from the primary after 10 s (`lagging`) |
| replica stopped | first read 1.0 s (connect timeout), then 16-33 ms: the circuit breaker skips it (`replica_down`) |
| **primary stopped** | concert list, logged-in list, my orders, order status: **all 200 from the replica**; buying: 503 (U1) |

Found by the e2e, fixed before the PR:
- the replica-state query had one placeholder but got two parameters, so the api did not start
- the `/metrics` gauge read the primary on every scrape, so monitoring loaded the leader; it now reads the replica

## Rollback

Remove `DATABASE_READ_HOST` from the Deployment: every query goes to the primary again.
