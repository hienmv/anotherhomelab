# Glossary

| Term | Meaning (in this homelab) |
|---|---|
| **accepted risk** | a known problem deliberately tolerated, with a reason and an end date (shown as a warning until then) |
| **age** | a small encryption tool; encrypts the backups. Its key: `~/.config/sops/age/keys.txt` on node-workstation + password manager |
| **agent (ssh-agent)** | keeps unlocked SSH keys in memory so you type the passphrase once (8 h here) |
| **Alertmanager / vmalert** | sends alerts (groups, repeats, Telegram) / evaluates the alert and recording rules every 20 s |
| **Ansible** | sets up machines from a description over SSH (chapter 2) |
| **AppArmor** | a Linux security layer that limits what programs may do; Ubuntu 26.04's default broke containers (postmortem) |
| **authorized_keys** | file on a server listing the public keys allowed to log in |
| **bastion** | the one machine you log in to first to reach the others (here: node-workstation) |
| **become** | Ansible for "run as root via sudo" |
| **branch** | a separate line of commits; only `main` is deployed |
| **break-glass** | emergency access, used only when everything else fails (e.g. keyboard on the Pi) |
| **burn rate** | how fast the error budget is spent; 1 = exactly used up in 30 days, 14.4 = in ~2 days (chapter 11) |
| **canary** | try something on one small target first (one node, one pod) before everything |
| **CI** | Continuous Integration: GitHub Actions builds and tests on every push |
| **cloudflared / tunnel** | program in the cluster that connects out to Cloudflare, so no router ports are open |
| **CNPG** | CloudNativePG: the operator that runs Postgres in Kubernetes |
| **ConfigMap / Secret** | Kubernetes objects holding settings / passwords |
| **container / image** | a program packaged with everything it needs / the package itself (stored in GHCR) |
| **CRD** | Custom Resource Definition: lets an operator add its own object types (e.g. `Cluster` for Postgres) |
| **DaemonSet** | "run one copy on every node" (e.g. the log collector, node-exporter) |
| **descheduler** | evicts pods that break a placement rule (here: spread) so the scheduler can place them better; the scheduler itself never moves running pods |
| **dead man's switch** | an alarm that fires when regular "I'm alive" pings **stop** (healthchecks.io here) |
| **deploy key** | an SSH key that may access **one** repository (read, or read + write); here for Flux, the workstation and CI's tag bumps |
| **Deployment / Pod** | "run N copies of this image" / one running copy |
| **drain** | `kubectl drain <node>`: move every pod off a node before maintenance (respects PDBs) |
| **drift** | a machine or cluster no longer matching git (e.g. a change made by hand); GitOps undoes it |
| **drill** | practising a procedure on purpose (e.g. a full rebuild) to prove it works |
| **Flux** | GitOps tool inside the cluster: applies what is in git (chapter 3) |
| **Garage** | a small S3 server; the backup store on node-8gb's SSD (chapter 13) |
| **GHCR** | GitHub Container Registry: where the app images are stored (`ghcr.io/<owner>/...`) |
| **GitHub Actions secret** | an encrypted value stored in the repo's settings; workflows can use it, nobody can read it back (e.g. `CI_DEPLOY_KEY`) |
| **gitleaks** | a scanner that looks for secrets (keys, tokens) in the git history; runs in CI |
| **GitOps** | the cluster runs what is in git; changes happen by commits |
| **host GitOps / pull model** | each machine applies the desired state from git itself (here: `homelab-apply.timer` runs `site.yml`) |
| **Helm / HelmRelease** | packages of Kubernetes manifests ("charts") / Flux's object that installs one |
| **idempotent** | running it again changes nothing if it is already right |
| **503 / Retry-After** | HTTP "temporarily unavailable, try again in N seconds": a brief outage, not a bug (500) or a wrong request (4xx) |
| **cascading failure** | one part failing makes others fail too (e.g. health checks that depend on the database take every api pod out at once) |
| **Ingress / Traefik** | rule "this hostname goes to that app" / the program that applies those rules |
| **inventory** | Ansible's list of machines and their variables (`ansible/inventory.yml`) |
| **journal / journald** | systemd's log of a machine (services + kernel); `journalctl` reads it; since O1 also shipped to VictoriaLogs |
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
| **PITR / WAL / base backup** | point-in-time recovery / Postgres' change log / a full copy; together: restore to any moment (chapter 13) |
| **passphrase** | password that encrypts a private key file |
| **PodDisruptionBudget (PDB)** | "keep at least N copies running" during voluntary disruptions (drain, upgrade) |
| **playbook / play / task / module** | Ansible file / a list of tasks for some hosts / one step / the tool doing the step |
| **postmortem** | written review after an incident: what, why, what changes (no blame) |
| **pull request (PR)** | a request to merge a branch into `main`; CI runs the checks on it first |
| **required check** | a CI job that must pass before a PR may be merged |
| **recording rule** | a query computed regularly and stored as a new metric (`slo:error_ratio:rate1h`), so alerts and dashboards stay cheap |
| **RPO / RTO** | how much data can be lost / how long until service is back (here: RPO ~5 min with WAL archiving) |
| **replica** | one copy of a Deployment's pod; `replicas: 2` = two identical copies behind one Service. For a database: a second server that continuously copies the primary (Postgres since 2026-11-22) |
| **primary / streaming replication** | the one Postgres instance that accepts writes / the primary sends each change (WAL) to the replica as it happens; asynchronous here: the replica is usually under a second behind |
| **failover / switchover** | the replica is promoted because the primary failed (automatic, CNPG) / the roles are swapped on purpose, cleanly (planned, e.g. before maintenance) |
| **request id (correlation id)** | one id per request, in every log line it causes across services (`request_id`, `X-Request-ID`) |
| **OpenTelemetry (OTel)** | the industry standard for producing and sending metrics, logs and traces: libraries/agent in the app, the Collector, standard field names (chapter 10) |
| **OTLP** | OpenTelemetry's wire protocol (port 4318 HTTP / 4317 gRPC) |
| **Provider / Alert (Flux)** | where Flux sends messages (Telegram) / which events (changes, failures) |
| **ruleset** | GitHub's branch protection: who may change `main`, and how (`.github/rulesets/main.json`) |
| **R2 / rclone crypt** | Cloudflare's S3 storage (the off-site copy) / rclone's encryption layer: content and names scrambled before upload |
| **S3 / bucket / object** | the standard object-storage API / a named container / one stored file (Garage on the SSD, later R2) |
| **S.M.A.R.T.** | a disk's own health log (`smartctl`): failing sectors, transfer errors, age |
| **3-2-1 rule** | 3 copies of data, on 2 kinds of storage, 1 off-site (+1 immutable, 0 restore errors); chapter 13 |
| **SD swap / static IP** | moving SD cards between Pis / an IP address set on the machine instead of given by the router |
| **site-settings** | this site's values (domain, registry, ...) in `clusters/homelab/site-settings.yaml` |
| **SLI / SLO / error budget** | measured share of good requests / its target (99.5 % in 30 days) / what may fail (0.5 %) |
| **SOPS** | encrypts only the values of YAML files so secrets can live in git; Flux decrypts them with the age key (chapter 12) |
| **span** | one timed step of a trace (an HTTP request, one SQL query, ...), with a `span_id` and a parent |
| **structured logs** | log lines as JSON objects (`{"level":"INFO","status":200,...}`): every part is a searchable field |
| **substitution (`${DOMAIN}`)** | Flux fills variables in manifests from site-settings |
| **textfile collector** | node-exporter reads `*.prom` files from a folder: how short jobs (backups) publish their results as metrics |
| **tmux** | keeps a terminal session running when your SSH connection drops |
| **tail sampling** | deciding to keep or drop a trace after it is complete (the collector drops probe and housekeeping traces) |
| **topology spread** | `topologySpreadConstraints`: asks the scheduler to put the copies on different nodes |
| **vmagent / vmsingle** | collects metrics every 30 s / stores them 30 days (VictoriaMetrics, on node-workstation) |
| **VictoriaLogs / vlagent** | the log database (`vlsingle`, 14 days) / the collector on every node that ships log lines to it |
| **trace / trace_id** | everything one request caused, across programs / its id, also on every log line (`traceparent` header between programs) |
| **VictoriaTraces** | the trace database (`vtsingle`, 7 days) |
| **UID / GID** | the number Linux uses for a user / group; file owners are stored as numbers |
| **watch label** | `reconcile.fluxcd.io/watch: Enabled` on a ConfigMap: the HelmRelease using it upgrades at once when it changes |
| **verify.yml** | the read-only health check of the whole homelab |
