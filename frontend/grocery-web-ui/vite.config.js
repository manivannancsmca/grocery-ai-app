import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Where the dev server forwards /api/** — the API gateway.
// Host run: http://localhost:8080 (default). Inside Docker: http://host.docker.internal:8080 or http://api-gateway:8080.
const proxyTarget = process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080'

// Windows bind mounts into Linux containers do not deliver file-change events,
// so a containerized dev server must poll to get hot reload.
const usePolling = process.env.VITE_USE_POLLING === 'true'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '0.0.0.0',
    // 6420 belongs to the original grocery-web app, so both can run side by side.
    port: 6421,
    strictPort: true,
    watch: usePolling ? { usePolling: true, interval: 300 } : undefined,
    proxy: {
      '/api': {
        target: proxyTarget,
        changeOrigin: true,
        // The browser talks to this dev server on its own origin, so the gateway does not need the Origin header.
        // Dropping it keeps the gateway's CORS check (which only lists http://localhost:6420, the original app)
        // from rejecting requests made from this app on port 6421.
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'))
        },
      },
    },
  },
  preview: {
    host: '0.0.0.0',
    port: 4174,
    strictPort: true,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
    css: false,
    // Cap parallel jsdom workers so a memory-capped container is never pushed over its limit.
    maxWorkers: Number(process.env.VITEST_MAX_WORKERS ?? 2),
  },
})
