/**
 * A small API client for test fixtures: data that is not the subject of a journey is created through the gateway
 * (the same calls as infrastructure/local/scripts/smoke-phase*.sh), so each journey only drives the UI it tests.
 *
 * Uses Node's fetch (no cookie jar: every call carries its own Bearer token). 429 (gateway rate limits: 2 logins/s
 * per IP, 20 requests/s per user) and 503 are retried with a back-off; nothing else is.
 */
export const API_URL = (process.env.API_URL ?? 'http://localhost:8090').replace(/\/+$/, '')
export const ADMIN_EMAIL = process.env.ADMIN_EMAIL ?? 'admin@grocery.local'
export const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? 'Admin@12345'
/** Password of every user a fixture registers (8–64 chars, a letter and a digit). */
export const PASSWORD = process.env.E2E_PASSWORD ?? 'Secret123'

/** One id per test run: correlation ids, e-mails, product names and coupon codes all carry it. */
export const RUN_ID = (process.env.E2E_RUN_ID ?? Date.now().toString(36)).toLowerCase()

const RETRYABLE = new Set([429, 503])
let sequence = 0

export const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Json = any

export interface ApiResult {
  status: number
  body: Json
  text: string
  headers: Headers
}

export class ApiError extends Error {
  constructor(
    readonly method: string,
    readonly path: string,
    readonly status: number,
    readonly text: string,
  ) {
    super(`${method} ${path} → ${status}: ${text.slice(0, 600)}`)
  }
}

export interface CallOptions {
  token?: string
  body?: unknown
  headers?: Record<string, string>
  timeoutMs?: number
}

/** Any call; returns the status and body whatever the status (after retrying 429/503). */
export async function call(method: string, path: string, options: CallOptions = {}): Promise<ApiResult> {
  const url = path.startsWith('http') ? path : `${API_URL}${path}`
  for (let attempt = 1; ; attempt++) {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      'X-Correlation-Id': `e2e-${RUN_ID}-${++sequence}`,
      ...options.headers,
    }
    if (options.token) headers.Authorization = `Bearer ${options.token}`
    let payload: string | undefined
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json'
      payload = JSON.stringify(options.body)
    }
    let response: Response
    try {
      response = await fetch(url, { method, headers, body: payload, signal: AbortSignal.timeout(options.timeoutMs ?? 30_000) })
    } catch (error) {
      // connection refused/reset before any answer: safe to retry a few times
      if (attempt < 5) {
        await sleep(500 * attempt)
        continue
      }
      throw error
    }
    const text = await response.text()
    if (RETRYABLE.has(response.status) && attempt < 15) {
      await sleep(Math.min(400 * attempt, 3_000))
      continue
    }
    let body: Json
    try {
      body = text ? JSON.parse(text) : undefined
    } catch {
      body = undefined
    }
    return { status: response.status, body, text, headers: response.headers }
  }
}

/** A call that must succeed (2xx); returns the parsed body. */
export async function ok<T = Json>(method: string, path: string, options: CallOptions = {}): Promise<T> {
  const result = await call(method, path, options)
  if (result.status < 200 || result.status >= 300) throw new ApiError(method, path, result.status, result.text)
  return result.body as T
}

/** Polls `probe` until it returns something truthy (or throws after `timeoutMs`). */
export async function until<T>(
  label: string,
  probe: () => Promise<T | null | undefined | false>,
  { timeoutMs = 30_000, intervalMs = 500 }: { timeoutMs?: number; intervalMs?: number } = {},
): Promise<T> {
  const deadline = Date.now() + timeoutMs
  let last: unknown
  for (;;) {
    try {
      const value = await probe()
      if (value) return value as T
      last = value
    } catch (error) {
      last = error
    }
    if (Date.now() > deadline) {
      const detail = last instanceof Error ? last.message : JSON.stringify(last)
      throw new Error(`Timed out after ${timeoutMs} ms waiting for: ${label} (last: ${String(detail).slice(0, 400)})`)
    }
    await sleep(intervalMs)
  }
}

/** The (unverified) claims of a JWT. */
export function claims(token: string): Record<string, Json> {
  const part = token.split('.')[1] ?? ''
  return JSON.parse(Buffer.from(part, 'base64url').toString('utf8'))
}

export interface Session {
  email: string
  password: string
  fullName: string
  token: string
  userId: string
}

function toSession(email: string, password: string, response: Json): Session {
  const token = String(response.accessToken)
  return { email, password, fullName: String(response.user?.fullName ?? ''), token, userId: String(claims(token).sub) }
}

export type SelfRole = 'CUSTOMER' | 'VENDOR' | 'RESTAURANT_OWNER' | 'DELIVERY_PARTNER'

export async function register(role: SelfRole, email: string, fullName: string, password = PASSWORD): Promise<Session> {
  const response = await ok('POST', '/api/auth/register', {
    body: { email, password, fullName, phone: '+919876543210', role },
  })
  return toSession(email, password, response)
}

export async function login(email: string, password: string): Promise<Session> {
  return toSession(email, password, await ok('POST', '/api/auth/login', { body: { email, password } }))
}

/** Logs in again until the access token carries `claim` (e.g. vendor_id after an approval). */
export async function reloginUntilClaim(session: Session, claim: string, timeoutMs = 30_000): Promise<Session> {
  return until(
    `${session.email}: a token with ${claim}`,
    async () => {
      const next = await login(session.email, session.password)
      return claims(next.token)[claim] ? next : null
    },
    { timeoutMs, intervalMs: 1_000 },
  )
}
