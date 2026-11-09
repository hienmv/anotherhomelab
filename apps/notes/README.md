# Notes (notes.hien0ethan.com)

Notes migrated from hienmv.github.io (Jekyll). Astro static build served by nginx in the k3s cluster.
The landing page (hien0ethan.com, `apps/landing`) shows featured notes, the homelab apps and About.
Hostnames and redirects: [docs/domains.md](../../docs/domains.md).

## Edit content

| What | Where |
|---|---|
| A note | `src/content/notes/<slug>/index.md`, images next to it as `./image.png`. Front matter: `title`, `date`, `category`, `lang` (`vi`/`en`), optional `description`, `featured: true` to show it on the landing page |
| Site title / tagline | `src/data/site.json` |

Commit and push: CI builds and checks, Flux deploys (about 3 to 5 minutes).

## URLs

| Path | Content |
|---|---|
| `/` | all notes, newest first |
| `/<slug>/` | one note |
| `/category/<name>/` | notes in one category |
| `/rss.xml` | feed |
| `/featured.json` | notes with `featured: true` (or the 3 newest if none); read by the landing page |
| `/about/` | 301 to https://hien0ethan.com/about/ |
| `/tech/2020/10/10/heap.html` (old Jekyll) | 301 to `/heap/` |

## Checks (in the Docker build, so CI fails on any of them)

1. `astro check`: TypeScript and the front matter schema (`src/content.config.ts`).
2. `vitest`: featured-note selection.
3. `astro build`: images to WebP (animated GIFs stay animated).
4. `scripts/check-links.mjs`: every internal link and image must exist.

## Run locally

```bash
docker buildx build --load -t notes:dev apps/notes && docker run --rm -p 8091:8080 notes:dev
```

## Migration (2026-11-09)

8 posts, 72 referenced images (8.9 MB, 5.1 MB as WebP), MIT license kept. `lang: vi` detected per note.
