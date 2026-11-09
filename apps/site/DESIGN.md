# Site (hien0ethan.com): design

Status: **proposal, 2026-11-09**, awaiting approval. Replaces the placeholder in `k8s/apps/landing/`.

Personal site on the apex domain: notes (migrated from `hienmv.github.io`, to be shut down), a menu of
homelab apps, and an about page. A separate app from TicketLite: own folder, image, CI and k8s manifests.

## 1. Pages and navigation

```text
┌──────────────────────────────────────────────────────────────────────────┐
│ Hien                         Notes    Homelab ▾    About                 │
│                                       ┌─────────────────────────────┐    │
│                                       │ TicketLite                  │    │
│                                       │ Ticket sales, hot-ticket    │    │
│                                       │ rush handling               │    │
│                                       │ ─────────────────────────── │    │
│                                       │ More apps coming            │    │
│                                       └─────────────────────────────┘    │
├──────────────────────────────────────────────────────────────────────────┤
│  Notes                                     [All] [tech] [work-style]     │
│                                                                          │
│  Jul 14, 2021 · tech                                                     │
│  JVM GC: G1 collector                                                    │
│  ─────────────────────────────────────────────────────────────────────   │
│  Jul 11, 2021 · tech                                                     │
│  JVM GC: CMS collector                                                   │
│  ...                                                                     │
└──────────────────────────────────────────────────────────────────────────┘
```

| Route | Content |
|---|---|
| `/` | **Notes list (default)**: newest first, category filter (tech, work-style), date + category |
| `/notes/<slug>/` | One note: title, date, category, reading time, body, prev/next links |
| Homelab ▾ | Dropdown in the header: each app with name, one-line description, link. Also at `/homelab/` (same list as a page, for mobile/no-JS) |
| `/about/` | Intro, CV (sections + downloadable PDF), links: GitHub, LinkedIn, LeetCode, ... |
| `/rss.xml` | Notes feed (the old site had one) |
| 404 | Simple page linking back to Notes |

Mobile: header collapses to a menu button; the dropdown opens on tap. Keyboard: the Homelab button
toggles with Enter/Space, arrow keys move between items, Escape closes. Dark mode follows the OS.

## 2. Tech choice

**Astro** (static site generator), recommended:
- Markdown notes are first-class (content collections with typed front matter), so the 8 notes move
  almost unchanged. Code highlighting (Java, Python) built in.
- Output is plain static HTML/CSS, no JavaScript except the small dropdown. Fast on a Pi, good for search engines.
- Components can be React (same skills/Atomic Design as TicketLite) where interactivity is needed.
- Image optimisation at build time (PNG → WebP), cuts the 17 MB of images substantially.

Alternatives considered:
- Keep **Jekyll**: works, but Ruby and an aging theme; a different stack from everything else here.
- **React SPA** (like TicketLite's web): notes rendered in the browser, worse for reading/search, more code.

## 3. Content model

```text
apps/site/
  src/content/notes/<slug>/index.md     one folder per note, images next to it
  src/data/apps.json                    homelab app list (drives dropdown + /homelab)
  src/content/about.md                  intro + CV sections
  public/cv.pdf                         downloadable CV
```

Note front matter (old fields kept, two added):
```yaml
title: "Heap"
date: 2020-10-10
category: tech            # was: categories
lang: vi                  # new: most notes are Vietnamese (sets <html lang>, fonts)
description: "..."        # new, optional: list/RSS summary
```

`apps.json` entry: `{ "name": "TicketLite", "url": "https://ticketlite.hien0ethan.com", "description": "...", "status": "live" }`.
Adding an app = one entry + push.

## 4. Migration from hienmv.github.io

1. Copy the 8 posts into `notes/<slug>/index.md`, images into the same folder; rewrite
   `../assets/x.png` → `./x.png`; `categories` → `category`.
2. Old URLs (`/tech/2020/10/10/heap.html`) 301-redirect to `/notes/heap/` (nginx rules), so links
   shared elsewhere keep working once the domain points here.
3. Keep the original LICENSE terms for the notes content.
4. After you confirm the new site, archive the old repo (GitHub Pages then stops).

## 5. Build, deploy, operate

| Piece | Choice |
|---|---|
| Code | `apps/site/` |
| Image | `ghcr.io/hienmv/site` (Astro build on CI's amd64, served by arm64 `nginx-unprivileged`), same pattern as TicketLite web |
| CI | `.github/workflows/site.yml`: build + checks on PR; on `main` push image, bump tag in `k8s/apps/site/`. The shared build/push/bump steps move into one reusable workflow used by both apps |
| Checks | `astro check` (types, front matter schema), internal link + image checker on the built site, Vitest for the dropdown (keyboard, outside click) |
| Deploy | Flux, `k8s/apps/site/`, Ingress host `hien0ethan.com`; the unknown-subdomain fallback points here. `k8s/apps/landing/` is removed |
| Cost | ~10 MiB RAM, static files only |

## 6. Needed from you

1. **About content:** intro text, CV (PDF and/or text), link URLs (LinkedIn, LeetCode, others; GitHub is `hienmv`).
2. **Site title** in the header (old site: "Hien").
3. **Old URL redirects** (section 4.2): keep, or not needed?
4. **Notes license:** confirm the old repo's LICENSE should carry over.
