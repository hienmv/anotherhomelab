# Postmortem: phase 2 migration outage (2026-11-11 → 2026-11-12)

| | |
|---|---|
| Impact | All public sites down (landing, notes, TicketLite; Grafana) for **about 21 h** |
| Data loss | none: every row restored, counts checked (concerts 7, flyway 2, orders 24, users 3) |
| Trigger | planned rebuild: k3s server + data moved from pi4-8gb (SD card) to the CF-SV9 laptop |
| Root cause | Ubuntu 26.04's AppArmor default `kernel.apparmor_restrict_unprivileged_unconfined = 1` stacks the container label (`cri-containerd.apparmor.d//&unconfined`); containerd's built-in profile then denies every signal inside a container ([containerd#14485](https://github.com/containerd/containerd/issues/14485), no fixed release) |
| Resolution | node-workstation reinstalled with **Ubuntu 24.04 LTS** (does not set that sysctl), no host AppArmor change |

Times are JST and approximate.

## Timeline

| When | What |
|---|---|
| 11-11 17:40 | `phase2-migrate.yml`: fresh backup, k3s uninstalled on the Pis (**sites down**), SD cards pre-set, Pi 4s powered off |
| 11-11 ~17:45 | Step 4b fails: Ansible 2.20 (Ubuntu package) does not recognise **sudo-rs**'s prompt. Fix: `ansible_become_exe: sudo.ws`, tags to resume |
| 11-11 ~17:55 | SD cards swapped; hardware check passes (name, IP, RAM, SD size per board) |
| 11-11 18:01–18:16 | New cluster up, but Postgres never becomes Ready (15 min timeout). Postmaster stuck in `starting` after "redo done" |
| 11-11 ~18:37 | Pod restart brings Postgres up. vmagent crash-loops: `cannot send SIGHUP to itself: permission denied`. Kernel log: thousands of `apparmor="DENIED" operation="signal"` → root cause found |
| 11-11 ~18:50 | Fix proposed: pre-load a corrected container profile. **Rejected by the owner: no host AppArmor changes**; also no data back on SD cards |
| 11-11 19:00–20:30 | Alternatives evaluated (VM node, Kata/gVisor, Unconfined pods, other runtimes: all mainstream runtimes affected). Verified from the packages: 24.04's apparmor sets only `restrict_unprivileged_userns` |
| 11-11 19:09 | The only copy of the pre-teardown backup copied from the workstation to the Mac (it would have died with the reinstall) |
| 11-12 ~00:30 | Ubuntu 24.04.4 installed by hand; `workstation-prepare.yml` (one fix: `python3-bcrypt` missing on 24.04); age key restored; backups decrypt-tested |
| 11-12 | Run 1 (`--tags teardown,k3s`): new empty cluster, 4 nodes Ready. **Canary**: Postgres pod + signal test on node-workstation pass, 0 container denials |
| 11-12 ~15:00 | Run 2 (`--tags restore`): Flux, secrets, Postgres restore with matching row counts, 4 URLs `200` (**sites up**). `verify.yml` `failed=0` |

## What went well

- **Backups were encrypted, complete and checked**: dumps with `PGDMP` check, row counts, all secrets
  including `tunnel-creds` (which existed nowhere else before 2026-11-11).
- **Everything was code**: the rebuild ran twice from the same playbooks; tags let each run resume
  at the failed step; nothing had to be redone by hand.
- **The SD swap was clean**: identities pre-set per card, host keys moved with the name, a hardware
  check stopped bad placements.
- **The owner's constraints held**: no host security weakened, data stayed on SSD.

## What went wrong

| Problem | Why it hurt |
|---|---|
| **No canary**: data moved before a test container ran on the new node | the AppArmor bug was found only after the old cluster was gone; the outage included a full OS reinstall |
| Newest OS release (26.04, five months old) used as a server | its new security default was ahead of containerd |
| Tool assumptions about the OS: sudo-rs prompt, `python3-bcrypt`, logind directory | three separate stops, each fixed and re-run |
| The pre-teardown backup lived only on the machine being changed | nearly lost when the reinstall was decided |
| Single machine for control plane + data + admin desktop | any problem on it is a full outage |

## Action items

| # | Action | Status |
|---|---|---|
| 1 | **Canary before moving data**: `verify.yml --tags containers` (Postgres pod + signals on every schedulable node) | done ([docs/testing.md](../testing.md)) |
| 2 | Container AppArmor denials checked on every node | done (`verify.yml --tags nodes`) |
| 3 | Server OS = previous LTS (Ubuntu 24.04), not the newest release | done |
| 4 | Backups copied off the machine automatically | open: `backup.yml` copies to the controller only when run from another machine; scheduled R2 backups (roadmap stage 1) |
| 5 | Remove the single point of failure: USB SSDs for the Pi 4s, 3 control-plane nodes, Postgres replica | open (roadmap stage 2) |
| 6 | Re-test on 26.04 when k3s ships a containerd with [PR #14487](https://github.com/containerd/containerd/pull/14487) | open: bump `k3s_version`, canary first |
| 7 | vmagent memory limit (OOMKilled once on the first full scrape) | done (768Mi) |
