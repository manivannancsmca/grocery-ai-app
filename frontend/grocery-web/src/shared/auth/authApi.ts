import type { AxiosRequestConfig } from 'axios'
import { httpClient } from '../api/httpClient'
import type { AuthResponse, ChangePasswordRequest, LoginRequest, RegisterRequest, ResetPasswordRequest, UserView } from './types'

/**
 * Identity endpoints. Login, register, refresh and logout set or clear the httpOnly refresh cookie
 * (`grocery_rt`, Path=/api/auth), so they send credentials and skip the Bearer/refresh interceptor.
 */
const AUTH_CONFIG: AxiosRequestConfig = { skipAuth: true, withCredentials: true }

/** Required on the cookie-authenticated endpoints (CSRF defence, `403 CSRF_HEADER_MISSING` otherwise). */
const CSRF_HEADERS = { 'X-Requested-With': 'XMLHttpRequest' }

export async function login(body: LoginRequest): Promise<AuthResponse> {
  const response = await httpClient.post<AuthResponse>('/auth/login', body, AUTH_CONFIG)
  return response.data
}

export async function register(body: RegisterRequest): Promise<AuthResponse> {
  const response = await httpClient.post<AuthResponse>('/auth/register', body, AUTH_CONFIG)
  return response.data
}

export async function refresh(): Promise<AuthResponse> {
  const response = await httpClient.post<AuthResponse>('/auth/refresh', null, { ...AUTH_CONFIG, headers: CSRF_HEADERS })
  return response.data
}

export async function logout(): Promise<void> {
  await httpClient.post('/auth/logout', null, { ...AUTH_CONFIG, headers: CSRF_HEADERS })
}

export async function fetchMe(): Promise<UserView> {
  const response = await httpClient.get<UserView>('/auth/me')
  return response.data
}

/**
 * Signed in (Bearer token). The refresh cookie travels along (its path is /api/auth) so identity keeps this session and
 * signs out every other one. The current access token is revoked too: refresh afterwards.
 */
export async function changePassword(body: ChangePasswordRequest): Promise<void> {
  await httpClient.post('/auth/password/change', body, { withCredentials: true })
}

/** Public. Always `202`, whether or not the address has an account: the UI says the same thing either way. */
export async function requestPasswordReset(email: string): Promise<void> {
  await httpClient.post('/auth/password/forgot', { email }, { skipAuth: true })
}

/** Public: the token of the emailed link (`400 RESET_TOKEN_INVALID` when unknown, used or expired). Signs nobody in. */
export async function resetPassword(body: ResetPasswordRequest): Promise<void> {
  await httpClient.post('/auth/password/reset', body, { skipAuth: true })
}
