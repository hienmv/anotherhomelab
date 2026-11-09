import { defineConfig } from 'astro/config'

export default defineConfig({
  site: 'https://notes.hien0ethan.com',
  trailingSlash: 'always',
  build: { format: 'directory' },
  markdown: {
    shikiConfig: { themes: { light: 'github-light', dark: 'github-dark' } },
  },
})
