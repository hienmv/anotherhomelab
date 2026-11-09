import { defineConfig } from 'astro/config'

export default defineConfig({
  site: 'https://hien0ethan.com',
  trailingSlash: 'always',
  build: { format: 'directory' },
})
