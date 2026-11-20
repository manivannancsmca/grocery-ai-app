import type { SelfRegistrationRole, UserRole } from './roles'

/** Status of a business linked to a user, as identity sees it. */
export type BusinessLinkStatus = 'PENDING' | 'ACTIVE' | 'REJECTED' | 'SUSPENDED'

export interface BusinessLink {
  type: 'VENDOR' | 'RESTAURANT' | (string & {})
  businessId: string
  status: BusinessLinkStatus | (string & {})
}

export type UserStatus = 'ACTIVE' | 'SUSPENDED' | (string & {})

/** `UserView` — the signed-in user (contract §3). */
export interface UserView {
  id: string
  email: string
  fullName: string
  phone: string | null
  status: UserStatus
  roles: UserRole[]
  /** Set only while the vendor is APPROVED (mirrors the `vendor_id` claim). */
  vendorId: string | null
  /** Set only while the restaurant is APPROVED (mirrors the `restaurant_id` claim). */
  restaurantId: string | null
  businesses?: BusinessLink[]
}

export interface AuthResponse {
  accessToken: string
  tokenType: 'Bearer' | (string & {})
  /** Seconds until the access token expires (900). */
  expiresIn: number
  user: UserView
}

export interface LoginRequest {
  email: string
  password: string
}

/** `POST /api/auth/password/change` (signed in). */
export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

/** `POST /api/auth/password/reset`: the token of the emailed link. */
export interface ResetPasswordRequest {
  token: string
  newPassword: string
}

export interface RegisterRequest {
  email: string
  password: string
  fullName: string
  phone?: string
  role: SelfRegistrationRole
}
