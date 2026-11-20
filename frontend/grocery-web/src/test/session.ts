import type { UserRole } from '../shared/auth/roles'
import { applyAuthResponse } from '../shared/auth/session'
import type { AuthResponse, UserView } from '../shared/auth/types'

export function fakeUser(overrides: Partial<UserView> = {}): UserView {
  return {
    id: 'user-1',
    email: 'asha@example.com',
    fullName: 'Asha Rao',
    phone: '+919876543210',
    status: 'ACTIVE',
    roles: ['CUSTOMER'],
    vendorId: null,
    restaurantId: null,
    businesses: [],
    ...overrides,
  }
}

export function fakeAuthResponse(accessToken: string, user: Partial<UserView> = {}): AuthResponse {
  return { accessToken, tokenType: 'Bearer', expiresIn: 900, user: fakeUser(user) }
}

/** Puts the in-memory session straight into "signed in" (no network). */
export function signInAs(roles: UserRole[], overrides: Partial<UserView> = {}, accessToken = 'test-access-token'): UserView {
  const auth = fakeAuthResponse(accessToken, { roles, ...overrides })
  applyAuthResponse(auth)
  return auth.user
}
