import { defineConfig } from 'vitest/config'

export default defineConfig({
  // fixed test values: tests never depend on the real site
  define: { __SITE_DOMAIN__: JSON.stringify('example.test') },
  test: { environment: 'jsdom', include: ['src/**/*.test.ts'] },
})
