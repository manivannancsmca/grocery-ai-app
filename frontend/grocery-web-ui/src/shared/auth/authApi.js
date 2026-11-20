import { httpClient } from '../api/httpClient'

/** @typedef {import('axios').AxiosRequestConfig} AxiosRequestConfig */
/** @typedef {import('./types').AuthResponse} AuthResponse */
/** @typedef {import('./types').ChangePasswordRequest} ChangePasswordRequest */
/** @typedef {import('./types').LoginRequest} LoginRequest */
/** @typedef {import('./types').RegisterRequest} RegisterRequest */
/** @typedef {import('./types').ResetPasswordRequest} ResetPasswordRequest */
/** @typedef {import('./types').UserView} UserView */

/**
 * Identity endpoints. Login, register, refresh and logout set or clear the httpOnly refresh cookie
 * (`grocery_rt`, Path=/api/auth), so they send credentials and skip the Bearer/refresh interceptor.
 * @type {AxiosRequestConfig}
 */
const AUTH_CONFIG = { skipAuth: true, withCredentials: true }

/** Required on the cookie-authenticated endpoints (CSRF defence, `403 CSRF_HEADER_MISSING` otherwise). */
const CSRF_HEADERS = { 'X-Requested-With': 'XMLHttpRequest' }

/**
 * @param {LoginRequest} body
 * @returns {Promise<AuthResponse>}
 */
export async function login(body) {
  const response = await httpClient.post('/auth/login', body, AUTH_CONFIG)
  return response.data
}

/**
 * @param {RegisterRequest} body
 * @returns {Promise<AuthResponse>}
 */
export async function register(body) {
  const response = await httpClient.post('/auth/register', body, AUTH_CONFIG)
  return response.data
}

/** @returns {Promise<AuthResponse>} */
export async function refresh() {
  const response = await httpClient.post('/auth/refresh', null, { ...AUTH_CONFIG, headers: CSRF_HEADERS })
  return response.data
}

/** @returns {Promise<void>} */
export async function logout() {
  await httpClient.post('/auth/logout', null, { ...AUTH_CONFIG, headers: CSRF_HEADERS })
}

/** @returns {Promise<UserView>} */
export async function fetchMe() {
  const response = await httpClient.get('/auth/me')
  return response.data
}

/**
 * Signed in (Bearer token). The refresh cookie travels along (its path is /api/auth) so identity keeps this session and
 * signs out every other one. The current access token is revoked too: refresh afterwards.
 * @param {ChangePasswordRequest} body
 * @returns {Promise<void>}
 */
export async function changePassword(body) {
  await httpClient.post('/auth/password/change', body, { withCredentials: true })
}

/**
 * Public. Always `202`, whether or not the address has an account: the UI says the same thing either way.
 * @param {string} email
 * @returns {Promise<void>}
 */
export async function requestPasswordReset(email) {
  await httpClient.post('/auth/password/forgot', { email }, { skipAuth: true })
}

/**
 * Public: the token of the emailed link (`400 RESET_TOKEN_INVALID` when unknown, used or expired). Signs nobody in.
 * @param {ResetPasswordRequest} body
 * @returns {Promise<void>}
 */
export async function resetPassword(body) {
  await httpClient.post('/auth/password/reset', body, { skipAuth: true })
}
