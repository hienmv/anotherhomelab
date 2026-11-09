// Fails the build if any internal link or image in the built site points to a missing file.
// usage: node scripts/check-links.mjs dist
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'

const root = resolve(process.argv[2] ?? 'dist')
const htmlFiles = []
const walk = (d) => readdirSync(d).forEach((f) => {
  const p = join(d, f)
  statSync(p).isDirectory() ? walk(p) : p.endsWith('.html') && htmlFiles.push(p)
})
walk(root)

const exists = (target) =>
  [target, join(target, 'index.html'), `${target}.html`].some((p) => existsSync(p) && statSync(p).isFile())

const broken = []
let checked = 0
for (const file of htmlFiles) {
  const html = readFileSync(file, 'utf8')
  for (const [, url] of html.matchAll(/\s(?:href|src)="([^"]+)"/g)) {
    if (/^(https?:|mailto:|data:|#|\/\/)/.test(url)) continue
    const path = decodeURI(url.split(/[?#]/)[0])
    const target = path.startsWith('/') ? join(root, path) : join(dirname(file), path)
    checked++
    if (!exists(target)) broken.push(`${file.replace(root, '')} -> ${url}`)
  }
}
console.log(`checked ${checked} internal links/images in ${htmlFiles.length} pages`)
if (broken.length) {
  console.error(`BROKEN (${broken.length}):\n  ` + broken.join('\n  '))
  process.exit(1)
}
