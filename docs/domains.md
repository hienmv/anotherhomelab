# Domains and routing

How a hostname like `notes.<domain>` reaches its app, where each piece is configured, and how to
add a new one. Live state verified 2026-11-10.

The tables describe this site: the domain and Cloudflare IDs come from `clusters/homelab/site-settings.yaml`,
IPs and node settings from `ansible/inventory.yml`. Change them there, not here.

## Hostnames

| Host | Serves | Defined in |
|---|---|---|
| `hien0ethan.com` | landing: intro, featured notes, Homelab, About | `k8s/apps/landing/ingress.yaml` (Ingress `landing`) |
| `notes.hien0ethan.com` | notes | `k8s/apps/notes/ingress.yaml` (Ingress `notes`) |
| `ticketlite.hien0ethan.com` | TicketLite (`/api` → api, `/` → web) | `k8s/apps/ticketlite/web.yaml` (Ingress `ticketlite`) |
| `grafana.hien0ethan.com` | Grafana, public, behind Cloudflare Access (email PIN), then Grafana login | `k8s/monitoring/grafana-public.yaml` (Ingress `grafana-public`) |
| any other `*.hien0ethan.com` | 301 to `https://hien0ethan.com` + same path | `k8s/apps/landing/ingress.yaml` (Ingress `fallback-to-landing`, Middleware `to-landing`) |
| `grafana.lan` | same Grafana, **LAN only** (not via Cloudflare) | `k8s/releases/vm-stack-values.yaml` (`grafana.ingress`) |

Retired names (now caught by the fallback): `tickets.`, `whoami.`, `hien.qzz.io` (expired free domain).

## Request path

```text
Browser ──HTTPS──> Cloudflare edge ──Cloudflare Tunnel──> cloudflared pod ──HTTP──> Traefik ──> Service ──> pods
          (1) DNS + TLS               (2) outbound only     (3) k8s/infra         (4) Ingress by Host
```

1. **Cloudflare DNS + TLS** (dashboard, zone `hien0ethan.com`, bought via Cloudflare Registrar on 2026-11-07).
   Two DNS records, both **Proxied** (orange cloud):

   | Type | Name | Target |
   |---|---|---|
   | CNAME | `@` | `ab958f58-ce09-42de-a826-bd8a8779bdbd.cfargotunnel.com` |
   | CNAME | `*` | `ab958f58-ce09-42de-a826-bd8a8779bdbd.cfargotunnel.com` |

   The `*` record means **no DNS change is needed for a new subdomain**. Cloudflare's free Universal SSL
   certificate covers `hien0ethan.com` and `*.hien0ethan.com`, i.e. **one level only**:
   `x.homelab.hien0ethan.com` resolves but fails the TLS handshake (tested 2026-11-09).
2. **Cloudflare Tunnel** `test-cloudflared-tunnel` (id `ab958f58-…`): the cluster dials out to Cloudflare, so
   no router port is open. Credentials: secret `infra/tunnel-creds` only (no other copy exists; losing it
   means creating a new tunnel and repointing both CNAMEs).
3. **cloudflared** (`k8s/infra/cloudflared.yaml`, 1 replica on node-8gb). Its ConfigMap sends
   `hien0ethan.com` and `*.hien0ethan.com` to `http://traefik.kube-system.svc.cluster.local:80`, anything
   else to 404. Hostname-agnostic: new subdomains need no change here either.
4. **Traefik** (bundled with k3s; customised by `k8s/infra/traefik-config.yaml`, which trusts
   `X-Forwarded-*` from pods so apps see `https` for tunnel traffic). It picks the Ingress whose `host`
   matches. Host-less Ingresses have the lowest priority, which is how the fallback works.

Inside the cluster, traffic is plain HTTP (TLS ends at Cloudflare; Cloudflare → tunnel is encrypted).

## Redirects inside apps (nginx)

