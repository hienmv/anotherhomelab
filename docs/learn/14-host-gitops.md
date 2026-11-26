# 14. Host GitOps: the machines follow main too

Flux makes the **cluster** follow git (chapter 3). The **machines** under it (services on
node-workstation, Garage on node-8gb, accounts and SSH on the Pis, the SSD) were set up by running
Ansible by hand. Since 2026-11-18 they follow `main` the same way: merge a PR, and within ~15 minutes
the machines match it, with a Telegram message saying what changed.

## Two kinds of GitOps here

| | Cluster (Kubernetes) | Machines (hosts) |
|---|---|---|
| Tool | Flux, inside the cluster | Ansible, run by `homelab-apply.timer` on node-workstation |
| Applies | `k8s/`, `clusters/` | `ansible/playbooks/site.yml` |
| When | ~1 min after a merge | ≤ 15 min after a merge, and every 6 h anyway |
| Tells you | Flux → Telegram (chapter 12) | `homelab-apply.sh` → Telegram |
| Undoes hand changes ("drift") | yes, every 10 min | yes, every 6 h |

## What the timer does (`ansible/files/homelab-apply.sh`)

```text
every 15 min:  take the lock (never two Ansible jobs at once: check, apply, backup)
               update the repo to GitHub's main (fast-forward, anonymous HTTPS)
               same commit as last good run and < 6 h ago?  -> stop, quietly
               ansible-playbook playbooks/site.yml
               changed something?  -> 🔧 "node-8gb: Garage binary" ...
               failed?             -> 🔴 once per commit, reminder every 6 h, retried every 15 min
               fixed?              -> 🟢 OK again
```

### The health check judges what was applied, not what was merged

`homelab-check` (verify, every 15 min) **does not move the checkout**: only the apply does. The check
fetches `main` just to see what is waiting:

```text
merge at 23:13 ─────► check 23:15: verifies the APPLIED commit; logs "pending: <new>, merged 2 min ago"
                      apply 23:20: fast-forwards + applies it
                      check 23:30: verifies the new commit
```

Before 2026-11-26 the check pulled `main` itself, so right after a merge it judged the machines
against a change nobody had applied yet: a false 🔴 ("192.168.40.249 is on 'wlp0s20f3', lan_link is
ethernet", 5 min before the apply switched the link). Kubernetes and Flux work the same way: a
resource's health is measured against its `observedGeneration` / `lastAppliedRevision`, not against
the newest spec. A merge still not applied after 45 min (3 apply runs) **is** reported: then the apply
is stuck or failing. [Design](../design/2026-11-26-check-applied-revision.md).

The real-world names: this is the **pull model** (each place applies the desired state itself, like
`ansible-pull`, Puppet or Chef agents), as opposed to **push** (a CI pipeline runs Ansible against
the machines; that needs a runner inside the LAN, GitHub's runners cannot reach it).

## What is applied, what is not

| In `site.yml` | Why safe unattended |
|---|---|
| `workstation-prepare.yml` | packages, tools, timers; keys and the repo are only created when missing (never recreated) |
| `access.yml` | accounts, SSH keys, sshd on the Pis; sshd config is validated (`sshd -t`) before reload |
| `ssd.yml` | mounts the SSD; **erasing still needs a person** (`-e confirm_wipe=<serial>`) |
| `backup-store.yml` | Garage, buckets, keys (from git) |

| Not in `site.yml` | Why |
|---|---|
| `k3s.yml` | a version or config change restarts k3s on **all** nodes at once = an outage. Real systems upgrade node by node, draining each first (a later step). Until then: planned, by a person |
| `rebuild`, `restore`, `backup`, `verify` | procedures and checks, not "how the machines should be" |

## Secrets are declared, not collected

Before: `backup-store.yml` let Garage *generate* keys, and a person had to copy Postgres' key into git.
Now the keys are made **once**, encrypted into git (`k8s/secrets/data/backup-s3.sops.yaml`,
`ansible/secrets/backup-store.sops.yaml`), and the playbook **imports** them into Garage. Git is the
one source of truth; no step needs a person to carry a secret. If Garage ever holds a different
secret for the same key id, the apply fails loudly instead of overwriting (Garage never reuses an id:
a new key = a new id in the file).

They were generated in a throwaway container that encrypted them at once with the age **public**
key: the plain keys were never printed or stored anywhere.

## The trade-off, plainly

A merged PR now runs **as root on every machine** within 15 minutes. That is how real systems work,
and it is exactly why `main` is protected and every change goes through a PR with CI checks
(chapter 7). The safety net: CI (syntax, task loading with the workstation's Ansible version), the
"changed" messages, the failure message, and `git revert` (the revert is applied the same way).

## Commands

```bash
systemctl list-timers homelab-apply                  # when next
ansible/files/homelab-apply.sh --force               # apply now, even without a new commit
less ~/.local/state/homelab/last-apply.log           # full output of the last run
cat ~/.local/state/homelab/apply.state               # <commit> <last run> <ok|fail> <last message>
```

`verify.yml` reports `gitops:` problems: the last apply failed, or it has not run for 7 h.

## The one manual step, once

The timer itself is installed by `workstation-prepare.yml`, so it cannot install itself: run that once
by hand (2026-11-18). From then on it keeps itself up to date like everything else.

Back to: [README](README.md)
