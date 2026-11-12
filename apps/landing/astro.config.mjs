import { defineConfig } from 'astro/config'

// Site values come from the build environment: CI passes SITE_DOMAIN / SITE_TIMEZONE from
// clusters/<cluster>/site-settings.yaml. Local: SITE_DOMAIN=example.test SITE_TIMEZONE=UTC npm run dev
const required = (name) => {
  const value = process.env[name]
  if (!value) throw new Error(`${name} is not set (DOMAIN/TIMEZONE in clusters/<cluster>/site-settings.yaml)`)
  return value
}
const domain = required('SITE_DOMAIN')

export default defineConfig({
  site: `https://${domain}`,
  trailingSlash: 'always',
  build: { format: 'directory' },
  vite: { define: { __SITE_DOMAIN__: JSON.stringify(domain) } },
})
