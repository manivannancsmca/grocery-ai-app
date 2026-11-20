// Serves APP_URL's port on this machine's loopback and pipes every connection to FRONTEND_UPSTREAM (host:port).
//
// Why: inside the Playwright container the frontend is reachable as `frontend:6420` (compose network) or
// `host.docker.internal:6420`, but the browser must still talk to `http://localhost:6420`:
//   - Vite's dev server only answers Host headers it allows (localhost and IP addresses by default);
//   - the gateway's CORS allow-list is `http://localhost:6420` (GATEWAY_ALLOWED_ORIGINS);
//   - the refresh cookie is `Secure`, which Chromium accepts over plain http only for localhost.
// So the browser opens localhost:6420 and this forwarder carries the bytes (HTTP, SSE, Vite's HMR websocket).
// Playwright starts it as its `webServer` when FRONTEND_UPSTREAM is set (see playwright.config.ts).
import net from 'node:net'

const appUrl = new URL(process.env.APP_URL ?? 'http://localhost:6420')
const listenPort = Number(appUrl.port || (appUrl.protocol === 'https:' ? 443 : 80))
const upstream = process.env.FRONTEND_UPSTREAM ?? 'frontend:6420'
const [upstreamHost, upstreamPortText] = upstream.split(':')
const upstreamPort = Number(upstreamPortText ?? 80)

function handle(client) {
  const target = net.connect({ host: upstreamHost, port: upstreamPort })
  client.pipe(target)
  target.pipe(client)
  const close = () => {
    client.destroy()
    target.destroy()
  }
  client.on('error', close)
  target.on('error', close)
  client.on('close', close)
  target.on('close', close)
}

function listen(host) {
  return new Promise((resolve, reject) => {
    const server = net.createServer(handle)
    server.once('error', reject)
    server.listen({ host, port: listenPort }, () => resolve(server))
  })
}

// Chromium may resolve localhost to ::1 or 127.0.0.1: listen on both when the container has IPv6 loopback.
await listen('127.0.0.1')
try {
  await listen('::1')
} catch {
  // no IPv6 loopback in this container: IPv4 is enough
}
console.log(`[tcp-forward] ${appUrl.hostname}:${listenPort} -> ${upstreamHost}:${upstreamPort}`)
