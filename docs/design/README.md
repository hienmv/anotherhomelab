# Design docs

A design doc is written **before** building something non-trivial. Real teams call it an RFC,
design doc or ADR (architecture decision record). It makes you think the change through while it
is still cheap, and it explains later **why** things are the way they are, not just what they are
(the code shows what; the reasons get lost).

One file per change: `YYYY-MM-DD-<short-name>.md`. Keep it short; a page or two is plenty here.

## Template

Since 2026-11-26 the structure follows how design docs are written at Google and similar
engineering teams. The first full example is [D1 read routing](2026-11-25-read-routing.md). Older
docs use a shorter form and stay as they are.

Principles:
- **write it before the code**, and keep it updated while building: findings from tests go into the doc
- **measurable goals**: each goal names the number or test that proves it
- **alternatives with a verdict**: a reader must see why the obvious other way was not taken
- **cross-cutting concerns are not optional**: security, observability, compatibility and rollout are where designs fail in production
- **no guesses as facts**: an unverified claim is an open question until tested

```markdown
# <Title> (<roadmap id>)

| | |
|---|---|
| **Authors** | … |
| **Reviewers** | … |
| **Status** | Draft / In review / Approved / Implemented / Superseded by … |
| **Created / updated** | YYYY-MM-DD / YYYY-MM-DD |
| **Roadmap** | id ([roadmap](../roadmap.md)) |
| **Related** | other design docs, learn chapters, PRs |

## 1. Context and scope
The situation today, with facts and numbers. What this doc covers and what it does not.

## 2. Goals and non-goals
| # | Goal | Measured by |   (each goal testable)
Non-goals: what it deliberately does not do, and where that is handled instead.

## 3. Overview
The design in a few sentences and one picture. Someone who only reads this section understands the idea.

## 4. Detailed design
Flows (sequence diagrams), decision rules (tables), interfaces and config, data model changes,
failure modes (table: situation → behaviour).

## 5. Alternatives considered
| Option | Pros | Cons | Verdict |

## 6. Cross-cutting concerns
Security, privacy, observability (metrics, alerts, logs), performance and capacity, reliability and
SLOs, compatibility (mixed versions during a rolling update, rollback direction, old data / cookies).

## 7. Rollout plan
Order and dependencies, what to watch after deploying (numbers), rollback (steps, and what it costs).

## 8. Testing
| Level | What | Result |   (unit, e2e, after deploy; with dates and results once run)

## 9. Open questions
| # | Question | Owner / when |   (answered ones stay, struck through, with the answer)

## 10. Future work
Follow-ups with roadmap ids.

## Appendix
Measurements, bugs found by the tests, glossary links.
```

## Index

| Date | Doc | Status |
|---|---|---|
| 2026-11-21 | [Host logs in Grafana (O1)](2026-11-21-host-logs.md) | built 2026-11-21 |
| 2026-11-21 | [Re-spread copies after an outage: the descheduler (O8)](2026-11-21-descheduler.md) | built 2026-11-22 |
| 2026-11-24 | [Brief outages without errors: 503 + Retry-After, automatic retries (U1)](2026-11-24-graceful-db-outage.md) | built 2026-11-24 |
| 2026-11-25 | [Read routing with read-your-writes (D1)](2026-11-25-read-routing.md) (first doc in the full format) | implemented, in review |
| 2026-11-22 | [Postgres replica on node-8gb's SSD (A1)](2026-11-22-postgres-replica.md) | built 2026-11-22 |

Earlier stages were decided in the chapters themselves (docs/learn) and in the review
(docs/review/2026-11-15-resilience-review.md); from O1 on, each stage gets a design doc here.
