#!/usr/bin/env python3
"""Generate monosketch-style ASCII system diagrams and inject them into README.md.

Diagrams live between markers in README.md:
    <!-- diagram:NAME:start --> ... <!-- diagram:NAME:end -->

Usage:
    python3 scripts/diagrams.py          # rewrite README.md in place
    python3 scripts/diagrams.py --print  # print diagrams only

Edit the draw_* functions when the infrastructure changes, then re-run.
Legend used in diagrams: solid box = running, dashed box = planned.
"""
import pathlib
import re
import sys

README = pathlib.Path(__file__).resolve().parent.parent / "README.md"


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.g = [[" "] * w for _ in range(h)]

    def put(self, x, y, s):
        for i, c in enumerate(s):
            self.g[y][x + i] = c

    def box(self, x, y, w, h, title=None, lines=(), dashed=False, center=False):
        hz, vt = ("┄", "┆") if dashed else ("─", "│")
        self.put(x, y, "┌" + hz * (w - 2) + "┐")
        for r in range(1, h - 1):
            self.put(x, y + r, vt)
            self.put(x + w - 1, y + r, vt)
        self.put(x, y + h - 1, "└" + hz * (w - 2) + "┘")
        if title:
            self.put(x + 1, y, "─ " + title + " ")
        assert len(lines) <= h - 2, f"too many lines for box height {h}: {lines[0]!r}..."
        for i, line in enumerate(lines):
            inner = w - 4
            assert len(line) <= inner, f"text too long for box: {line!r}"
            text = line.center(inner) if center else line
            self.put(x + 2, y + 1 + i, text)

    def vline(self, x, y1, y2):
        for y in range(y1, y2 + 1):
            c = self.g[y][x]
            self.g[y][x] = "┼" if c in "─┄" else ("│" if c == " " else c)

    def hline(self, y, x1, x2):
        for x in range(x1, x2 + 1):
            c = self.g[y][x]
            self.g[y][x] = "┼" if c in "│┆" else ("─" if c == " " else c)

    def render(self):
        return "\n".join("".join(r).rstrip() for r in self.g)


def draw_flow():
    """Request + data flow (logical view)."""
    c = Canvas(96, 44)
    cx = 38

    c.box(31, 0, 15, 3, lines=["Client"], center=True)
    c.put(cx, 2, "┬")
    c.vline(cx, 3, 3)
    c.put(cx + 2, 3, "HTTPS  hien0ethan.com, *.hien0ethan.com")
    c.put(cx, 4, "▼")

    c.box(23, 5, 31, 4, lines=["Cloudflare Edge", "TLS, DNS: @ and * -> tunnel"], center=True)
    c.put(cx, 8, "┬")
    c.vline(cx, 9, 9)
    c.put(cx + 2, 9, "Cloudflare Tunnel (outbound only, no open ports)")

    # LAN frame + k3s frame
    c.box(0, 10, 96, 34, title="Home LAN 192.168.40.0/24")
    c.box(2, 11, 92, 32, title="k3s v1.37.2  (3 nodes)")
    c.vline(cx, 10, 11)
    c.put(cx, 12, "▼")
    c.box(23, 13, 31, 3, lines=["cloudflared  (pod, pi4-8gb)"], center=True)
    c.put(cx, 15, "┬")
    c.vline(cx, 16, 16)
    c.put(cx + 2, 16, "svc traefik:80")
    c.put(cx, 17, "▼")
    c.box(23, 18, 31, 3, lines=["Traefik ingress"], center=True)
    c.put(cx, 20, "┬")
    c.vline(cx, 21, 21)
    c.put(cx + 2, 21, "Host rules")

    # monitoring (LAN only: Host grafana.lan, never routed by the tunnel)
    c.box(58, 12, 34, 7, title="ns monitoring", lines=[
        "vmagent --scrape--> all pods",
        "  & node-exporter (3 nodes)",
        "VMSingle: 30d, PVC 10Gi",
        "Grafana: https://grafana.lan",
        "(LAN only, not public)",
    ])

    c.hline(22, 14, 62)
    c.put(14, 22, "┌")
    c.put(cx, 22, "┴")
    c.put(62, 22, "┐")
    c.put(14, 23, "▼")
    c.put(62, 23, "▼")

    c.box(5, 24, 19, 4, lines=["site (apex)", "+ whoami debug"], center=True)
    c.box(48, 24, 29, 4, lines=["TicketLite web + api x2", "ticketlite.<domain>"], center=True)

    # web -> postgres / redis
    c.put(50, 27, "┬")
    c.vline(50, 28, 29)
    c.put(51, 29, " SQL")
    c.put(50, 30, "▼")
    c.put(70, 27, "┬")
    c.vline(70, 28, 29)
    c.put(72, 29, "gate + XADD")
    c.put(70, 30, "▼")

    c.box(26, 31, 28, 5, lines=["PostgreSQL 17 (CNPG)", "pg-rw.data.svc:5432", "PVC 10Gi, pi4-8gb"], center=True)
    c.box(58, 31, 28, 5, lines=["Redis 8.2 (queue/cache)", "redis.data.svc:6379", "PVC 2Gi, AOF, pi4-8gb"], center=True)

    # cron -> postgres
    c.box(4, 38, 27, 4, lines=["batch CronJob (planned)", "nightly / one-off Jobs"], center=True, dashed=True)
    c.put(28, 38, "┴")
    c.vline(28, 37, 37)
    c.put(28, 36, "▲")

    # workers -> redis (consume), workers -> postgres (write)
    c.box(58, 38, 28, 4, lines=["TicketLite worker x2", "stream 'orders' consumer"], center=True)
    c.put(72, 38, "┴")
    c.vline(72, 37, 37)
    c.put(72, 36, "▲")
    c.put(74, 37, "consume")
    c.put(58, 40, "┤")
    c.hline(40, 41, 57)
    c.put(40, 40, "└")
    c.vline(40, 37, 39)
    c.put(40, 36, "▲")
    c.put(43, 39, "confirm tx")
    return c.render()


