import { setAuthHooks } from '../api/httpClient'
import { hasErrorCode, toApiError } from '../api/problemDetail'
import * as authApi from './authApi'

/** @typedef {import('../api/problemDetail').ApiError} ApiError */
/** @typedef {import('./types').AuthResponse} AuthResponse */
/** @typedef {import('./types').ChangePasswordRequest} ChangePasswordRequest */
/** @typedef {import('./types').LoginRequest} LoginRequest */
/** @typedef {import('./types').RegisterRequest} RegisterRequest */
/** @typedef {import('./types').UserView} UserView */

/**
 * The browser session, kept outside React so the axios interceptor can read and renew it.
 *
 * - The access token lives in this module's memory only (never localStorage/sessionStorage).
 * - The refresh token is the httpOnly `grocery_rt` cookie; JavaScript never sees it.
 * - At most one `POST /api/auth/refresh` is in flight; every caller joins it.
 *
 * React reads the state through `useSyncExternalStore` (see AuthProvider).
 */

/** @typedef {'initializing' | 'authenticated' | 'anonymous' | 'suspended'} SessionStatus */

/**
 * Why an authenticated session ended; the login page explains it.
 * @typedef {'expired' | 'signed-out'} SessionEndReason
 */

/**
 * @typedef {object} SessionState
 * @property {SessionStatus} status
 * @property {UserView | null} user
 * @property {SessionEndReason | null} endReason
 * @property {string | null} suspendedMessage Server message for `403 ACCOUNT_SUSPENDED`.
 */

/** @type {SessionState} */
const INITIAL_STATE = { status: 'initializing', user: null, endReason: null, suspendedMessage: null }

/** Delay before retrying a refresh that lost a race (`409 REFRESH_RACE`) so the rotated cookie has landed. */
export const REFRESH_RACE_RETRY_DELAY_MS = 200

/** @type {string | null} */
let accessToken = null
/** @type {SessionState} */
let state = INITIAL_STATE
/** @type {Promise<AuthResponse> | null} */
let refreshInFlight = null
let bootstrapStarted = false
const listeners = new Set()

/**
 * A name the user just saved on their customer profile, which identity has not caught up with yet: the profile service
 * tells identity by event, a second or two later. Until then a refresh still returns one of the `staleNames`, and the
 * session keeps showing `fullName` instead, so the header never flips back to the old name.
 * @typedef {object} PendingName
 * @property {string} userId
 * @property {string} fullName
 * @property {ReadonlySet<string>} staleNames
 */

/** @type {PendingName | null} */
let pendingName = null

/**
 * The user as identity reports it, with a name saved meanwhile that identity does not know yet.
 * @param {UserView} user
 * @returns {UserView}
 */
function withPendingName(user) {
  if (!pendingName) return user
  if (pendingName.userId === user.id && pendingName.staleNames.has(user.fullName)) {
    return { ...user, fullName: pendingName.fullName }
  }
  // identity has the saved name now (or another user, or a name changed elsewhere): identity is right again
  pendingName = null
  return user
}

/**
 * @param {SessionState} next
 * @returns {void}
 */
function setState(next) {
  state = next
  listeners.forEach((listener) => listener())
}

export const sessionStore = {
  subscribe(listener) {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },
  getSnapshot() {
    return state
  },
}

/** @returns {string | null} */
export function getAccessToken() {
  return accessToken
}

/**
 * @param {AuthResponse} auth
 * @returns {void}
 */
export function applyAuthResponse(auth) {
  accessToken = auth.accessToken
  setState({ status: 'authenticated', user: withPendingName(auth.user), endReason: null, suspendedMessage: null })
}

/**
 * Shows details the user just saved elsewhere (their customer profile) in the session at once: the header and the menu
 * read the session user. The new name reaches identity by event; until a refresh returns it, refreshes keep it.
 * @param {Pick<UserView, 'fullName'> & Partial<Pick<UserView, 'phone'>>} changes
 * @returns {void}
 */
export function patchSessionUser(changes) {
  const user = state.user
  if (state.status !== 'authenticated' || !user) return
  if (changes.fullName !== user.fullName) {
    // Every name the session showed before may still come back from identity until it has the new one.
    const staleNames = new Set(pendingName?.userId === user.id ? pendingName.staleNames : [])
    staleNames.add(user.fullName)
    staleNames.delete(changes.fullName)
    pendingName = { userId: user.id, fullName: changes.fullName, staleNames }
  }
  setState({ ...state, user: { ...user, ...changes } })
}

