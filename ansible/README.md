# Ansible

Automation for the homelab hosts. Plan and context: [K3S-GUIDE.md → section 10](../K3S-GUIDE.md).

| File | What |
|---|---|
| `../clusters/<cluster>/site-settings.yaml` | **site values** (domain, repo, registry, Cloudflare IDs), shared with Flux and CI; picked by `cluster_name` |
| `inventory.yml` | hosts (`node-workstation`, `node-8gb`, `node-4gb`, `node-1gb`), static IPs, k3s roles and per-node settings |
| `group_vars/all.yml` | `cluster_name`, paths derived from `admin_user` (`admin_home`, `kubeconfig`, `backup_dir`, `age_key`, `repo_dest`), tool versions, k3s server |
| `playbooks/workstation-prepare.yml` | phase 0: node-workstation base system + admin workstation (no cluster change) |
| `playbooks/backup.yml` + `files/backup.sh` | phase 1 / any time: Secrets + PostgreSQL, age-encrypted, on node-workstation (+ a copy on the controller if that is another machine) |
| `playbooks/k3s.yml` | install/update k3s on group `k3s` (server `k3s_servers`, agents `k3s_agents`) |
| `playbooks/restore.yml` | new cluster from a backup: Flux, Secrets, PostgreSQL (row counts checked) |
| `playbooks/phase2-migrate.yml` | phase 2: backup → teardown → Pi identities / SD swap → k3s → restore |
| `playbooks/access.yml` | accounts on the Pis: automation user `ops` (IP-restricted key, passwordless sudo), SSH keys only |
| `playbooks/verify.yml` | read-only health checks per layer (catalog: [docs/testing.md](../docs/testing.md)) |
| `files/net-static.sh` | static IP on the `lan_link` link (used by workstation-prepare) |

Run from the `ansible/` folder **on node-workstation as `ops`** (`sudo -iu ops` from your account,
your password once), in `tmux` for long runs. `ops` has passwordless sudo and an unattended key to
the Pis, so no prompts. Accounts explained: [docs/learn/5](../docs/learn/5-accounts-and-ssh.md). Ansible connects by IP with `~/.ssh/id_homelab` (Pis) and `~/.ssh/id_homelab_workstation` (workstation, from the Mac).

```bash
brew install ansible                     # Mac; on node-workstation the playbook installs it with apt
ansible pis -m ping                      # connectivity
ansible-playbook playbooks/workstation-prepare.yml --check --diff   # dry run
ansible-playbook playbooks/workstation-prepare.yml
```

First run only: `-e workstation_ip=<current Wi-Fi IP>` (no static IP yet). Every run asks the node-workstation sudo
password (`become_ask_pass` in `ansible.cfg`; node-workstation keeps its sudo password). Commands that need no
sudo can skip the prompt: `ansible pis -m ping`.

## Configuration, not code

Nothing user-, site- or device-specific is written in the playbooks or scripts:

| Value | Where it is set |
|---|---|
| domain, Git repo, image registry, Cloudflare IDs | `clusters/<cluster>/site-settings.yaml` |
| admin account (`admin_user`), IPs, Wi-Fi profile, RAM/SD, k3s roles | `inventory.yml` |
| home paths, kubeconfig, backup folder, age key path | derived in `group_vars/all.yml` |
| secrets | Kubernetes Secrets / encrypted backups (later SOPS), never here |

Another site or user: copy `clusters/homelab/` to `clusters/<name>/`, edit its `site-settings.yaml`, set
`cluster_name` and the inventory. Check the resolved values with a `debug` play before running anything.
