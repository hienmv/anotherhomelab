# 6. Daily operations

All commands run on **node-workstation** (`ssh node-workstation` from the Mac), in the repo:
`cd ~/workspace/source/homelab && git pull`.

## Is everything OK?

```bash
cd ansible && ANSIBLE_BECOME_ASK_PASS=false ansible-playbook playbooks/verify.yml
```

About 30 seconds. Every host must end with `all checks passed`. Otherwise it lists every problem
with a reason, e.g. `1 problem(s) on node-workstation: pod: apps/notes-… Pending`. What each
check means: [docs/testing.md](../testing.md).

Quick looks:

```bash
kubectl get nodes                 # 4 x Ready?
kubectl get pods -A               # anything not Running?
flux get kustomizations           # all Ready?
```

## Make a backup

```bash
cd ansible && ANSIBLE_BECOME_ASK_PASS=false ansible-playbook playbooks/backup.yml
```

Writes `~/homelab-backup/homelab-<date>.tar.gz.age` (secrets + Postgres, encrypted with the age
key). Do it before anything risky. ⚠️ Today the backups live on the same disk as the database:
scheduled off-site backups are the next roadmap item.

## Deploy a change

| Change | How |
|---|---|
| an app (`apps/<app>/`) | commit + push; CI builds, tests, commits the new tag; Flux deploys (3–5 min) |
| cluster config (`k8s/`) | commit + push; check `validate` on GitHub; Flux applies within a minute |
| site values (domain, ...) | edit `clusters/homelab/site-settings.yaml`, commit + push (chapter 4) |
| machines (packages, users, network, k3s) | edit the playbook/inventory, run the playbook |

After each: `verify.yml`.

## Read logs

```bash
kubectl -n apps logs deploy/notes --tail=50              # an app
kubectl -n apps logs deploy/ticketlite-api -f            # follow live (Ctrl+C to stop)
kubectl -n data logs pg-1 -c postgres --tail=50          # Postgres
kubectl -n flux-system logs deploy/kustomize-controller --since=10m   # why Flux is unhappy
ANSIBLE_BECOME_ASK_PASS=false ansible node-8gb -b -a 'journalctl -u k3s-agent -n 50'   # a node
journalctl -k -b | grep DENIED                           # security blocks on this machine
```

Grafana shows metrics: `https://grafana.<domain>` (Cloudflare login) or `https://grafana.lan` on
node-workstation.

## Common fixes

| Symptom | Look at | Usual fix |
|---|---|---|
| a site answers 502/404 | `kubectl -n apps get pods`, then its logs | wait for a rollout, or revert the last commit |
| a pod `CrashLoopBackOff` | `kubectl -n <ns> logs <pod> --previous` | read the last lines; often a missing Secret or config |
| a pod `Pending` | `kubectl -n <ns> describe pod <pod>` (Events at the bottom) | node full, or no node matches its labels |
| `flux get kustomizations` not Ready | the MESSAGE column; `flux logs` | fix the file named in the message (often a `${variable}`) |
| Postgres not ready | `kubectl -n data get cluster pg`, its logs | restart the pod: `kubectl -n data delete pod pg-1` (data is kept on its volume) |
| a node `NotReady` | ping it; `ansible <node> -m ping` | power/cable/Wi-Fi; the node rejoins by itself |

Restarting an app safely: `kubectl -n apps rollout restart deploy/<name>`.

## Bigger procedures (in the guide)

| Procedure | Where |
|---|---|
| reinstall node-workstation | [K3S-GUIDE.md 10.4](../../K3S-GUIDE.md) |
| rebuild the whole cluster from a backup | `phase2-migrate.yml --tags teardown,k3s,restore` ([K3S-GUIDE.md 10.3](../../K3S-GUIDE.md)) |
| add a public subdomain for a new app | [docs/domains.md](../domains.md) |
| change who can log in | `access.yml` ([chapter 5](5-accounts-and-ssh.md)) |

## When something breaks badly

1. **Don't panic, don't rush**: the data is in Postgres on its volume and in the backups.
2. **Read** before changing: `verify.yml`, the logs above.
3. **Change one thing at a time**, through code where possible, and write down what you did.
4. Afterwards: a short postmortem in `docs/postmortems/` (what happened, why, what changes).
   Example: [the 2026-11-11 migration](../postmortems/2026-11-11-phase2-migration.md).

Next: [Glossary](glossary.md)
