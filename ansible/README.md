# Ansible

Automation for the homelab hosts. Plan and context: [K3S-GUIDE.md → section 10](../K3S-GUIDE.md).

| File | What |
|---|---|
| `inventory.yml` | hosts (`sv9`, `pi4-8gb`, `pi4-4gb`, `pi3`), static IPs |
| `group_vars/all.yml` | pinned tool versions, repo, current k3s server IP |
| `playbooks/sv9-prepare.yml` | phase 0: sv9 base system + admin workstation (no cluster change) |
| `playbooks/backup.yml` + `files/backup.sh` | phase 1 / any time: Secrets + PostgreSQL, age-encrypted, on sv9 + a copy on the Mac |
| `playbooks/k3s.yml` | install/update k3s on group `k3s` (server `k3s_servers`, agents `k3s_agents`) |
| `playbooks/restore.yml` | new cluster from a backup: Flux, Secrets, PostgreSQL (row counts checked) |
| `playbooks/phase2-migrate.yml` | phase 2: backup → teardown → Pi identities / SD swap → k3s → restore |
| `files/net-static.sh` | static IP on the `lan_link` link (used by sv9-prepare) |

Run from the `ansible/` folder, on the Mac (today) or on sv9 (afterwards; it detects itself and runs
locally). The Pis' SSH user and key come from `~/.ssh/config` on the machine running Ansible.

```bash
brew install ansible                     # Mac; on sv9 the playbook installs it with apt
ansible pis -m ping                      # connectivity
ansible-playbook playbooks/sv9-prepare.yml --check --diff   # dry run
ansible-playbook playbooks/sv9-prepare.yml
```

First run only: `-e sv9_ip=<current Wi-Fi IP>` (no static IP yet). Every run asks the sv9 sudo
password (`become_ask_pass` in `ansible.cfg`; sv9 keeps its sudo password). Commands that need no
sudo can skip the prompt: `ANSIBLE_BECOME_ASK_PASS=false ansible pis -m ping`.
