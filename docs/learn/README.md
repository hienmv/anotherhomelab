# Learning this homelab

A handbook for someone new to DevOps, Ansible and Kubernetes. It explains **why** things are the
way they are, not only which commands to type. The technical reference is elsewhere (links below);
start here.

## Reading order

| # | Chapter | You learn |
|---|---|---|
| 1 | [The big picture](1-big-picture.md) | the machines, the layers, how a web request and a code change travel |
| 2 | [Ansible](2-ansible.md) | what Ansible is, inventory, playbooks, tasks; every playbook in this repo |
| 3 | [GitOps with Flux](3-gitops-flux.md) | how `git push` becomes a running app; Kustomize; Flux variables |
| 4 | [Configuration, not code](4-configuration.md) | where every value lives (domain, IPs, users) and how to change one safely |
| 5 | [Accounts and SSH](5-accounts-and-ssh.md) | keys, passphrases, the agent, who can log in where, and why |
| 6 | [Daily operations](6-daily-operations.md) | health check, backups, deploying, reading logs, what to do when something breaks |
| 7 | [Git workflow](7-git-workflow.md) | branches and pull requests, why `main` is protected, deploy keys, how a change gets in |
| 8 | [Surviving a node failure](8-surviving-a-node-failure.md) | 2 copies per site, spreading over nodes, disruption budgets; what is still single |
| 9 | [Monitoring with Grafana](9-monitoring-grafana.md) | metrics and central logs: where each is stored, searching logs in Grafana, what a node shutdown loses |
| 10 | [Tracing](10-tracing.md) | traces and spans, OpenTelemetry (agent, collector), following one purchase through api, queue and worker |
| 11 | [SLOs](11-slo.md) | SLI, SLO, error budget, burn-rate alerts to Telegram, testing alerts like code |
| 12 | [Everything through git](12-gitops-secrets.md) | secrets encrypted in git (SOPS + age), instant chart upgrades, Flux messages, checks with the newest code |
| – | [Glossary](glossary.md) | every term used in these pages, in one place |

## Golden rules (learned the hard way, see the [postmortem](../postmortems/2026-11-11-phase2-migration.md))

1. **Change things through code**, never by hand on a server: edit the repo, commit, run the
   playbook or let Flux deploy. Then the next rebuild does the same thing.
2. **Test before you trust**: run `verify.yml` after every change; try new things on one node
   (a "canary") before moving data.
3. **Back up before anything risky**, and keep the age key in the password manager.
4. **Write it down while you do it** (this handbook, the guide, commit messages).

## Where the reference documentation is

| Topic | Document |
|---|---|
| Overview, diagrams, components | [README.md](../../README.md) |
| How it was built, decisions, troubleshooting | [K3S-GUIDE.md](../../K3S-GUIDE.md) |
| Hostnames, Cloudflare, routing | [docs/domains.md](../domains.md) |
| Every test and health check | [docs/testing.md](../testing.md) |
| How resilient is it, compared to real systems? | [docs/review/](../review/2026-11-15-resilience-review.md) |
| The Ansible folder | [ansible/README.md](../../ansible/README.md) |
| Each app | `apps/<app>/README.md` |