def draw_nodes():
    """Physical placement per node."""
    c = Canvas(96, 15)
    c.box(0, 0, 34, 15, title="pi4-8gb  .250", lines=[
        "Pi 4B, 8 GB RAM, SD 32 GB",
        "k3s server (control plane)",
        "label role=data",
        "",
        "* traefik, coredns",
        "* metrics-server, local-path",
        "* postgres pg-1",
        "* redis-0",
        "* cloudflared (tunnel)",
        "* vmsingle, vmagent, ksm",
        "* node-exporter",
        "* whoami x1, ticketlite pods",
        "- SSD for PG/Redis (todo)",
    ])
    c.box(36, 0, 30, 15, title="pi4-4gb  .251", lines=[
        "Pi 4B, 4 GB RAM, SD 64 GB",
        "k3s agent",
        "label role=apps",
        "",
        "* cnpg operator",
        "* grafana, vm-operator",
        "* node-exporter",
        "* whoami x1",
        "* ticketlite pods",
        "- KEDA autoscale (planned)",
        "- batch CronJobs (planned)",
    ])
    c.box(68, 0, 28, 15, title="pi3  .252", lines=[
        "Pi 3B+, 1 GB, SD 32 GB",
        "k3s agent",
        "label role=light",
        "taint role=light:NoSched",
        "",
        "* node-exporter",
        "* ufw firewall",
        "- light pods only",
        "! under-voltage on load",
    ])
    return c.render() + "\n\n  * running    - planned / todo"


def draw_ops():
    """How changes reach the cluster: admin Mac -> GitHub -> Flux."""
    c = Canvas(96, 17)
    c.box(0, 0, 38, 9, title="Mac (admin workstation)", lines=[
        "repo ~/workspace/source/homelab",
        "kubectl, helm, k9s, flux (docker)",
        "kubeconfig ~/.kube/homelab.yaml",
        "mkcert CA: signs grafana.lan",
        "/etc/hosts: Pis + grafana.lan",
        "ssh key id_homelab -> Pis",
        "browser -> https://grafana.lan",
    ])
    c.box(56, 0, 36, 5, title="GitHub", lines=[
        "hienmv/anotherhomelab (private)",
        "Actions: images -> GHCR (arm64)",
        "  then bump tag in k8s/ (commit)",
    ])
    # git push
    c.put(37, 2, "├")
    c.hline(2, 38, 54)
    c.put(55, 2, "►")
    c.put(42, 1, "git push")
    # flux pulls
    c.put(74, 4, "┬")
    c.vline(74, 5, 9)
    c.put(53, 5, "pull every 1m (SSH)")
    c.put(52, 6, "read-only deploy key")
    c.put(74, 10, "▼")
    c.box(56, 11, 36, 6, title="k3s: ns flux-system", lines=[
        "source, kustomize, helm ctrls",
        "apply clusters/homelab ->",
        "k8s/* in dependency order",
        "(secrets stay out of git)",
    ])
    # mac -> cluster / pis
    for x, label in ((8, "kubectl :6443"), (26, "ssh (setup)")):
        c.put(x, 8, "┬")
        c.vline(x, 9, 10)
        c.put(x, 11, "▼")
    c.put(10, 9, "admin")
    c.put(28, 9, "OS, k3s")
    c.put(2, 12, "k3s API")
    c.put(2, 13, "pi4-8gb .250")
    c.put(21, 12, "Pis .250-.252")
    c.put(21, 13, "(hien@, sudo)")
    return c.render()


DIAGRAMS = {"flow": draw_flow, "nodes": draw_nodes, "ops": draw_ops}


def main():
    rendered = {name: fn() for name, fn in DIAGRAMS.items()}
    if "--print" in sys.argv:
        for name, text in rendered.items():
            print(f"### {name}\n{text}\n")
        return
    readme = README.read_text()
    for name, text in rendered.items():
        pat = re.compile(
            rf"(<!-- diagram:{name}:start -->).*?(<!-- diagram:{name}:end -->)", re.S
        )
        if not pat.search(readme):
            sys.exit(f"marker for diagram '{name}' not found in README.md")
        block = "\n```text\n" + text + "\n```\n"
        readme = pat.sub(lambda m: m.group(1) + block + m.group(2), readme)
    README.write_text(readme)
    print(f"updated {README.name}: {', '.join(rendered)}")


if __name__ == "__main__":
    main()
