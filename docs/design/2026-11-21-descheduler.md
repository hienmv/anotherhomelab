# Re-spread copies after an outage: the descheduler (roadmap O8)

Status: built 2026-11-22

## Problem

Every public site runs 2 copies with a **soft** spread rule (chapter 8: `ScheduleAnyway`, so a site
still runs when only one node is left). When a node goes away (2026-11-21: node-8gb off while its
charger was swapped), Kubernetes starts the copies elsewhere, correctly. When the node comes back,
**nothing moves them back**: the scheduler only decides where a pod *starts*, never revisits it.
Result: `verify.yml` reported 6 apps with "all copies on one node", and one more failure there would
take the sites down. The fix was a hand-made `rollout restart` per app, after every outage or reboot.

## Goals / non-goals

Goals: copies spread out again automatically within ~10 minutes of a node returning; never take a
site down while doing it; never touch the databases.

Non-goals: balancing by CPU/memory use, moving pods for other reasons (taints, affinity changes),
anything in namespaces we do not own.

## Options considered

| Option | For | Against |
|---|---|---|
| keep the manual `rollout restart` | nothing to run | a person after every outage; forgotten = a hidden single point of failure |
| hard spread (`DoNotSchedule`) | the scheduler never stacks copies | with one node left the second copy stays **Pending**: worse during the outage itself |
| the check restarts deployments itself (script) | small | a home-made controller; restarts **all** copies instead of moving one |
| **the descheduler** (kubernetes-sigs) | the standard answer to exactly this ("the scheduler never reconsiders"); evicts only the copy that breaks the rule; respects PodDisruptionBudgets | one more component |

## Decision

**The descheduler**, with a minimal policy: only `RemovePodsViolatingTopologySpreadConstraint`,
including soft constraints, only in `apps`, `infra`, `kube-system`.

## Design

```text
 every 10 min (CronJob)       descheduler: for each deployment with a spread rule:
                              copies 2+0 on the nodes? -> evict ONE copy (eviction API: PDB respected)
                              -> scheduler places the new copy on the emptier node (the soft rule)
```

| Setting | Why |
|---|---|
| only the topology-spread plugin, `constraints: [DoNotSchedule, ScheduleAnyway]` | our rules are soft (`ScheduleAnyway`); the default ignores soft ones |
| `topologyBalanceNodeFit: true` | evict only if the copy fits on another node (no evicting into Pending) |
| namespaces `apps`, `infra`, `kube-system` | the sites, cloudflared, Traefik; nothing else |
| `PodsWithPVC` protected | pods with a volume (Postgres, VictoriaMetrics, VictoriaLogs, ...) are never evicted |
| `SystemCriticalPods` allowed (podProtections) | Traefik runs with priority `system-cluster-critical` (k3s) |
| CronJob every 10 min | short-lived, no always-running pod |
| chart 0.37.0 (= Kubernetes 1.37) | the descheduler follows the Kubernetes version |

## Risks and how they are handled

| Risk | Answer |
|---|---|
| evicting takes a site down | eviction API: the PodDisruptionBudgets (minAvailable 1) allow one copy at a time; the other keeps serving |
| it evicts something it should not | only one plugin, only 3 namespaces, pods with volumes protected, DaemonSets never evicted (default) |
| ping-pong (evict, lands on the same node again) | `topologyBalanceNodeFit` + the soft rule steer the new copy to the emptier node |
| node-1gb (tainted, apps do not tolerate it) counted as a "node" | the spread rule's node set follows taints/affinity for our apps; tested in the drill below |
| a bad policy | dry-run first in a test cluster (below); rollback = revert |

## Test plan

Before merging: a throwaway k3s cluster in Docker (server + agent): a 2-copy deployment with the
same soft spread rule and a PDB; both copies forced onto one node; one descheduler run with this
exact policy -> one copy evicted and restarted on the other node, the site never below 1 copy.

After deploying: `kubectl -n kube-system get cronjob descheduler`, a run's log
(`kubectl -n kube-system logs job/<last>`), and `verify.yml` no longer reporting `spread:` some
minutes after a node returns.

## What the test cluster found (2026-11-22)

The test (2-node k3s v1.37.2 in Docker, 2 stacked copies with a soft spread rule and a PDB) changed
the design twice:

| Run | Result | Change |
|---|---|---|
| `evictSystemCriticalPods: true` + `podProtections` | the descheduler refused to start: "cannot use Deprecated fields alongside PodProtections" | use `podProtections.defaultDisabled: [SystemCriticalPods]` |
| + extra protection `PodsWithoutPDB` (only move pods that have a PDB) | ran, **0 evictions**, copies stayed stacked; same without the namespace filter or node-fit check | |
| the same **without** `PodsWithoutPDB` | **1 eviction**, copies on both nodes, PDB respected | `PodsWithoutPDB` not used |

`PodsWithoutPDB` would have been a good extra guard (system pods like DNS have no PDB), but in 0.37
it protects pods with a PDB too, so the descheduler would silently do nothing. The remaining limits
(one plugin, three namespaces, pods with volumes protected, eviction through PDBs) are enough here:
in `kube-system` only Traefik has 2 copies and a spread rule.

Lesson: a policy that looks right can do nothing at all. Only a run against a real (test) cluster
showed it, and it is why the test plan asks for "2+0 -> 1+1", not just "it starts".

## Rollback

Revert the PR (Flux removes the CronJob). Nothing depends on it; spread goes back to manual.
