# 2. Ansible

## What Ansible is

Ansible is a tool that **sets up machines from a description**. Instead of logging in to each Pi
and typing commands, you write down the end state ("this file has this content", "this user exists",
"k3s version X is installed") and Ansible makes every machine match it, over SSH.

Two properties make it safe:

- **Idempotent**: running the same playbook twice changes nothing the second time. Every task says
  `ok` (already right) or `changed` (fixed now). Re-running after a failure is always safe.
- **Agentless**: nothing to install on the Pis except SSH and Python, which they have.

## The parts, using this repo

```
ansible/
  ansible.cfg            settings: where the inventory is, log file, ask for the sudo password
  inventory.yml          WHICH machines exist, their IPs, roles, users    -> "the list"
  group_vars/all.yml     values shared by all machines (paths, versions)   -> "the settings"
  playbooks/*.yml        WHAT to do                                       -> "the recipes"
  files/                 scripts and files the playbooks copy or run
  logs/ansible.log       every run is recorded here (not in git)
```

### Inventory: the list of machines

```yaml
pis:                              # a group
  hosts:
    node-8gb:                     # a host (machine)
      static_ip: 192.168.40.250   # a host variable
      k3s_labels: [role=apps]
```

Groups let a playbook say "do this on all Pis" (`hosts: pis`). Groups in this repo: `workstation`,
`pis`, `k3s_servers`, `k3s_agents`, `k3s` (all cluster nodes).

### Playbook, play, task, module

```yaml
- name: Base system on the workstation     # a PLAY: a list of tasks for some hosts
  hosts: workstation
  become: true                             # run tasks as root (sudo)
  tasks:
    - name: Base packages                  # a TASK: one step
      ansible.builtin.apt:                 # a MODULE: the tool that does the step
        name: [curl, git, jq]
        state: present                     # the end state, not a command
```

A **playbook** is a file with one or more plays. A **module** is a building block: `apt` installs
packages, `copy` writes a file, `user` creates users, `command`/`shell` run a command, `assert`
checks something.

### Variables and templates

`{{ ... }}` is a template: Ansible fills it in before running. Example from `access.yml`:

```yaml
key_options: 'from="{{ workstation_ip }}",no-agent-forwarding'
```

`workstation_ip` comes from the inventory (`static_ip` of node-workstation), so the IP is written
only once, in `inventory.yml`.

### become (sudo)

`become: true` means "run as root". Ansible runs as the automation user `ops`, which has
passwordless sudo on every host, so it never asks. You prove who you are once, when you switch to it
with `sudo -iu ops` (chapter 5).

### Tags

Big playbooks have tags so you can run only a part: `--tags k3s,restore`.

## The playbooks in this repo

| Playbook | What it does | When to run it | Prompts |
|---|---|---|---|
| `workstation-prepare.yml` | sets up node-workstation: packages, never sleep, static IP, tools, the `ops` account, keys (yours and ops's), repo clone, kubeconfig | after a reinstall, or when you change its settings | key passphrase (Enter if keys exist) |
| `access.yml` | accounts on the Pis: `ops` and `ethan`, fixed IDs, IP-restricted keys, SSH keys only, Raspberry Pi Connect removed | after changing access rules | none |
| `k3s.yml` | installs/updates k3s on all nodes from the inventory | new k3s version, new node | none |
| `backup.yml` | encrypted backup of secrets + Postgres | before anything risky; regularly | none |
| `restore.yml` | rebuilds Flux, secrets and Postgres in a fresh cluster from a backup | after a rebuild | none |
| `verify.yml` | read-only health check of everything | after every change, whenever unsure | none |
| `phase2-migrate.yml` | the full rebuild: backup, teardown, SD swap, k3s, restore | big migrations, the rebuild drill | `REBUILD` |

## How to run one

On node-workstation **as `ops`** (from the Mac: `ssh node-workstation`, then `sudo -iu ops`),
ideally inside `tmux` so it survives a dropped connection:

```bash
sudo -iu ops                              # your (ethan's) password, once
tmux new -A -s ops
cd ~/workspace/source/homelab && git pull
cd ansible
ansible-playbook playbooks/verify.yml
ansible-playbook playbooks/k3s.yml
```

Useful flags:

| Flag | Meaning |
|---|---|
| `--check --diff` | dry run: show what would change, change nothing |
| `--tags x,y` | only the tasks with these tags |
| `--limit node-8gb` | only this machine |
| `-e name=value` | set a variable for this run |
| `-v`, `-vvv` | more output, for debugging |

Ad-hoc commands (one module on some hosts, no playbook):

```bash
ansible pis -m ping                         # can Ansible reach them?
ansible pis -a 'uptime'                     # run a command
ansible node-8gb -b -a 'journalctl -u k3s-agent -n 30'   # as root
```

## Reading the output

```
TASK [Base packages] ****
ok: [node-workstation]                  # already right, nothing done
changed: [node-8gb]                     # Ansible fixed it now
skipping: [node-1gb]                    # a `when:` condition was false
fatal: [node-4gb]: FAILED! => {...}     # error: read the message, fix, re-run

PLAY RECAP ****
node-8gb : ok=12 changed=1 unreachable=0 failed=0      # the summary that matters
```

`verify.yml` ends with one line per host: `all checks passed`, or `N problem(s) on <host>: ...`.

## Mistakes we actually made (and what they teach)

| Error | Cause | Lesson |
|---|---|---|
| `mapping values are not allowed` | a `:` in a task name (`name: Automation user (no password: ...)`) | in YAML, quote text that contains `: ` |
| `Timed out waiting for become ... password prompt` | Ubuntu 26.04's `sudo-rs` has a different prompt | the OS matters; test on the real target |
| `Need bcrypt module` | a Python library missing on Ubuntu 24.04 | packages differ between OS versions; list them in the playbook |
| `'inventory_dir' is undefined` | the built-in `localhost` has no inventory file | build paths from something always defined (`repo_root`) |
| a renamed file (`grafana-lan-tls-…` instead of `homelab-…`) | a shell variable reused inside a loop | test with real runs, not only syntax checks |

Next: [3. GitOps with Flux](3-gitops-flux.md)
