import { createContext } from 'react'
import type { Permission } from './permissions'
import type { RoleArea, UserRole } from './roles'
import type { SessionEndReason, SessionStatus } from './session'
import type { LoginRequest, RegisterRequest, UserView } from './types'

export interface AuthContextValue {
  status: SessionStatus
  user: UserView | null
  roles: readonly UserRole[]
  /** The UI area the user belongs to; null while signed out. */
  area: RoleArea | null
  isAuthenticated: boolean
  isAdmin: boolean
  endReason: SessionEndReason | null
  suspendedMessage: string | null
  hasRole: (...roles: readonly UserRole[]) => boolean
  can: (permission: Permission) => boolean
  login: (request: LoginRequest) => Promise<UserView>
  register: (request: RegisterRequest) => Promise<UserView>
  logout: () => Promise<void>
  /** Renews the access token now (e.g. after an approval, to pick up `vendor_id`). */
  refresh: () => Promise<UserView>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
