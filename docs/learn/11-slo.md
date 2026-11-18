# 11. SLOs: how reliable is "reliable enough", and when to wake up

Until 2026-11-18 the alerts said "**something** is broken" (a pod, a check). That is not the same as
"**visitors** are affected", and it says nothing about *how much* failure is acceptable. Real
systems answer both with **SLOs**.

## Four words

| Word | Meaning | Here |
|---|---|---|
| **SLI** (indicator) | a number measured from real traffic: the share of good requests | requests without a 5xx, at Traefik |
| **SLO** (objective) | the target for that number, over a window | 99.5 % of requests, over 30 days |
| **error budget** | what the target allows to fail: 1 − SLO | 0.5 % of requests (≈ 3.6 h of full outage a month) |
| **burn rate** | how fast the budget is being spent. 1 = exactly on budget for 30 days | 14.4 = the month's budget gone in ~2 days |

Why not 100 %? Nothing is 100 %: Wi-Fi drops, a deploy restarts pods, Cloudflare has a bad minute.
A target of 100 % means every blip is an emergency, and you stop changing anything. The budget makes
it explicit: **while budget is left, ship; when it is used up, fix reliability first.**

## The SLOs of this homelab

| SLO | Sites | Good request | Objective (30 days) | Budget |
|---|---|---|---|---|
| **availability** | landing, notes, ticketlite (web + api) | answered without 5xx | **99.5 %** | 0.5 % |
| **latency** | ticketlite api | answered within **300 ms** | **99 %** | 1 % |

Chosen for a homelab on Wi-Fi with one database node; a company would set them from what users
need and what the business can pay for. Measured at **Traefik**, which every visitor request passes
(`traefik_service_requests_total`, `traefik_service_request_duration_seconds`), so all sites are
measured the same way, close to the visitor.

Not measured: requests that never reach Traefik (Cloudflare or the tunnel down). Those are covered
from outside by the scheduled checks (`verify.yml` calls the public URLs) and healthchecks.io.

## How the alerting works (multiwindow, multi-burn-rate)

The method from Google's SRE workbook, chapter "Alerting on SLOs". An alert fires when the budget
burns too fast over a **long** window (it is real, not a blip) **and** still over a **short** window
(it is still happening, so the alert stops soon after the problem does):

| Alert | Severity | Long / short window | Burn rate | Means |
|---|---|---|---|---|
| `SLOErrorBudgetBurnFast` | critical | 1 h / 5 min | 14.4 | 2 % of the month's budget gone in 1 hour |
| `SLOErrorBudgetBurnFast` | critical | 6 h / 30 min | 6 | 5 % gone in 6 hours |
| `SLOErrorBudgetBurnSlow` | warning | 1 day / 2 h | 3 | 10 % gone in a day |
| `SLOErrorBudgetBurnSlow` | warning | 3 days / 6 h | 1 | on track to miss the month |

Plus a **low-traffic guard**: no alert unless the site had at least 10 requests in the last hour.
With 3 visitors, 1 failed request would be "33 % errors": not a signal. (Real systems with little
traffic add synthetic requests; here the scheduled checks send a few every 15 minutes.)

Example, 10 % of landing's requests fail for an hour: the error ratio 0.10 is over 14.4 × 0.005 =
0.072 in both the 1 h and the 5 min window → `SLOErrorBudgetBurnFast` (critical) in Telegram:

```text
🔴 landing: availability SLO error budget burning 14.4x too fast (1h window)
landing has 10% bad requests over 1h (objective 0.995, allowed 0.005). At this rate the 30-day
budget is gone in 2.1 days. Check Grafana: dashboard "SLOs", then logs level:=ERROR.
```

and `🟢 resolved: ...` when it is over. That is exactly the case in the unit test below.

## The pieces

```text
 Traefik /metrics ──scrape 30 s──► VictoriaMetrics ◄── vmalert (every 20 s: recording rules,
 (every request)    (vmagent)                           then alert rules) ──► Alertmanager ──► Telegram
                                        ▲                                    (group, repeat 6 h,
                                        └── Grafana dashboard "SLOs"          inhibit, resolve)
```

| Part | File | What |
|---|---|---|
| scrape | `k8s/monitoring/scrapes.yaml` (`traefik`), `k8s/infra/traefik-config.yaml` (`metrics:`) | Traefik's request counters and latency buckets |
| rules | `k8s/monitoring/slo-rules.yaml` (VMRule) | **recording rules** `slo:error_ratio:rate5m … rate30d` (one per window, per SLO and site), `slo:error_budget_remaining:ratio`, then the alerts above |
| tests | `k8s/monitoring/slo-rules.test.yaml`, `scripts/test-slo-rules.sh` | fake traffic in, expected alerts out (CI, every PR) |
| evaluate | vmalert (`vm-stack-values.yaml`) | runs all rules every 20 s; also the chart's default Kubernetes rules (not sent to Telegram) |
| notify | Alertmanager; config **Secret** from `ansible/templates/alertmanager.yml.j2`, encrypted into git by `playbooks/alerting.yml` ([chapter 12](12-gitops-secrets.md)) | SLO alerts → Telegram; same site + SLO: a critical silences the warning; reminder every 6 h; `resolved` message |
| see | Grafana dashboard **Apps → SLOs** (`k8s/monitoring/dashboards/slo.json`) | availability over 30 days, budget left, burn rate, traffic, latency, alerts firing |
| check | `verify.yml` (`slo:` problems) | Traefik metrics arrive, Alertmanager config exists, which SLO alerts fire |

Why **recording rules**? `rate(...[30d])` over every request of a month is expensive; computing it
once a minute and storing the result (`slo:error_ratio:rate30d`) makes alerts and the dashboard cheap.

## Testing alerts like code

An alert that never fires when it should is worse than none: you trust it. So the rules have unit
tests, run in CI on every PR (`scripts/test-slo-rules.sh`, with `vmalert-tool`, like Prometheus'
`promtool test rules`):

| Test | Fake traffic | Expected |
|---|---|---|
| 1 | landing: 10 % of requests are 500, for 2 h | both `SLOErrorBudgetBurnFast` (1h/5m and 6h/30m), with the exact Telegram text |
| 2 | notes: no errors | nothing |
| 3 | ticketlite web: 1 in 3 fails, but only ~6 requests per hour | nothing (low-traffic guard) |
| 4 | ticketlite api: 10 % slower than 300 ms | the latency alert for 6h/30m only (over 6 × 1 %, under 14.4 × 1 %) |

Proved once on purpose: lowering the guard to 1 request made test 3 fail.

## Using it

- **Grafana → Dashboards → Apps → SLOs**: green numbers and budget left > 50 % = relax.
- A **critical** in Telegram: look now (dashboard, then logs `level:=ERROR`, then a trace).
- A **warning**: look today; something degrades slowly.
- **Budget below 0** at the end of a month: the SLO was missed. Write a short postmortem, make
  reliability work the next task before new features.
- After changing the Telegram bot or chat: `ansible-playbook playbooks/alerting.yml` (as `ops`), then commit
  the changed `*.sops.yaml` files and open a PR ([chapter 12](12-gitops-secrets.md)).

## Changing an SLO

Change the objective in `slo-rules.yaml` (the `objective` label, the budget in
`slo:error_budget_remaining:ratio` and the alert thresholds = burn rate × budget), adjust the tests,
`scripts/test-slo-rules.sh`, PR. Real systems generate these rules from a short SLO file with tools
like **Sloth** or **Pyrra**; with 2 SLOs, writing them out keeps every number visible for learning.

Back to: [README](README.md)
