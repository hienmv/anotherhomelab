# Landing (hien0ethan.com)

Home page: short intro, featured notes, the homelab apps, and About. Astro static build on nginx.

## Edit content

| What | Where |
|---|---|
| Intro, About text, CV, links | `src/data/profile.json` (links with `"url": null` are hidden; `cv.pdf`: path to a PDF in `public/`) |
| Homelab apps (dropdown + list) | `src/data/apps.json` (`name`, `url`, `description`, `status`) |
| Which notes are featured | in the notes app: `featured: true` in a note's front matter (no landing rebuild needed) |

## How featured notes work

The page loads `/featured.json`. nginx proxies it from the notes Service inside the cluster
(`NOTES_UPSTREAM`, default `http://notes.apps.svc.cluster.local`), so the browser stays same-origin.
nginx resolves that name per request, so the landing starts even if notes is down; the page then keeps
its "Browse all notes" fallback. Titles are inserted with `textContent` (no HTML injection).

## Redirects

Old apex URLs move to notes.hien0ethan.com: `/notes/<slug>/`, old Jekyll `/tech/.../<slug>.html`,
`/rss.xml`, `/category/...` (301, see `nginx.conf.template`).

## Checks (in the Docker build)

`astro check`, `vitest` (dropdown keyboard/Escape/outside click; featured rendering, XSS-safe,
notes-down fallback), `astro build`, internal link checker.

## Run locally with notes

```bash
docker network create hl
docker run -d --name notes --network hl notes:dev
docker run --rm --network hl -p 8092:8080 -e NOTES_UPSTREAM=http://notes:8080 landing:dev
```
