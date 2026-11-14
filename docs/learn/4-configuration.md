# 4. Configuration, not code

## The principle

**Code** says *how* things work. **Configuration** says *for whom / where*: the domain, the IPs,
the user names. Keep them apart, and the same code runs for another person, another site, another
set of machines, by changing only the configuration. (This is factor III of the
[Twelve-Factor App](https://12factor.net/config).)

In this repo **no code contains your domain, IPs or user names**. CI checks that the configuration
is valid on every change.

## Where every value lives

| Value | File | Used by |
|---|---|---|
| domain, Git repo, image registry, Cloudflare tunnel ID, time zone | `clusters/homelab/site-settings.yaml` | Flux (`${DOMAIN}` in manifests), Ansible (`site.DOMAIN`), CI (app builds) |
| machines, IPs, Wi-Fi profile, RAM/SD size, k3s roles, user names, account IDs | `ansible/inventory.yml` | Ansible |
| paths (kubeconfig, backups, keys), tool versions, k3s version | `ansible/group_vars/all.yml` (mostly derived from the two above) | Ansible |
| passwords, tokens, tunnel credentials | Kubernetes Secrets (never in git); exported encrypted by `backup.yml` | the cluster |
| your name, links on the landing page | `apps/landing/src/data/profile.json` | content, not configuration |

## How each tool reads its configuration

```
site-settings.yaml ──► Flux postBuild.substituteFrom ──► ${DOMAIN} in k8s/ manifests
        │
        ├────────────► Ansible group_vars:  site: "{{ (lookup('file', ...) | from_yaml).data }}"
        │
        └────────────► CI build-image.yml (yq) ──► --build-arg SITE_DOMAIN ──► astro.config.mjs
inventory.yml ───────► Ansible (hosts, IPs, users)
```

## Example: change the domain

1. Buy/set up the new domain in Cloudflare and point its DNS at the tunnel ([docs/domains.md](../domains.md)).
2. Edit one line: `DOMAIN:` in `clusters/homelab/site-settings.yaml`.
3. Commit, push. Then:
   - `validate` checks the value,
   - the app workflows rebuild landing/notes with the new domain (it is baked into their pages),
   - Flux re-renders every Ingress, the tunnel routes, Grafana's URL.
4. `verify.yml` (its URL checks are built from `DOMAIN` too).

## Example: a Pi gets a new IP

1. Edit `static_ip:` of that node in `ansible/inventory.yml`.
2. Apply it on the machine itself (its network settings) and re-run `access.yml` (the IP
   restrictions on the keys use the workstation IP) and `k3s.yml` (node IP).
3. `verify.yml`.

## What protects the configuration

| Check | Where |
|---|---|
| every key present and well-formed (domain, UUID, time zone, ...) | `scripts/validate-config.py`, CI job `validate` |
| values consistent (registry = repo owner, Git URL = repo slug, bootstrap URL = slug) | same |
| every manifest renders, no unknown `${x}` | `scripts/render-k8s.py`, CI |
| no raw `${KEY}` in the running cluster | `verify.yml --tags cluster` |
| a missing value fails the app build ("SITE_DOMAIN is not set") | `astro.config.mjs` |

## The one exception: the Flux bootstrap

`clusters/homelab/flux-system/gotk-sync.yaml` contains the Git URL written out. Flux reads it
**before** any variable exists, so it cannot use `${...}`. It is per-cluster configuration by
nature, and CI checks that it matches `GIT_REPO_SLUG`.

Next: [5. Accounts and SSH](5-accounts-and-ssh.md)
