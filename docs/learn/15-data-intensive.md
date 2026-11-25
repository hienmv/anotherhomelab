# 15. Data: one leader, many readers

Chapter 8 kept the **apps** alive by running 2 copies. Copies of an app are easy: they hold no
data, so any copy can answer any request. A **database** is different. If 2 copies both accept
writes, they can disagree. This chapter is about where data lives and who may change it. Book: Martin
Kleppmann, *Designing Data-Intensive Applications* (DDIA), chapters 5, 7 and 9.

## Who takes writes: a single leader

TicketLite's Postgres has 2 instances since A1:

```text
 writes ──► pg-1 PRIMARY (leader) ── WAL stream ──► pg-2 REPLICA (follower)
 reads  ──► wherever correct (below)
```

- The **primary** (leader) takes every write and records it in its write-ahead log (WAL).
- The **replica** (follower) replays that log, so it holds the same data, a moment later.

Why not let both take writes? Two people buy the last ticket at the same moment, one on each node.
Each node sees "1 left" and says yes: oversold. Systems with several leaders (or none, like
Cassandra) do exist, but they resolve conflicts *afterwards*, and a ticket sold twice can't be
fixed afterwards. Deciding "who got the last ticket" needs one place that orders all writes: a
single leader, or a consensus protocol (Raft, as in etcd) that makes several nodes agree first.

TicketLite also protects the leader from a rush:
- the **Redis gate** checks the stock and the per-person limit atomically and turns away the excess at once
- accepted orders go into a **Redis stream** (a log)
- **workers** confirm them in Postgres at a steady pace, re-checking under a row lock

This is admission control plus a queue: the leader sees a calm stream, not the spike.

## Where reads go: by the guarantee each one needs

A replica is usually under a second behind. "A second old" is fine for some reads and wrong for
others, so each read says what it needs (D1, [design](../design/2026-11-25-read-routing.md)):

| Guarantee | Plain words | TicketLite example | Goes to |
|---|---|---|---|
| eventual | "a recent copy is fine" | the concert list, login | replica |
| **read-your-writes** | "I see what I just did" | right after buying: my order exists | replica only if it already has my write |
| **monotonic reads** | "time never goes backwards for me" | my order was CONFIRMED; reloading must not show PENDING | same check |
| **linearizable** | "the one true latest value" | is a ticket left? may I buy more? | Redis gate + primary only |

### How "the replica already has my write" is checked: LSN tokens

Every write in Postgres has a position in the WAL, the **LSN** (log sequence number, e.g.
`0/3000060`). It only grows.

1. After my write, the api asks the primary for its current LSN and keeps it in my session cookie (my **token**).
2. My next read goes to the replica, which first answers "how far have I replayed?" (`pg_last_wal_replay_lsn()`).
3. Replayed past my token: the replica serves my read. Not yet: the primary serves it, and my token moves to the primary's position, so I never see older data later.

This is an exact check, not a guess like "wait 2 seconds". Real services (GitHub, Shopify, the
database routing in Rails) use the same idea.

### When things break

| Situation | What happens |
|---|---|
| replica behind > 10 s | its reads move to the primary (`lagging`) |
| replica down | the first read waits 1 s, then a **circuit breaker** skips the replica for 30 s: no waiting on every request |
| **primary down** (failover) | reads still work from the replica (concert list, my orders); buying gets 503 + "not placed" until the replica is promoted (≤ 75 s) |

Measured in the e2e test; numbers in the design doc. See where reads go:
`curl -s <api>:8080/metrics | grep ticketlite_db_reads_total`.

## How safe is a write? (asynchronous replication)

Today the primary confirms a write **before** the replica has it (asynchronous). If the primary
dies in that moment, the newest writes (normally under a second) are lost in the failover. This is
the **RPO** (recovery point objective) of a failover.

**Synchronous** replication waits for the replica first: 0 loss, but every write is slower. If the
replica is down, writes either stop ("strict") or continue alone ("preferred"). Roadmap **D2**
chooses "preferred" for TicketLite: 0 loss while both run, never stuck when node-8gb is down.

## Why not split the data (partitioning / sharding)?

**Partitioning** (sharding) puts different rows on different leaders, e.g. concerts 1-1000 on one
node, the rest on another. It is how writes scale beyond one machine. It costs a lot:
- cross-partition queries
- rebalancing data between nodes
- transactions over several partitions

One Postgres primary handles thousands of writes per second; TicketLite has a handful. The rule
real teams use: partition when one leader can no longer keep up, and not before. Roadmap **D4**
writes the trigger down.

## Getting events out reliably (outbox)

Today the api inserts the order (Postgres) and then adds it to the queue (Redis): **two** systems,
two separate steps. If it crashes between them, the order exists but no worker hears about it.

The **outbox** pattern writes the event into a Postgres table in the **same transaction** as the
order. A relay then publishes it to the queue. This is called change data capture when it reads the
WAL itself (Debezium). Roadmap **D3**.

## Try it

```sh
cd apps/ticketlite
docker compose -f docker-compose.yml -f docker-compose.replica.yml up --build -d
curl -s localhost:8080/api/concerts >/dev/null; curl -s localhost:8081/metrics | grep db_reads   # target="replica"
docker compose exec replica psql -U app -d app -c 'select pg_wal_replay_pause()'   # replica stops replaying
docker compose stop postgres                                                         # the leader dies: reads still work
docker compose -f docker-compose.yml -f docker-compose.replica.yml down -v
```
