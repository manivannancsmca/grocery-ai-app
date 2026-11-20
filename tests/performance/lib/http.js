// Shared HTTP helpers for the k6 scenarios (k6 JavaScript runtime, not Node).
import http from 'k6/http'
import { sleep } from 'k6'
import exec from 'k6/execution'
import encoding from 'k6/encoding'

export const API_URL = (__ENV.API_URL || 'http://api-gateway:8080').replace(/\/+$/, '')
export const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@grocery.local'
export const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'Admin@12345'
export const PASSWORD = 'Secret123'

/**
 * The gateway rate-limits per user (20 req/s, burst 40) and, for anonymous calls and logins, per client IP
 * (login 2/s, burst 10). Behind the AWS load balancer every shopper has their own IP; here all virtual users share
 * the k6 container's. With SIMULATE_CLIENT_IPS=true (default) each VU sends its own X-Forwarded-For, i.e. k6 plays
 * the load balancer (the gateway trusts exactly one proxy hop) — otherwise 50 anonymous VUs measure the rate limiter.
 */
const SIMULATE_CLIENT_IPS = (__ENV.SIMULATE_CLIENT_IPS || 'true') === 'true'

let sequence = 0

function clientIp(seed) {
  const n = seed % 65_000
  return `10.13.${Math.floor(n / 250)}.${(n % 250) + 1}`
}

function vuId() {
  try {
    return exec.vu.idInTest || 0
  } catch {
    return 0 // init / setup context
  }
}

/**
 * One API call. `name` groups URLs with ids into one metric series (tag `name`), `expect` lists the statuses that
 * are not failures (default: 2xx/3xx). Retries 429 (rate limited) a few times, as a browser client would.
 */
export function api(method, path, { token, body, name, headers = {}, expect, ip, retries = 3 } = {}) {
  const params = {
    headers: {
      Accept: 'application/json',
      'X-Correlation-Id': `k6-${__ENV.RUN_ID || 'run'}-${vuId()}-${++sequence}`,
      ...headers,
    },
    tags: { name: name || path },
  }
  if (token) params.headers.Authorization = `Bearer ${token}`
  if (SIMULATE_CLIENT_IPS) params.headers['X-Forwarded-For'] = ip || clientIp(vuId() || 1)
  if (expect) params.responseCallback = http.expectedStatuses(...expect)
  let payload = null
  if (body !== undefined) {
    params.headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  let response = http.request(method, `${API_URL}${path}`, payload, params)
  for (let attempt = 1; response.status === 429 && attempt <= retries; attempt++) {
    sleep(0.5 * attempt)
    response = http.request(method, `${API_URL}${path}`, payload, params)
  }
  logUnexpected(method, name || path, response, expect)
  return response
}

/** The first few unexpected answers per VU go to the log (status, errorCode, body) — the "why" behind error rates. */
const LOG_LIMIT = Number(__ENV.LOG_FAILURES || 3)
let logged = 0

function logUnexpected(method, name, response, expect) {
  const ok = expect ? true : response.status >= 200 && response.status < 400
  if (ok || logged >= LOG_LIMIT) return
  logged++
  console.warn(`${method} ${name} → ${response.status} ${String(response.body).slice(0, 300)}`)
}

export function json(response) {
  try {
    return response.json()
  } catch {
    return null
  }
}

/** A call that must succeed (setup only): throws with the body otherwise. */
export function must(method, path, options = {}) {
  const response = api(method, path, { retries: 10, ...options })
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`${method} ${path} → ${response.status}: ${String(response.body).slice(0, 400)}`)
  }
  return json(response)
}

/** Polls `probe` (returns truthy when done) every `interval` s for at most `timeout` s. */
export function until(label, probe, timeout = 30, interval = 0.5) {
  const deadline = Date.now() + timeout * 1000
  for (;;) {
    const value = probe()
    if (value) return value
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${label}`)
    sleep(interval)
  }
}

/** The (unverified) claims of a JWT. */
export function claims(token) {
  return JSON.parse(encoding.b64decode(token.split('.')[1], 'rawurl', 's'))
}

export { clientIp }
