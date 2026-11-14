# Glossary

| Term | Meaning (in this homelab) |
|---|---|
| **age** | a small encryption tool; encrypts the backups. Its key: `~/.config/sops/age/keys.txt` on node-workstation + password manager |
| **agent (ssh-agent)** | keeps unlocked SSH keys in memory so you type the passphrase once (8 h here) |
| **Ansible** | sets up machines from a description over SSH (chapter 2) |
| **AppArmor** | a Linux security layer that limits what programs may do; Ubuntu 26.04's default broke containers (postmortem) |
| **authorized_keys** | file on a server listing the public keys allowed to log in |
| **bastion** | the one machine you log in to first to reach the others (here: node-workstation) |
| **become** | Ansible for "run as root via sudo" |
| **break-glass** | emergency access, used only when everything else fails (e.g. keyboard on the Pi) |
| **canary** | try something on one small target first (one node, one pod) before everything |
| **CI** | Continuous Integration: GitHub Actions builds and tests on every push |
| **cloudflared / tunnel** | program in the cluster that connects out to Cloudflare, so no router ports are open |
| **CNPG** | CloudNativePG: the operator that runs Postgres in Kubernetes |
| **ConfigMap / Secret** | Kubernetes objects holding settings / passwords |
| **container / image** | a program packaged with everything it needs / the package itself (stored in GHCR) |
| **CRD** | Custom Resource Definition: lets an operator add its own object types (e.g. `Cluster` for Postgres) |
| **Deployment / Pod** | "run N copies of this image" / one running copy |
| **drill** | practising a procedure on purpose (e.g. a full rebuild) to prove it works |
| **Flux** | GitOps tool inside the cluster: applies what is in git (chapter 3) |
| **GHCR** | GitHub Container Registry: where the app images are stored (`ghcr.io/<owner>/...`) |
| **GitOps** | the cluster runs what is in git; changes happen by commits |
| **Helm / HelmRelease** | packages of Kubernetes manifests ("charts") / Flux's object that installs one |
| **idempotent** | running it again changes nothing if it is already right |
| **Ingress / Traefik** | rule "this hostname goes to that app" / the program that applies those rules |
| **inventory** | Ansible's list of machines and their variables (`ansible/inventory.yml`) |
| **k3s** | a small Kubernetes distribution, good for Pis |
| **kubeconfig** | the file `kubectl` uses to talk to the cluster (`~/.kube/homelab.yaml`, cluster-admin) |
| **kubeconform** | checks manifests against Kubernetes schemas (in CI) |
| **kubectl** | the command-line tool for Kubernetes |
| **Kustomize / Kustomization** | combines and patches manifests (`kustomization.yaml`) / Flux's object that applies one folder |
| **manifest** | a YAML file describing Kubernetes objects |
| **namespace** | a group of related Kubernetes objects (`apps`, `data`, ...) |
| **node** | one machine in the cluster |
| **passphrase** | password that encrypts a private key file |
| **playbook / play / task / module** | Ansible file / a list of tasks for some hosts / one step / the tool doing the step |
| **postmortem** | written review after an incident: what, why, what changes (no blame) |
| **SD swap / static IP** | moving SD cards between Pis / an IP address set on the machine instead of given by the router |
| **site-settings** | this site's values (domain, registry, ...) in `clusters/homelab/site-settings.yaml` |
| **SOPS** | encrypts secrets so they can live in git (planned) |
| **substitution (`${DOMAIN}`)** | Flux fills variables in manifests from site-settings |
| **tmux** | keeps a terminal session running when your SSH connection drops |
| **UID / GID** | the number Linux uses for a user / group; file owners are stored as numbers |
| **verify.yml** | the read-only health check of the whole homelab |
