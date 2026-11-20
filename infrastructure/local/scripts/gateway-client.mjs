// =====================================================================================
// Local dev: a tiny client for the API gateway, shared by seed-chennai.mjs and verify-workflow.mjs.
// Every call goes through the gateway exactly like the web app's calls (no database access).
// =====================================================================================

export const GATEWAY_URL = process.env.GATEWAY_URL ?? 'http://localhost:8080'

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

export const log = (message) => console.log(message)

/**
 * One API call through the gateway: `{ status, data }` (data is the parsed JSON body, or null). The gateway limits how
 * fast accounts can be registered or signed in (HTTP 429): the call then waits as long as the server asks and retries.
 */
export async function call(method, path, token, body, headers = {}) {
  for (let attempt = 1; ; attempt++) {
    const result = await send(method, path, token, body, headers)
    if (result.status !== 429 || attempt === 20) return result
    await sleep(Math.max(1, Number(result.retryAfter) || 5) * 1000)
  }
}

async function send(method, path, token, body, headers) {
  const response = await fetch(GATEWAY_URL + path, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      'X-Correlation-Id': `local-script-${Date.now()}-${Math.floor(Math.random() * 1e6)}`,
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const text = await response.text()
  let data = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  return { status: response.status, data, retryAfter: response.headers.get('retry-after') }
}

/** A call that must succeed (2xx); otherwise the script stops with the API's answer. */
export async function must(label, method, path, token, body, headers) {
  const result = await call(method, path, token, body, headers)
  if (result.status >= 300) throw new Error(`${label}: HTTP ${result.status} ${JSON.stringify(result.data).slice(0, 400)}`)
  return result
}

/** Repeats `read` until `done(result)` is true (events take a moment to reach the other services). */
export async function waitFor(label, read, done, seconds = 30) {
  const deadline = Date.now() + seconds * 1000
  for (;;) {
    const result = await read()
    if (done(result)) return result
    if (Date.now() > deadline) throw new Error(`${label}: timed out after ${seconds} s (last answer: HTTP ${result.status})`)
    await sleep(500)
  }
}

/** Signs in: the access token, or null when the e-mail and password do not sign in. */
export async function login(email, password) {
  const result = await call('POST', '/api/auth/login', null, { email, password })
  return result.status === 200 ? result.data.accessToken : null
}

/** The claims inside an access token (its middle part): roles, vendor_id, restaurant_id… */
export function claims(token) {
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'))
}