| From | To | Where |
|---|---|---|
| `grafana.lan` over `http://` | `https://grafana.lan` | `k8s/monitoring/grafana-https.yaml` (Middleware `redirect-https`) |

The landing also proxies `hien0ethan.com/featured.json` from the notes Service inside the cluster
(`NOTES_UPSTREAM` in `apps/landing/Dockerfile`), so the browser never calls the notes host for it.

## Add a public subdomain for a new app

1. Pick `<app>.<domain>` (one level; not already in the table above).
2. Add an Ingress with `host: <app>.${DOMAIN}` (Flux fills in the domain) in `k8s/apps/<app>/`,
   `ingressClassName: traefik`, pointing at the app's Service. Copy `k8s/apps/notes/ingress.yaml`.
3. Commit and push. Flux applies it within about a minute; the name works immediately.

Nothing to change in Cloudflare DNS, the tunnel, or cloudflared. The new host leaves the fallback
automatically because an Ingress with a host always wins over the host-less one.

Apps behind the tunnel see `https` in `X-Forwarded-Proto`; use it for Secure cookies (TicketLite does).

## Protect an app with Cloudflare Access (recommended for Grafana)

Grafana's login page is public. Cloudflare Access adds a login step at Cloudflare's edge, so requests
reach the Pi only after it. No cluster change is needed.

Status: enabled for `grafana.hien0ethan.com` (team `soft-violet-7a6b`, app `Grafana`).

1. Cloudflare dashboard → **Zero Trust** (first time: choose a team name, Free plan, up to 50 users).
2. **Access → Applications → Add an application → Self-hosted**. Name: `Grafana`.
3. Public hostname: subdomain `grafana`, domain `hien0ethan.com`, path empty. Session duration: 24 hours.
4. Policy: action **Allow**, include **Emails** → your email address. Policies are separate, reusable
   objects (Access → Policies): create one, then in the app use **Select existing policies** to attach it.
5. Login method: **One-time PIN** (code sent by email). If it is not listed, add it under
   Settings → Authentication → Login methods. Click **Save** on the last wizard page.

Check from any shell:

```sh
curl -sI https://grafana.hien0ethan.com | grep -i location
# Access on:  location: https://<team>.cloudflareaccess.com/cdn-cgi/access/login/...
# Access off: location: /login   (Grafana's own redirect, request reached the Pi)
```

In a browser, use a private window: Cloudflare's email/PIN page first, then Grafana's login. Keep the
Grafana password anyway (two layers).

Troubleshooting: `location: /login` means no Access application covers the hostname. A policy alone
protects nothing; it must be attached to an application whose hostname matches exactly. Tools that call
the public URL without a browser get the Cloudflare login page too: use an Access service token
(`CF-Access-Client-Id` / `CF-Access-Client-Secret` headers) or the LAN name.

## LAN-only names

`grafana.lan` is not in Cloudflare DNS at all (the public name is `grafana.<domain>`). It works only on
node-workstation: `workstation-prepare.yml` maps it to the k3s server in `/etc/hosts` and installs the
private CA's public cert (K3S-GUIDE.md section 7). Tunnel traffic can never reach it: cloudflared only forwards
`*.hien0ethan.com`, and the fallback Ingress does not match because Traefik sees the real Host header.

## Change things in Cloudflare

| What | Where in the dashboard |
|---|---|
| DNS records (`@`, `*`) | hien0ethan.com → DNS → Records |
| Tunnel status / connectors | Zero Trust → Networks → Tunnels (locally-managed tunnel: routes live in `k8s/infra/cloudflared.yaml`, not in the dashboard) |
| Force HTTPS | SSL/TLS → Edge Certificates → Always Use HTTPS |
| Deeper subdomains (`a.b.hien0ethan.com`) | needs Advanced Certificate Manager (paid); avoided by the one-level rule |
| Domain renewal | Domain Registration → hien0ethan.com |
