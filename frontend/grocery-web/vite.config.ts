/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Where the dev server forwards /api/** — the API gateway.
// Host run: http://localhost:8080 (default). Inside Docker Compose: http://api-gateway:8080.
const proxyTarget = process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080'

// Windows bind mounts into Linux containers do not deliver file-change events,
// so the containerized dev server must poll to get hot reload.
const usePolling = process.env.VITE_USE_POLLING === 'true'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '0.0.0.0',
    port: 6420,
    strictPort: true,
    watch: usePolling ? { usePolling: true, interval: 300 } : undefined,
    proxy: {
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
      },
    },
  },
  preview: {
    host: '0.0.0.0',
    port: 4173,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // Tests often run inside the memory-capped dev container next to the Vite server:
    // cap parallel jsdom workers so the container is never pushed over its limit.
    maxWorkers: Number(process.env.VITEST_MAX_WORKERS ?? 2),
  },
})
