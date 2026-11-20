import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useSyncExternalStore } from 'react'
import { AuthContext } from './authContext'
import { hasPermission } from './permissions'
import { areaForRoles, isAdmin } from './roles'
import { bootstrapSession, refreshSession, sessionStore, signIn, signOut, signUp } from './session'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('./roles').UserRole} UserRole */

/** @type {readonly UserRole[]} */
const NO_ROLES = []

/** @param {{ children: ReactNode }} props */
export function AuthProvider({ children }) {
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

  const login = useCallback((request) => signIn(request), [])
  const register = useCallback((request) => signUp(request), [])
  const logout = useCallback(() => signOut(), [])
  const refresh = useCallback(async () => (await refreshSession()).user, [])

  const value = useMemo(() => {
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
      can: (permission) => hasPermission(roles, permission),
      login,
      register,
      logout,
      refresh,
    }
  }, [session, login, register, logout, refresh])

  return <AuthContext value={value}>{children}</AuthContext>
}
