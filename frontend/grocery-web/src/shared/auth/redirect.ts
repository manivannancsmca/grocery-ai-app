import type { Location } from 'react-router'

/** Router state the login page reads to send the user back where they were going. */
export interface LoginRedirectState {
  from?: string
}

export function locationToPath(location: Pick<Location, 'pathname' | 'search' | 'hash'>): string {
  return `${location.pathname}${location.search}${location.hash}`
}

/** Accepts only in-app absolute paths, so `state.from` can never send the user to another site. */
export function safeRedirectPath(value: unknown): string | null {
  if (typeof value !== 'string') return null
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) return null
  if (value.startsWith('/login') || value.startsWith('/register') || value.startsWith('/account-suspended')) return null
  return value
}

export function redirectTarget(state: unknown): string | null {
  if (typeof state !== 'object' || state === null) return null
  return safeRedirectPath((state as LoginRedirectState).from)
}
