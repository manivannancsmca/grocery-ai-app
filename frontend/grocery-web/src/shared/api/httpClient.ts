import axios, { isAxiosError, type InternalAxiosRequestConfig } from 'axios'
import { CORRELATION_ID_HEADER, newCorrelationId } from './correlation'
import { type ApiError, toApiError } from './problemDetail'

declare module 'axios' {
  interface AxiosRequestConfig {
    /**
     * Public or auth endpoint: never attach the access token and never run the refresh-on-401 logic.
     * Used by /auth/* (the refresh call must not recurse) and /health/* (an expired token must not
     * turn a public check into a 401).
     */
    skipAuth?: boolean
    /** Internal: set once the request has been retried after a token refresh. */
    _authRetried?: boolean
  }
}

/**
 * Hooks the session layer (src/shared/auth/session.ts) registers at load time. Keeping them as a
 * registration keeps this module free of auth imports (no import cycle with the auth API module).
 */
export interface AuthHooks {
  /** The in-memory access token, or null when signed out. */
  getAccessToken: () => string | null
  /** Runs (or joins) the single shared refresh. Rejects when the session cannot be renewed. */
  refreshAccessToken: () => Promise<unknown>
  /** Called for `403 ACCOUNT_SUSPENDED` on any request. */
  onAccountSuspended: (error: ApiError) => void
}

let authHooks: AuthHooks | null = null

export function setAuthHooks(hooks: AuthHooks | null): void {
  authHooks = hooks
}

/** 401 codes that mean "your access token is stale" — a refresh can fix them (contract §1). */
const REFRESHABLE_401_CODES = new Set(['TOKEN_EXPIRED', 'TOKEN_REVOKED', 'INVALID_TOKEN', 'UNAUTHENTICATED'])

function isRefreshable(error: ApiError): boolean {
  // A bare 401 without a body (e.g. from a proxy) is treated like UNAUTHENTICATED.
  return error.status === 401 && (error.errorCode === null || REFRESHABLE_401_CODES.has(error.errorCode))
}

function bearerToken(config: InternalAxiosRequestConfig): string | null {
  const header: unknown = config.headers.get('Authorization')
  return typeof header === 'string' && header.startsWith('Bearer ') ? header.slice('Bearer '.length) : null
}

/**
 * Shared HTTP client for every backend call. Paths are relative to /api, so
 * `httpClient.get('/customers/me')` requests `/api/customers/me`.
 * In dev, Vite proxies /api to the API gateway; in production the SPA and gateway
 * share one origin behind the load balancer.
 */
export const httpClient = axios.create({
  baseURL: '/api',
  timeout: 10_000,
  headers: { Accept: 'application/json' },
})

httpClient.interceptors.request.use((config) => {
  // A retried request keeps its correlation ID, so both attempts show up under one ID in the logs.
  if (!config.headers.has(CORRELATION_ID_HEADER)) {
    config.headers.set(CORRELATION_ID_HEADER, newCorrelationId())
  }
  const token = config.skipAuth ? null : (authHooks?.getAccessToken() ?? null)
  if (token) {
    config.headers.setAuthorization(`Bearer ${token}`)
  }
  return config
})

httpClient.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    const apiError = toApiError(error)
    const config = isAxiosError(error) ? error.config : undefined
    const hooks = authHooks

    if (!hooks || !config || config.skipAuth) {
      throw apiError
    }

    const tokenUsed = bearerToken(config)
    // Only requests that carried a token are renewed: a signed-out request has no session to refresh.
    if (isRefreshable(apiError) && !config._authRetried && tokenUsed !== null) {
      config._authRetried = true
      const current = hooks.getAccessToken()
      if (current === null) throw apiError // the session ended while this request was in flight
      // If another request already refreshed meanwhile, just retry with the new token.
      if (current === tokenUsed) {
        try {
          await hooks.refreshAccessToken()
        } catch {
          // The session layer has already cleared the session (→ login page); surface the original 401.
          throw apiError
        }
      }
      return httpClient.request(config)
    }

    if (apiError.status === 403 && apiError.errorCode === 'ACCOUNT_SUSPENDED') {
      hooks.onAccountSuspended(apiError)
    }
    throw apiError
  },
)
