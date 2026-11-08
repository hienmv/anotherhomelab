import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// `npm run dev`: proxies /api to a backend on localhost:8081 (e.g. docker compose api).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://localhost:8081' },
  },
})
