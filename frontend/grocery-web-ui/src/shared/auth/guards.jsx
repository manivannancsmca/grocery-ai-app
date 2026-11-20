import { Navigate, Outlet, useLocation } from 'react-router'
import { PageLoader } from '../components/ui'
import { locationToPath, redirectTarget } from './redirect'
import { AREA_META } from './roles'
import { useAuth } from './useAuth'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('./permissions').Permission} Permission */
/** @typedef {import('./roles').UserRole} UserRole */

/**
 * @typedef {object} GuardProps
 * @property {ReactNode} [children] Rendered when allowed; defaults to the child routes (`<Outlet />`).
 */

/**
 * Shared "who is this?" checks: loading, suspended, signed out. Returns null when signed in.
 * @returns {ReactNode | null}
 */
function useSessionGate() {
  const { status, endReason } = useAuth()
  const location = useLocation()

  if (status === 'initializing') return <PageLoader />
  if (status === 'suspended') return <Navigate to="/account-suspended" replace />
  if (status !== 'authenticated') {
    // After an explicit sign-out the next person starts fresh; otherwise come back here after login.
    const state = endReason === 'signed-out' ? undefined : { from: locationToPath(location) }
    return <Navigate to="/login" replace state={state} />
  }
  return null
}

/**
 * Signed-in users only; everyone else goes to /login and comes back afterwards.
 * @param {GuardProps} props
 */
export function RequireAuth({ children }) {
  const gate = useSessionGate()
  if (gate) return gate
  return children ?? <Outlet />
}

/**
 * @typedef {GuardProps & { roles: readonly UserRole[], permission?: Permission }} RequireRoleProps
 * - `roles`: The user needs at least one of these roles.
 * - `permission`: Optional finer check inside an area (e.g. `users.view` for the admin Users page).
 */

/**
 * Signed-in users holding one of `roles`; others are sent to their own home page.
 * @param {RequireRoleProps} props
 */
export function RequireRole({ roles, permission, children }) {
  const gate = useSessionGate()
  const { area, hasRole, can } = useAuth()
  if (gate) return gate

  const allowed = hasRole(...roles) && (permission === undefined || can(permission))
  if (!allowed) {
    return <Navigate to={area ? AREA_META[area].homePath : '/'} replace />
  }
  return children ?? <Outlet />
}

/**
 * Login and register: signed-in users continue to where they were going (or their home page).
 * @param {GuardProps} props
 */
export function GuestOnly({ children }) {
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
 * @param {GuardProps} props
 */
export function HomeGate({ children }) {
  const { status, area } = useAuth()

  if (status === 'initializing') return <PageLoader />
  if (status === 'suspended') return <Navigate to="/account-suspended" replace />
  if (status === 'authenticated' && area) {
    return <Navigate to={AREA_META[area].homePath} replace />
  }
  return children ?? <Outlet />
}
