import { useQueryClient } from '@tanstack/react-query'
import { type ReactNode, useCallback, useEffect, useMemo, useRef, useSyncExternalStore } from 'react'
import { AuthContext, type AuthContextValue } from './authContext'
import { hasPermission, type Permission } from './permissions'
import { areaForRoles, isAdmin, type UserRole } from './roles'
import { bootstrapSession, refreshSession, sessionStore, signIn, signOut, signUp } from './session'
import type { LoginRequest, RegisterRequest } from './types'

const NO_ROLES: readonly UserRole[] = []

export function AuthProvider({ children }: { children: ReactNode }) {
  const session = useSyncExternalStore(sessionStore.subscribe, sessionStore.getSnapshot)
  const queryClient = useQueryClient()

  // Silent refresh on app start (no-op once the session is known, e.g. in tests).
  useEffect(() => {
    bootstrapSession()
  }, [])

  // Never show one user's cached data to the next user of the same browser.
  const userId = session.user?.id ?? null
  const previousUserId = useRef(userId)
  useEffect(() => {
    if (previousUserId.current !== null && previousUserId.current !== userId) {
      queryClient.clear()
    }
    previousUserId.current = userId
  }, [userId, queryClient])

  const login = useCallback((request: LoginRequest) => signIn(request), [])
  const register = useCallback((request: RegisterRequest) => signUp(request), [])
  const logout = useCallback(() => signOut(), [])
  const refresh = useCallback(async () => (await refreshSession()).user, [])

  const value = useMemo<AuthContextValue>(() => {
    const roles = session.user?.roles ?? NO_ROLES
    return {
      status: session.status,
      user: session.user,
      roles,
      area: session.user ? areaForRoles(roles) : null,
      isAuthenticated: session.status === 'authenticated',
      isAdmin: isAdmin(roles),
      endReason: session.endReason,
      suspendedMessage: session.suspendedMessage,
      hasRole: (...wanted) => wanted.some((role) => roles.includes(role)),
      can: (permission: Permission) => hasPermission(roles, permission),
      login,
      register,
      logout,
      refresh,
    }
  }, [session, login, register, logout, refresh])

  return <AuthContext value={value}>{children}</AuthContext>
}
