# Ansible

Automation for the homelab hosts. Plan and context: [K3S-GUIDE.md → section 10](../K3S-GUIDE.md).

| File | What |
|---|---|
| `inventory.yml` | hosts (`node-workstation`, `node-8gb`, `node-4gb`, `node-1gb`), static IPs, k3s roles and per-node settings |
| `group_vars/all.yml` | pinned tool versions, repo, current k3s server IP |
| `playbooks/workstation-prepare.yml` | phase 0: node-workstation base system + admin workstation (no cluster change) |
| `playbooks/backup.yml` + `files/backup.sh` | phase 1 / any time: Secrets + PostgreSQL, age-encrypted, on node-workstation (+ a copy on the controller if that is another machine) |
| `playbooks/k3s.yml` | install/update k3s on group `k3s` (server `k3s_servers`, agents `k3s_agents`) |
| `playbooks/restore.yml` | new cluster from a backup: Flux, Secrets, PostgreSQL (row counts checked) |
| `playbooks/phase2-migrate.yml` | phase 2: backup → teardown → Pi identities / SD swap → k3s → restore |
| `playbooks/verify.yml` | read-only health checks per layer (catalog: [docs/testing.md](../docs/testing.md)) |
| `files/net-static.sh` | static IP on the `lan_link` link (used by workstation-prepare) |

Run from the `ansible/` folder **on node-workstation** (it detects itself and runs locally), in `tmux`
for long runs. Unlock the Pi key once per 8 h: `ssh-add ~/.ssh/id_homelab`. Since 2026-11-12 only
node-workstation's key opens the Pis. Ansible connects by IP with `~/.ssh/id_homelab` (Pis) and `~/.ssh/id_homelab_workstation` (workstation, from the Mac).

```bash
brew install ansible                     # Mac; on node-workstation the playbook installs it with apt
ansible pis -m ping                      # connectivity
ansible-playbook playbooks/workstation-prepare.yml --check --diff   # dry run
ansible-playbook playbooks/workstation-prepare.yml
```

First run only: `-e workstation_ip=<current Wi-Fi IP>` (no static IP yet). Every run asks the node-workstation sudo
password (`become_ask_pass` in `ansible.cfg`; node-workstation keeps its sudo password). Commands that need no
sudo can skip the prompt: `ANSIBLE_BECOME_ASK_PASS=false ansible pis -m ping`.
