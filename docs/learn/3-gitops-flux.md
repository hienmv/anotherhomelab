# 3. GitOps with Flux

## The idea

**GitOps** = the cluster runs what is in git, nothing else.

- You never "install" something by hand with `kubectl apply`.
- You change a file in `k8s/`, commit, push. **Flux** (a program inside the cluster) notices within
  about a minute and makes the cluster match.
- If someone changes the cluster by hand, Flux puts it back to what git says.

Why: git becomes the history of every change (who, when, why), a rebuild produces exactly the
same cluster, and rolling back is `git revert`.

## Kubernetes in one paragraph

Kubernetes runs **containers** (a program plus everything it needs, packaged as an **image**).
You describe what you want in **manifests** (YAML files): a **Deployment** ("run 2 copies of this
image"), a **Service** ("give them one internal address"), an **Ingress** ("send
`notes.<domain>` there"), a **ConfigMap**/**Secret** (settings, passwords). Kubernetes keeps
reality equal to the description. A running copy is a **Pod**. A **namespace** groups related
things (`apps`, `data`, `monitoring`, ...).

## The folders

```
clusters/homelab/                 the ENTRY POINT Flux reads
  flux-system/                    Flux itself (installed once) + where the repo is (gotk-sync.yaml)
  site-settings.yaml              this site's values (domain, registry, ...), see chapter 4
  platform.yaml                   one Flux "Kustomization" per k8s/ folder, with their order
k8s/
  namespaces/ releases/ infra/ data/ apps/ monitoring/     what gets deployed
```

`platform.yaml` defines the order, because some things need others first:

```
namespaces -> releases (Helm charts: Postgres operator, monitoring) -> data (Postgres, Redis) -> apps
namespaces -> infra (Cloudflare tunnel)
releases   -> monitoring
```

## Kustomize

Each `k8s/<folder>/kustomization.yaml` lists its files and small changes. Example from
`k8s/apps/notes/kustomization.yaml`:

```yaml
resources:
  - notes.yaml
  - ingress.yaml
images:
  - name: notes                          # the Deployment says `image: notes`
    newName: ${IMAGE_REGISTRY}/notes     # registry from site-settings
    newTag: sha-90de57e                  # CI writes the new version here
```

See what Kustomize produces: `kubectl kustomize k8s/apps/notes`.

## Variables: `${DOMAIN}` and friends

Manifests contain `${DOMAIN}`, `${IMAGE_REGISTRY}`, `${CLOUDFLARE_TUNNEL_ID}`. Flux fills them in
from the ConfigMap `site-settings` (`postBuild.substituteFrom` in `platform.yaml`) just before
applying. Rules, all learned the hard way:

- **Strict mode**: an unknown `${x}` stops that whole Kustomization (nothing broken, nothing
  deployed). To write a literal `${1}` (e.g. a regex), escape it: `$${1}`.
- Resources whose `${...}` belong to another tool (Grafana dashboards) carry the annotation
  `kustomize.toolkit.fluxcd.io/substitute: disabled`.
- Write images as `image: notes` + `newName:` in kustomization (not `image: ${IMAGE_REGISTRY}/notes`):
  Kustomize does not recognise image names with `${...}` and silently drops the version.

Check it like CI does: `python3 scripts/render-k8s.py > /tmp/rendered.yaml`.

## Watching Flux

On node-workstation:

```bash
flux get kustomizations                   # all Ready = True?
flux get helmreleases -A
flux reconcile source git flux-system     # pull now instead of waiting a minute
flux reconcile helmrelease vm -n flux-system   # Helm values change: their check runs only hourly
kubectl get pods -A                       # what is running where
```

## How CI fits in

1. **validate** (`.github/workflows/validate.yml`): on every change to `k8s/`, `clusters/`,
   `ansible/`, `scripts/`: site settings valid, everything renders like Flux, Kubernetes schemas
   valid, playbooks parse. A red check means: Flux would fail too, fix before merging.
2. **landing / notes / ticketlite**: on app changes: build the image (tests run inside), push to
   GHCR, commit the new tag (`deploy(app): sha-... [skip ci]`). Flux deploys that commit.

## A safe change, step by step

1. Edit the file in `k8s/...`.
2. Locally: `python3 scripts/render-k8s.py >/dev/null && echo OK` (catches typos in variables).
3. `git commit`, `git push`.
4. Watch the `validate` check on GitHub turn green.
5. `flux get kustomizations` until Ready; then `ansible-playbook playbooks/verify.yml`.
6. If it went wrong: `git revert <commit>` and push. Flux rolls back.

Next: [4. Configuration, not code](4-configuration.md)
