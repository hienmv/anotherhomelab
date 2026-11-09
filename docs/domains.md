# Domains and routing

How a hostname like `notes.hien0ethan.com` reaches its app, where each piece is configured, and
how to add a new one. Live state verified 2026-11-10.

## Hostnames

| Host | Serves | Defined in |
|---|---|---|
| `hien0ethan.com` | landing: intro, featured notes, Homelab, About | `k8s/apps/landing/ingress.yaml` (Ingress `landing`) |
| `notes.hien0ethan.com` | notes | `k8s/apps/notes/ingress.yaml` (Ingress `notes`) |
| `ticketlite.hien0ethan.com` | TicketLite (`/api` → api, `/` → web) | `k8s/apps/ticketlite/web.yaml` (Ingress `ticketlite`) |
| any other `*.hien0ethan.com` | 301 to `https://hien0ethan.com` + same path | `k8s/apps/landing/ingress.yaml` (Ingress `fallback-to-landing`, Middleware `to-landing`) |
| `grafana.lan` | Grafana, **LAN only** (not via Cloudflare) | `k8s/releases/vm-stack-values.yaml` (`grafana.ingress`) |

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
3. **cloudflared** (`k8s/infra/cloudflared.yaml`, 1 replica on pi4-8gb). Its ConfigMap sends
   `hien0ethan.com` and `*.hien0ethan.com` to `http://traefik.kube-system.svc.cluster.local:80`, anything
   else to 404. Hostname-agnostic: new subdomains need no change here either.
4. **Traefik** (bundled with k3s; customised by `k8s/infra/traefik-config.yaml`, which trusts
   `X-Forwarded-*` from pods so apps see `https` for tunnel traffic). It picks the Ingress whose `host`
   matches. Host-less Ingresses have the lowest priority, which is how the fallback works.

Inside the cluster, traffic is plain HTTP (TLS ends at Cloudflare; Cloudflare → tunnel is encrypted).

## Redirects inside apps (nginx)

| From | To | Where |
|---|---|---|
| `hien0ethan.com/notes/<slug>/`, `/rss.xml`, `/category/...` | `notes.hien0ethan.com/...` | `apps/landing/nginx.conf.template` |
| old Jekyll `/tech/2020/10/10/heap.html` (on either host) | `notes.hien0ethan.com/heap/` | landing template + `apps/notes/nginx.conf` |
| `notes.hien0ethan.com/about/` | `hien0ethan.com/about/` | `apps/notes/nginx.conf` |
| `grafana.lan` over `http://` | `https://grafana.lan` | `k8s/monitoring/grafana-https.yaml` (Middleware `redirect-https`) |

The landing also proxies `hien0ethan.com/featured.json` from the notes Service inside the cluster
(`NOTES_UPSTREAM` in `apps/landing/Dockerfile`), so the browser never calls the notes host for it.

## Add a public subdomain for a new app

1. Pick `<app>.hien0ethan.com` (one level; not already in the table above).
2. Add an Ingress with that `host` in `k8s/apps/<app>/`, `ingressClassName: traefik`, pointing at the
   app's Service. Copy `k8s/apps/notes/ingress.yaml`.
3. Commit and push. Flux applies it within about a minute; the name works immediately.

Nothing to change in Cloudflare DNS, the tunnel, or cloudflared. The new host leaves the fallback
automatically because an Ingress with a host always wins over the host-less one.

Apps behind the tunnel see `https` in `X-Forwarded-Proto`; use it for Secure cookies (TicketLite does).

## LAN-only names

`grafana.lan` is not in Cloudflare DNS at all. It works only on devices that map it to a node IP:
the Mac has `192.168.40.250 grafana.lan` in `/etc/hosts`, and trusts its certificate through the mkcert
CA (K3S-GUIDE.md section 10). Tunnel traffic can never reach it: cloudflared only forwards
`*.hien0ethan.com`, and the fallback Ingress does not match because Traefik sees the real Host header.

## Change things in Cloudflare

| What | Where in the dashboard |
|---|---|
| DNS records (`@`, `*`) | hien0ethan.com → DNS → Records |
| Tunnel status / connectors | Zero Trust → Networks → Tunnels (locally-managed tunnel: routes live in `k8s/infra/cloudflared.yaml`, not in the dashboard) |
| Force HTTPS | SSL/TLS → Edge Certificates → Always Use HTTPS |
| Deeper subdomains (`a.b.hien0ethan.com`) | needs Advanced Certificate Manager (paid); avoided by the one-level rule |
| Domain renewal | Domain Registration → hien0ethan.com |
