# Design docs

A design doc is written **before** building something non-trivial. Real teams call it an RFC,
design doc or ADR (architecture decision record). It makes you think the change through while it
is still cheap, and it explains later **why** things are the way they are, not just what they are
(the code shows what; the reasons get lost).

One file per change: `YYYY-MM-DD-<short-name>.md`. Keep it short; a page or two is plenty here.

## Template

```markdown
# <Title> (roadmap <id>)

Status: proposed | accepted | built <date> | replaced by <file>

## Problem
What hurts today, with a real example. Who or what is affected.

## Goals / non-goals
What it must do. What it deliberately does not do (scope).

## Options considered
| Option | For | Against |

## Decision
The chosen option, and why it wins over the others.

## Design
How it works: a picture, the pieces, where the config lives.

## Risks and how they are handled
What could go wrong (security, failure, cost) and the answer to each.

## Test plan
How we prove it works: before merging (locally), after deploying (on the real system).

## Rollback
How to undo it if it goes wrong.
```

## Index

| Date | Doc | Status |
|---|---|---|
| 2026-11-21 | [Host logs in Grafana (O1)](2026-11-21-host-logs.md) | built 2026-11-21 |
| 2026-11-21 | [Re-spread copies after an outage: the descheduler (O8)](2026-11-21-descheduler.md) | built 2026-11-22 |
| 2026-11-24 | [Brief outages without errors: 503 + Retry-After, automatic retries (U1)](2026-11-24-graceful-db-outage.md) | built 2026-11-24 |
| 2026-11-22 | [Postgres replica on node-8gb's SSD (A1)](2026-11-22-postgres-replica.md) | built 2026-11-22 |

Earlier stages were decided in the chapters themselves (docs/learn) and in the review
(docs/review/2026-11-15-resilience-review.md); from O1 on, each stage gets a design doc here.
