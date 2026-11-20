import { applyAuthResponse } from '../shared/auth/session'

export function fakeUser(overrides = {}) {
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

export function fakeAuthResponse(accessToken, user = {}) {
  return { accessToken, tokenType: 'Bearer', expiresIn: 900, user: fakeUser(user) }
}

/** Puts the in-memory session straight into "signed in" (no network). */
export function signInAs(roles, overrides = {}, accessToken = 'test-access-token') {
  const auth = fakeAuthResponse(accessToken, { roles, ...overrides })
  applyAuthResponse(auth)
  return auth.user
}
