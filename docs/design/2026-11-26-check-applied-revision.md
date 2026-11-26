# The health check judges the applied revision (host GitOps fix)

| | |
|---|---|
| **Authors** | Hien V. Mai (owner), Claude (draft) |
| **Reviewers** | Hien V. Mai |
| **Status** | In review |
| **Created / updated** | 2026-11-26 / 2026-11-26 |
| **Roadmap** | fix to host GitOps ([learn/14](../learn/14-host-gitops.md)) |
| **Related** | [H2 Ethernet](2026-11-26-workstation-ethernet.md) (where it showed) |

## 1. Context and scope

Two timers on node-workstation share one repo checkout (`~ops/workspace/source/homelab`):

| Timer | Runs | Did with the checkout |
|---|---|---|
| `homelab-apply` | `*:05/20/35/50` | fast-forward to `main`, then `site.yml` |
| `homelab-check` | `*:00/15/30/45` | **also** fast-forward to `main`, then `verify.yml` |

The check ran the newest `main` against machines that had **not** applied it yet. On 2026-11-26 #46
(`lan_link: ethernet`) was merged:
- **23:15** the check sent a false 🔴: `192.168.40.249 is on 'wlp0s20f3', lan_link is ethernet`
- **23:20** the apply switched the link, correctly

Any merge that changes the desired state of a machine could raise such an alert, and false alerts
train people to ignore real ones.

Scope: the two scripts (`homelab-check.sh`, `lib-homelab.sh`). The apply is unchanged.

## 2. Goals and non-goals

| # | Goal | Measured by |
|---|---|---|
| G1 | the check never judges a merge the apply has not applied yet | test 2: a merge 5 min old → the check runs the previous `verify.yml`, the checkout does not move, state ok |
| G2 | a merge that is **never** applied is still reported | test 3: still pending after 45 min → problem "host apply has not applied … for N min" |
| G3 | once applied, the check uses the new code | test 4: after the apply's fast-forward → new `verify.yml`, pending gone |
| G4 | GitHub unreachable: still checks, reports the fetch problem | test 5 |

Non-goals:
- making the check apply anything
- running both timers from separate checkouts (one checkout keeps code and state in step)

## 3. Overview

Only the apply moves the checkout. The check **fetches** `main` (updates `origin/main` only) to compute
what is pending, and verifies `HEAD`, which is what the apply last applied.

```text
merge ──► origin/main = B          checkout HEAD = A (applied)
check:    verify.yml of A           + "pending: B, merged N min ago"   (problem only after 45 min)
apply:    ff to B, site.yml         HEAD = B
check:    verify.yml of B
```

This is the same principle as Kubernetes' `status.observedGeneration` and Flux's
`status.lastAppliedRevision`: health describes the **applied** desired state; "newer desired state not
yet applied" is a separate signal (progressing / stalled), not a failure.

## 4. Detailed design

| Piece | Change |
|---|---|
| `lib-homelab.sh` | new `fetch_main` (fetch only, same anonymous HTTPS as `update_repo`) |
| `homelab-check.sh` | `update_repo` → `fetch_main`; `applied=HEAD`, `latest=origin/main`; if `latest` is ahead: log `pending`, and a problem when `now − committer time of latest ≥ 45 min` (`HOMELAB_PENDING_MAX`) |
| `homelab-apply.sh` | unchanged (fetch + fast-forward + apply) |

"Merged N min ago" uses the newest commit's committer time. A squash merge on GitHub sets it to the
merge time. A pushed commit with an old committer date could look older than it is, but `main` is
protected and only takes squash merges and the CI bot's deploy commits, both stamped at push time.

Failure modes:

| Situation | Behaviour |
|---|---|
| the apply fails on the new commit | the apply has already fast-forwarded: the check verifies the new commit (real failures show), and the apply's own 🔴 says why |
| the apply timer stopped | pending grows → problem after 45 min, plus the existing "host apply has not run for N h" |
| a deploy commit by CI (image tag) | pending for ≤ 15 min; no alert |

## 5. Alternatives considered

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **A. Check verifies the applied HEAD, fetch for pending** (chosen) | exact; tiny change; one checkout | — | chosen |
| B. Run the apply before every check | always current | the check becomes slow (site.yml) and does changes; mixes "observe" with "act" | rejected |
| C. Suppress alerts for 20 min after a merge | simple | hides real problems in that window; a guess at timing | rejected |
| D. Two checkouts (check on its own) | independent | the check would still need to know the applied revision: same problem, more moving parts | rejected |

## 6. Cross-cutting concerns

- **Observability:** the `pending` line goes to the check's log (`~/.local/state/homelab/last-check.log` and the journal). The 45-min rule turns a stuck rollout into an alert.
- **Security:** none. Same anonymous fetch.
- **Compatibility:** the new check script reaches the workstation through the apply, which by then has applied this same commit. The first new check therefore already sees `HEAD = applied`.

## 7. Rollout plan

Merge. The next apply fast-forwards and installs nothing new (the scripts run from the checkout). The
next check uses the new logic. Rollback: revert the PR.

## 8. Testing

| Level | What | Result |
|---|---|---|
| integration | `scripts/test-host-gitops-check.sh`: real git repos (a bare "GitHub" + the checkout), a fake `ansible-playbook` that records which `verify.yml` ran; 5 scenarios (nothing new; merge 5 min ago; still pending after 50 min; after the apply; GitHub unreachable) | 14 checks pass (Ubuntu 24.04); in CI |

## 9. Open questions

None.

## 10. Future work

- Show the applied revision and pending age as a metric (node-exporter textfile), like Flux's status in Grafana.
