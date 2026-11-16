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
| **branch** | a separate line of commits; only `main` is deployed |
| **break-glass** | emergency access, used only when everything else fails (e.g. keyboard on the Pi) |
| **canary** | try something on one small target first (one node, one pod) before everything |
| **CI** | Continuous Integration: GitHub Actions builds and tests on every push |
| **cloudflared / tunnel** | program in the cluster that connects out to Cloudflare, so no router ports are open |
| **CNPG** | CloudNativePG: the operator that runs Postgres in Kubernetes |
| **ConfigMap / Secret** | Kubernetes objects holding settings / passwords |
| **container / image** | a program packaged with everything it needs / the package itself (stored in GHCR) |
| **CRD** | Custom Resource Definition: lets an operator add its own object types (e.g. `Cluster` for Postgres) |
| **DaemonSet** | "run one copy on every node" (e.g. the log collector, node-exporter) |
| **dead man's switch** | an alarm that fires when regular "I'm alive" pings **stop** (healthchecks.io here) |
| **deploy key** | an SSH key that may access **one** repository (read, or read + write); here for Flux, the workstation and CI's tag bumps |
| **Deployment / Pod** | "run N copies of this image" / one running copy |
| **drain** | `kubectl drain <node>`: move every pod off a node before maintenance (respects PDBs) |
| **drill** | practising a procedure on purpose (e.g. a full rebuild) to prove it works |
| **Flux** | GitOps tool inside the cluster: applies what is in git (chapter 3) |
| **GHCR** | GitHub Container Registry: where the app images are stored (`ghcr.io/<owner>/...`) |
| **GitHub Actions secret** | an encrypted value stored in the repo's settings; workflows can use it, nobody can read it back (e.g. `CI_DEPLOY_KEY`) |
| **gitleaks** | a scanner that looks for secrets (keys, tokens) in the git history; runs in CI |
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
| **metrics / logs** | numbers measured over time (graphs in Grafana) / text lines a program prints (`kubectl logs`) |
| **LogsQL** | VictoriaLogs' query language, e.g. `kubernetes.pod_namespace:=apps AND error` |
| **log level** | how urgent a log line is: ERROR (failed) / WARN (degraded) / INFO (normal) / DEBUG (details); rule in chapter 9 |
| **manifest** | a YAML file describing Kubernetes objects |
| **namespace** | a group of related Kubernetes objects (`apps`, `data`, ...) |
| **node** | one machine in the cluster |
| **passphrase** | password that encrypts a private key file |
| **PodDisruptionBudget (PDB)** | "keep at least N copies running" during voluntary disruptions (drain, upgrade) |
| **playbook / play / task / module** | Ansible file / a list of tasks for some hosts / one step / the tool doing the step |
| **postmortem** | written review after an incident: what, why, what changes (no blame) |
| **pull request (PR)** | a request to merge a branch into `main`; CI runs the checks on it first |
| **required check** | a CI job that must pass before a PR may be merged |
| **replica** | one copy of a Deployment's pod; `replicas: 2` = two identical copies behind one Service |
| **request id (correlation id)** | one id per request, in every log line it causes across services (`request_id`, `X-Request-ID`) |
| **OpenTelemetry (OTel)** | the industry standard for producing and sending metrics, logs and traces: libraries/agent in the app, the Collector, standard field names (chapter 10) |
| **OTLP** | OpenTelemetry's wire protocol (port 4318 HTTP / 4317 gRPC) |
| **ruleset** | GitHub's branch protection: who may change `main`, and how (`.github/rulesets/main.json`) |
| **SD swap / static IP** | moving SD cards between Pis / an IP address set on the machine instead of given by the router |
| **site-settings** | this site's values (domain, registry, ...) in `clusters/homelab/site-settings.yaml` |
| **SOPS** | encrypts secrets so they can live in git (planned) |
| **span** | one timed step of a trace (an HTTP request, one SQL query, ...), with a `span_id` and a parent |
| **structured logs** | log lines as JSON objects (`{"level":"INFO","status":200,...}`): every part is a searchable field |
| **substitution (`${DOMAIN}`)** | Flux fills variables in manifests from site-settings |
| **tmux** | keeps a terminal session running when your SSH connection drops |
| **tail sampling** | deciding to keep or drop a trace after it is complete (the collector drops probe and housekeeping traces) |
| **topology spread** | `topologySpreadConstraints`: asks the scheduler to put the copies on different nodes |
| **vmagent / vmsingle** | collects metrics every 30 s / stores them 30 days (VictoriaMetrics, on node-workstation) |
| **VictoriaLogs / vlagent** | the log database (`vlsingle`, 14 days) / the collector on every node that ships log lines to it |
| **trace / trace_id** | everything one request caused, across programs / its id, also on every log line (`traceparent` header between programs) |
| **VictoriaTraces** | the trace database (`vtsingle`, 7 days) |
| **UID / GID** | the number Linux uses for a user / group; file owners are stored as numbers |
| **verify.yml** | the read-only health check of the whole homelab |
