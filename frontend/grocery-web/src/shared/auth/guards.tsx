import type { ReactNode } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { PageLoader } from '../components/ui'
import type { Permission } from './permissions'
import { type LoginRedirectState, locationToPath, redirectTarget } from './redirect'
import { AREA_META, type UserRole } from './roles'
import { useAuth } from './useAuth'

interface GuardProps {
  /** Rendered when allowed; defaults to the child routes (`<Outlet />`). */
  children?: ReactNode
}

/** Shared "who is this?" checks: loading, suspended, signed out. Returns null when signed in. */
function useSessionGate(): ReactNode | null {
  const { status, endReason } = useAuth()
  const location = useLocation()

  if (status === 'initializing') return <PageLoader />
  if (status === 'suspended') return <Navigate to="/account-suspended" replace />
  if (status !== 'authenticated') {
    // After an explicit sign-out the next person starts fresh; otherwise come back here after login.
    const state: LoginRedirectState | undefined =
      endReason === 'signed-out' ? undefined : { from: locationToPath(location) }
    return <Navigate to="/login" replace state={state} />
  }
  return null
}

/** Signed-in users only; everyone else goes to /login and comes back afterwards. */
export function RequireAuth({ children }: GuardProps) {
  const gate = useSessionGate()
  if (gate) return gate
  return children ?? <Outlet />
}

interface RequireRoleProps extends GuardProps {
  /** The user needs at least one of these roles. */
  roles: readonly UserRole[]
  /** Optional finer check inside an area (e.g. `users.view` for the admin Users page). */
  permission?: Permission
}

/** Signed-in users holding one of `roles`; others are sent to their own home page. */
export function RequireRole({ roles, permission, children }: RequireRoleProps) {
  const gate = useSessionGate()
  const { area, hasRole, can } = useAuth()
  if (gate) return gate

  const allowed = hasRole(...roles) && (permission === undefined || can(permission))
  if (!allowed) {
    return <Navigate to={area ? AREA_META[area].homePath : '/'} replace />
  }
  return children ?? <Outlet />
}

/** Login and register: signed-in users continue to where they were going (or their home page). */
export function GuestOnly({ children }: GuardProps) {
  const { status, area } = useAuth()
  const location = useLocation()

  if (status === 'initializing') return <PageLoader />
  if (status === 'suspended') return <Navigate to="/account-suspended" replace />
  if (status === 'authenticated') {
    const target = redirectTarget(location.state) ?? (area ? AREA_META[area].homePath : '/')
    return <Navigate to={target} replace />
  }
  return children ?? <Outlet />
}

/**
 * The site root is the public storefront for guests. Every signed-in user is sent to their own home page:
 * customers to `/customer/home`, vendors, restaurant owners, delivery partners and admins to their dashboard.
 */
export function HomeGate({ children }: GuardProps) {
  const { status, area } = useAuth()

  if (status === 'initializing') return <PageLoader />
  if (status === 'suspended') return <Navigate to="/account-suspended" replace />
  if (status === 'authenticated' && area) {
    return <Navigate to={AREA_META[area].homePath} replace />
  }
  return children ?? <Outlet />
}