/**
 * @param {SessionEndReason | null} reason
 * @returns {void}
 */
export function endSession(reason) {
  accessToken = null
  pendingName = null
  setState({ status: 'anonymous', user: null, endReason: reason, suspendedMessage: null })
}

/**
 * @param {string | null} message
 * @returns {void}
 */
export function markSuspended(message) {
  accessToken = null
  pendingName = null
  setState({ status: 'suspended', user: null, endReason: null, suspendedMessage: message })
}

/**
 * @param {number} ms
 * @returns {Promise<void>}
 */
function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** @returns {Promise<AuthResponse>} */
async function refreshWithRaceRetry() {
  try {
    return await authApi.refresh()
  } catch (error) {
    // Another tab (or request) rotated the cookie at the same moment — retry once (contract §3).
    if (hasErrorCode(error, 'REFRESH_RACE')) {
      await delay(REFRESH_RACE_RETRY_DELAY_MS)
      return authApi.refresh()
    }
    throw error
  }
}

/**
 * A 4xx from /auth/refresh means the session is over; network errors and 5xx may be transient.
 * @param {ApiError} error
 * @returns {boolean}
 */
function sessionIsOver(error) {
  return error.kind === 'http' && error.status !== null && error.status < 500
}

/** @returns {Promise<AuthResponse>} */
async function runRefresh() {
  const statusBefore = state.status
  try {
    const auth = await refreshWithRaceRetry()
    applyAuthResponse(auth)
    return auth
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.status === 403 && apiError.errorCode === 'ACCOUNT_SUSPENDED') {
      markSuspended(apiError.message)
    } else if (statusBefore === 'initializing') {
      endSession(null)
    } else if (statusBefore === 'authenticated' && sessionIsOver(apiError)) {
      endSession('expired')
    }
    // else: the gateway is unreachable — keep the signed-in UI; the next request tries again.
    throw apiError
  }
}

/**
 * Renews the access token with the refresh cookie. Concurrent callers share one request, so a burst
 * of 401s produces a single `POST /api/auth/refresh`.
 * @returns {Promise<AuthResponse>}
 */
export function refreshSession() {
  refreshInFlight ??= runRefresh().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

/**
 * Silent refresh on app start: turns a valid cookie into a session, otherwise the visitor is anonymous.
 * @returns {void}
 */
export function bootstrapSession() {
  if (bootstrapStarted || state.status !== 'initializing') return
  bootstrapStarted = true
  refreshSession().catch(() => {
    // State already reflects the outcome (anonymous / suspended).
  })
}

/**
 * @param {LoginRequest} request
 * @returns {Promise<UserView>}
 */
export async function signIn(request) {
  try {
    const auth = await authApi.login(request)
    applyAuthResponse(auth)
    return auth.user
  } catch (error) {
    if (hasErrorCode(error, 'ACCOUNT_SUSPENDED')) markSuspended(error.message)
    throw error
  }
}

/**
 * @param {RegisterRequest} request
 * @returns {Promise<UserView>}
 */
export async function signUp(request) {
  const auth = await authApi.register(request)
  applyAuthResponse(auth)
  return auth.user
}

/** @returns {Promise<void>} */
export async function signOut() {
  try {
    await authApi.logout()
  } catch {
    // The local session ends regardless; the cookie expires on its own if the call failed.
  } finally {
    endSession('signed-out')
  }
}

/**
 * Changes the signed-in user's password. identity signs out every other session and revokes the access tokens issued
 * so far; this session keeps its refresh cookie, so it renews its access token right away.
 * @param {ChangePasswordRequest} request
 * @returns {Promise<void>}
 */
export async function changeOwnPassword(request) {
  await authApi.changePassword(request)
  try {
    await refreshSession()
  } catch {
    // The state already says what happened; otherwise the next request refreshes through the interceptor.
  }
}

/**
 * Leaves the "account suspended" screen.
 * @returns {void}
 */
export function acknowledgeSuspension() {
  endSession(null)
}

setAuthHooks({
  getAccessToken,
  refreshAccessToken: refreshSession,
  onAccountSuspended: (error) => markSuspended(error.message),
})

/**
 * Test helper: start every test from a known session state.
 * @param {SessionStatus} [status='anonymous']
 * @returns {void}
 */
export function resetSessionForTests(status = 'anonymous') {
  accessToken = null
  pendingName = null
  refreshInFlight = null
  bootstrapStarted = false
  state = { ...INITIAL_STATE, status }
  listeners.forEach((listener) => listener())
}
