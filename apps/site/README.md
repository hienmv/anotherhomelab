# Site (hien0ethan.com)

Personal site: notes (migrated from hienmv.github.io), a menu of homelab apps, and an about page.
Astro static build served by nginx in the k3s cluster. Design and decisions: [DESIGN.md](DESIGN.md).

## Edit content

| What | Where |
|---|---|
| A note | `src/content/notes/<slug>/index.md` (front matter: `title`, `date`, `category`, `lang`, optional `description`); images next to it, referenced as `./image.png` |
| Homelab menu | `src/data/apps.json` (`name`, `url`, `description`, `status`) |
| About page, links, site title | `src/data/profile.json` (entries with `"url": null` are hidden; `cv.pdf`: path to a PDF in `public/`) |

Commit and push: CI builds and checks, Flux deploys (about 3 to 5 minutes).

## Run locally

```bash
cd apps/site
docker run --rm -it -p 4321:4321 -v "$PWD":/src -w /src node:22-alpine sh -c "npm ci && npx astro dev --host"
# or the production image:
docker buildx build --load -t site:dev . && docker run --rm -p 8090:8080 site:dev   # http://localhost:8090
```

## Checks (run in the Docker build, so CI fails on any of them)

1. `astro check`: TypeScript and the note front matter schema (`src/content.config.ts`).
2. `vitest`: the Homelab dropdown (click, keyboard, Escape, outside click).
3. `astro build`: images converted to WebP (animated GIFs stay animated).
4. `scripts/check-links.mjs`: every internal link and image in the built site must exist.

## Migration notes (2026-11-09)

- 8 posts from `hienmv.github.io` (Jekyll), 72 referenced images (8.9 MB → 5.1 MB as WebP). Notes keep
  their MIT license.
- Old URLs like `/tech/2020/10/10/heap.html` 301-redirect to `/notes/heap/` (`nginx.conf`).
- `lang: vi` is set per note (detected from Vietnamese characters) for correct `<html lang>`.
