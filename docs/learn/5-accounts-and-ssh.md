# 5. Accounts and SSH

## SSH keys in two minutes

An **SSH key** is a pair of files:

- the **private key** (`~/.ssh/id_homelab`): your secret, never leaves your machine;
- the **public key** (`~/.ssh/id_homelab.pub`): safe to share; you put it on the server in
  `~/.ssh/authorized_keys`.

When you connect, the server checks that you hold the private key matching one of its public
keys. No password is sent.

A **passphrase** encrypts the private key file: a copied file is useless without it. To avoid
typing it every time, the **ssh-agent** keeps the unlocked key in memory for a while
(`ssh-add ~/.ssh/id_homelab`; here: 8 hours).

`authorized_keys` lines can carry **restrictions** before the key:

```
from="192.168.40.249",no-agent-forwarding,no-port-forwarding,no-X11-forwarding ssh-ed25519 AAAA... node-workstation->homelab
```

`from=` = only accept this key from that IP. The others forbid tunnelling tricks through the
connection.

## Logging in vs. becoming root: the four words that confuse everyone

| Term | Meaning | Example |
|---|---|---|
| **passwordless login** | you log in **without typing a password**: your SSH key proves who you are | `ssh node-workstation` from the Mac asks nothing |
| **sudo** | "run this one command as **root**" (the administrator). Normal accounts cannot change the system; `sudo` lets an allowed account do it | `sudo apt install tmux` |
| **sudo with password** | sudo first asks **your** password. Someone who took over your session still cannot become root without knowing it | `ethan@node-workstation` |
| **passwordless sudo** | the account may use sudo **without** a password. Needed for automation (it cannot type), and only acceptable for an account nobody browses with or logs into casually | `ops@<every host>`, and (your choice) `ethan@<Pi>` |
| **`sudo -iu ops`** | "become the user `ops`, with a full login shell". `-u ops` = as user ops, `-i` = a login shell (ops's home folder, settings, PATH). It asks **your** password once; then you work as `ops` until you type `exit` | `ethan@node-workstation $ sudo -iu ops` → `ops@node-workstation $` |

So you prove who you are **once** (your password at `sudo -iu ops`), and from there automation runs
without prompts. That is the same idea as real systems: humans authenticate, services run unattended.

## The accounts (each machine has its own; same name = same role everywhere)

| Account | Logs in with | sudo | Used for |
|---|---|---|---|
| `maihien@Mac` | Mac password | – | `ssh node-workstation` only |
| **`ethan@node-workstation`** | desktop password, or the Mac key (`id_homelab_workstation`) | **with password** | **you**: desktop, browser, SSH; `sudo -iu ops` to run automation |
| **`ops@node-workstation`** | **no login** (no password, no SSH key): only `sudo -iu ops` from ethan | passwordless | **automation**: Ansible, backups, the kubeconfig, the age key, the repo checkout |
| `ethan@<Pi>` | only `id_homelab` (passphrase, in ethan's home), only from node-workstation's IP | passwordless (your choice) | you: debugging and learning on a Pi |
| `ops@<Pi>` | only `id_ops` (no passphrase, in ops's home), only from node-workstation's IP; password locked | passwordless | Ansible |

```
maihien@Mac --ssh (id_homelab_workstation)--> ethan@node-workstation --ssh (id_homelab)--> ethan@node-8gb
                                                       |
                                                  sudo -iu ops (your password)
                                                       v
                                               ops@node-workstation --ssh (id_ops, Ansible)--> ops@node-8gb
```

Write accounts as `user@host` when it matters which one you mean.

### Why a separate `ops` account, also on the workstation?

- **Humans and automation are kept apart**: your browser and desktop run as `ethan`; the keys that
  can change the whole homelab (kubeconfig, `id_ops`, age key) belong to `ops`, which never browses
  and never logs in. A bad web page in your session cannot read them without your password.
- **Why `id_ops` has no passphrase**: scheduled jobs cannot type one. The Pis accept it only from
  node-workstation's IP, and only `ops` (or root) can read it.

### Fixed account numbers (UID/GID)

Linux stores file owners as **numbers**, not names. The same number must mean the same account
everywhere, or a copied file changes owner. Fixed here (inventory `admin_uid`, `automation_uid`):
**`ethan` = 1000** and **`ops` = 2000** on every host.

## Things you will do

```bash
# from the Mac
ssh node-workstation                    # you are ethan (Keychain unlocks your Mac key)

# as ethan on node-workstation
ssh-add ~/.ssh/id_homelab               # once per 8 h, to use the Pis as yourself
                                        # "Could not open a connection to your authentication agent"?
                                        #   -> source ~/.bashrc  (or log in again)
ssh node-8gb                            # = ethan@node-8gb

# automation: switch to ops (asks YOUR password once)
sudo -iu ops
cd ~/workspace/source/homelab && git pull
cd ansible && ansible-playbook playbooks/verify.yml
exit                                    # back to ethan
```

## If you lose the keys

The Pis accept **only** `id_homelab` and `id_ops`, **only** from node-workstation's IP. If
node-workstation's disk dies, restore both files from the **password manager** onto the
reinstalled machine (same IP): `id_homelab` into ethan's `~/.ssh/`, `id_ops` into ops's, before running any playbook. Without them, only a keyboard and
monitor plugged into each Pi can add a new key.

## The SSH servers

Every machine accepts **keys only, no root login** (`/etc/ssh/sshd_config.d/10-homelab.conf`).
On the Pis this file must sort before Raspberry Pi OS's `50-cloud-init.conf`, which turns passwords
on: sshd uses the first value it reads.

## Where this is going (world-class practice)

| Next step | What changes |
|---|---|
| SSH certificates | the Pis trust one SSH CA; you get a certificate valid for hours instead of a key valid forever |
| no human SSH to the Pis ("production") | only `ops` logs in; you debug with `kubectl debug node/...`; emergencies via console |

Details and decisions: [K3S-GUIDE.md section 10.5](../../K3S-GUIDE.md).

Next: [6. Daily operations](6-daily-operations.md)
