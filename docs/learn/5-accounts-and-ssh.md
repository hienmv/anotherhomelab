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

## The accounts (each machine has its own; same name ≠ same account)

| Account | Logs in with | sudo | Used for |
|---|---|---|---|
| `maihien@Mac` | Mac password | – | `ssh node-workstation` only |
| `hien@node-workstation` | desktop password, or the Mac key (`id_homelab_workstation`) | with password | **you**, the admin; Ansible runs here |
| `hien@<Pi>` | only `id_homelab` (passphrase), only from node-workstation's IP | passwordless | you, for debugging and learning |
| `ops@<Pi>` | only `id_ops` (**no** passphrase), only from node-workstation's IP; password locked | passwordless | **Ansible** |

```
maihien@Mac --(id_homelab_workstation)--> hien@node-workstation --(id_homelab)--> hien@node-8gb
                                                  └-------------(id_ops, Ansible)--> ops@node-8gb
```

Write accounts as `user@host` when it matters which one you mean.

### Why a separate `ops` account?

- **Humans and automation are kept apart**: logs show who did what; your personal key keeps its
  passphrase while automation can run unattended.
- **Why `id_ops` has no passphrase**: scheduled jobs cannot type one. The risk is small because
  (1) the Pis accept it only from node-workstation's IP, and (2) whoever controls node-workstation
  already controls the Pis through Kubernetes anyway (the cluster-admin kubeconfig).

### Fixed account numbers (UID/GID)

Linux stores file owners as **numbers**, not names. If `hien` is 1001 on one machine and 1001 is
`ops` on another, a copied file changes owner. So service accounts get a fixed number:
`ops` = **2000** on every Pi (`automation_uid` in the inventory). Human IDs still differ
(`hien` = 1000 on the Pis, 1001 on node-workstation, where 1000 is `ethan`); they get aligned at the
next OS install.

## Things you will do

```bash
# from the Mac
ssh node-workstation                          # Keychain unlocks your Mac key automatically

# on node-workstation
ssh-add ~/.ssh/id_homelab                     # once per 8 h, before using the Pis as hien
ssh node-8gb                                  # = hien@node-8gb
ANSIBLE_BECOME_ASK_PASS=false ansible pis -m ping   # Ansible as ops (no agent needed)
```

## If you lose the keys

The Pis accept **only** `id_homelab` and `id_ops`, **only** from node-workstation's IP. If
node-workstation's disk dies, restore both files from the **password manager** onto the
reinstalled machine (same IP) before running any playbook. Without them, only a keyboard and
monitor plugged into each Pi can add a new key.

## The SSH servers

Every machine accepts **keys only, no root login** (`/etc/ssh/sshd_config.d/10-homelab.conf`).
On the Pis this file must sort before Raspberry Pi OS's `50-cloud-init.conf`, which turns passwords
on: sshd uses the first value it reads.

## Where this is going (world-class practice)

| Next step | What changes |
|---|---|
| automation account on node-workstation too | `id_ops`, the kubeconfig and scheduled jobs belong to `ops@node-workstation`, not to your personal account |
| SSH certificates | the Pis trust one SSH CA; you get a certificate valid for hours instead of a key valid forever |
| no human SSH to the Pis ("production") | only `ops` logs in; you debug with `kubectl debug node/...`; emergencies via console |

Details and decisions: [K3S-GUIDE.md section 10.5](../../K3S-GUIDE.md).

Next: [6. Daily operations](6-daily-operations.md)
