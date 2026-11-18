# 12. Everything through git: encrypted secrets, instant upgrades, Flux tells you

Before 2026-11-18 a change like the SLOs needed you on the server: a playbook to create a secret,
`flux reconcile` to skip a 1-hour wait, `git pull` so the checks ran the new code. Real systems
need none of that: **a merged PR is the whole change**. This chapter is how the homelab got there.

## The new flow for any change

```text
 you: branch, edit, PR ──► CI (validate, tests) ──► merge
                                                     │
 Flux (within ~1 min) ◄──────────────────────────────┘ applies it, secrets included (decrypts SOPS)
   │  helm-controller upgrades charts at once when their values change (watch label)
   └─► Telegram: "Kustomization/monitoring: configured ..." or "... failed: <why>"
 node-workstation timer (every 15 min): git pull, then verify.yml ─► Telegram if a problem
```

Nothing to type on the server. The only thing kept by a person is the **age key** (below).

## 1. Secrets in git, encrypted (SOPS + age)

A secret in a **public** repo sounds wrong, and it is, unless it is **encrypted**:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: redis              # readable: what it is
  namespace: data
type: Opaque
data:
  password: ENC[AES256_GCM,data:k3J...,iv:...,tag:...,type:str]   # encrypted: the value
sops:
  age:
    - recipient: age1...   # the PUBLIC key it was encrypted for
      enc: |
        -----BEGIN AGE ENCRYPTED FILE-----
        ...
```

| Piece | What |
|---|---|
| **age** | a small, modern encryption tool. A key pair: the **public** key (`age1...`) encrypts, only the **private** key decrypts. Already used for the backups |
| **SOPS** | encrypts only the *values* of a YAML file (here `data`, `stringData`, and a Provider's `channel`), so names and structure stay readable and `git diff` shows *which* secret changed |
| `.sops.yaml` | repo root: which files, which fields, which public key |
| Secret `flux-system/sops-age` | the **private** key, given to Flux so it can decrypt when it applies `k8s/secrets/` and `k8s/notifications/` |

Where the private key lives: your **password manager**, `~/.config/sops/age/keys.txt` on
node-workstation (as `ops`), and the Secret in the cluster. Never in git.

> **Security, important.** This repo is public, so the encrypted files are readable by anyone,
> forever (git history). They are safe exactly as long as the age private key stays secret. If the
> key ever leaks, re-encrypting is **not** enough: an attacker can decrypt the old versions from the
> history. Then **rotate every secret** (new Redis password, new tunnel credentials, new GHCR token,
> new Telegram bot token, ...), make a new age key, and re-seal everything.

### What is in git, what is not

| Secret | In git? | Why |
|---|---|---|
| tunnel-creds, redis, ticketlite, ghcr-pull, grafana-admin, grafana-lan-tls | yes (`k8s/secrets/`) | static values a rebuild needs |
| alertmanager-config, Flux's telegram + Provider | yes, written by `playbooks/alerting.yml` from `alerting.env` | |
| `flux-system/flux-system` (Flux's git key) | **no** | Flux needs it to *read* git: chicken and egg. In the backup |
| `flux-system/sops-age` (the age key) | **no** | it decrypts everything. Password manager; `restore.yml` creates it on a rebuild |
| `data/pg-app`, `apps/app-env` | **no** | the database password is generated per cluster (CNPG), `app-env` is built from it |

### Doing things

```bash
# on node-workstation, as ops, in the repo (sops finds the key in ~/.config/sops/age/keys.txt)
sops edit k8s/secrets/data/redis.sops.yaml           # change a value: decrypted in an editor, re-encrypted on save
scripts/secrets/seal.sh apps/ghcr-pull               # a secret you (re)created in the cluster -> into git
cd ansible && ansible-playbook playbooks/alerting.yml   # Telegram bot/chat changed in alerting.env -> files
git switch -c secret-change && git add -A k8s/secrets k8s/notifications && git commit -m "..." && git push -u origin HEAD
# then a PR; after the merge Flux applies it
```

Check before committing: `git diff` must show only `ENC[...]` values. CI's `gitleaks` check is the
second guard.

## 2. Charts upgrade at once

The monitoring chart (`HelmRelease vm`) used to look at its values once an hour. Its values
ConfigMap now has the label `reconcile.fluxcd.io/watch: Enabled` (`k8s/releases/kustomization.yaml`):
helm-controller upgrades **as soon as** the ConfigMap changes. No more `flux reconcile`.

## 3. Flux reports to Telegram

`k8s/notifications/`: an **Alert** (which events) and a **Provider** (where: your Telegram bot and
chat, encrypted). You get a message when a Kustomization applies a change or fails, when a
HelmRelease upgrades or fails, when git or a chart repository cannot be read. Flux sends nothing
when nothing changed, so it stays quiet.

## 4. The checks always run the newest code

`homelab-check.sh` (the 15-minute timer) starts with `git pull --ff-only`: `verify.yml` and the
Ansible code on node-workstation always match `main`. `--ff-only` never overwrites local changes; if
it cannot update, that is reported as a problem (Telegram), and the check runs with the old code.

## The one-time switch (2026-11-18)

`scripts/secrets/bootstrap.sh`, run once on node-workstation as `ops`: wrote `.sops.yaml`, created
`flux-system/sops-age`, encrypted the six live secrets into `k8s/secrets/`, ran `alerting.yml`,
pushed a branch. Nothing in the cluster changed except the key Secret: after the merge Flux applied
the same values it found running, and from then on owns them.

## How real systems compare

| Here | Larger systems |
|---|---|
| SOPS + age, key in a password manager | same (common with Flux), or a **vault** (HashiCorp Vault, cloud secret managers) read by the External Secrets Operator, with automatic rotation and audit logs |
| Flux → Telegram | Flux / Argo CD → Slack, PagerDuty |
| timer pulls + verifies | the same, plus **progressive delivery** (Flagger, Argo Rollouts): roll out to a few pods, watch the SLOs, roll back by itself |

Back to: [README](README.md)
