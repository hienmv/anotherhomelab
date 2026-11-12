/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Display time zone: SITE_TIMEZONE (CI build argument from clusters/<cluster>/site-settings.yaml);
// tests use UTC. `npm run dev`: SITE_TIMEZONE=UTC npm run dev
// (no @types/node in this app: read the environment through a typed globalThis lookup)
const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {}
const timezone = env.SITE_TIMEZONE ?? (env.VITEST ? 'UTC' : undefined)
if (!timezone) throw new Error('SITE_TIMEZONE is not set (TIMEZONE in clusters/<cluster>/site-settings.yaml)')

// `npm run dev`: proxies /api to a backend on localhost:8081 (e.g. docker compose api).
export default defineConfig({
  plugins: [react()],
  define: { __SITE_TIMEZONE__: JSON.stringify(timezone) },
  server: {
    proxy: { '/api': 'http://localhost:8081' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
