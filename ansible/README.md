# Ansible

Automation for the homelab hosts. Plan and context: [K3S-GUIDE.md → section 10](../K3S-GUIDE.md).

| File | What |
|---|---|
| `inventory.yml` | hosts (`sv9`, `pi4-8gb`, `pi4-4gb`, `pi3`), static IPs |
| `group_vars/all.yml` | pinned tool versions, repo, current k3s server IP |
| `playbooks/sv9-prepare.yml` | phase 0: sv9 base system + admin workstation (no cluster change) |

Run from the `ansible/` folder, on the Mac (today) or on sv9 (afterwards; it detects itself and runs
locally). The Pis' SSH user and key come from `~/.ssh/config` on the machine running Ansible.

```bash
brew install ansible                     # Mac; on sv9 the playbook installs it with apt
ansible pis -m ping                      # connectivity
ansible-playbook playbooks/sv9-prepare.yml --check --diff   # dry run
ansible-playbook playbooks/sv9-prepare.yml
```

First run only: `-e sv9_ip=<current Wi-Fi IP> -K` (sv9 has no static IP and no passwordless sudo yet).
