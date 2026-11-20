/** @typedef {import('react-router').Location} Location */

/**
 * Router state the login page reads to send the user back where they were going.
 * @typedef {object} LoginRedirectState
 * @property {string} [from]
 */

/**
 * @param {Pick<Location, 'pathname' | 'search' | 'hash'>} location
 * @returns {string}
 */
export function locationToPath(location) {
  return `${location.pathname}${location.search}${location.hash}`
}

/**
 * Accepts only in-app absolute paths, so `state.from` can never send the user to another site.
 * @param {unknown} value
 * @returns {string | null}
 */
export function safeRedirectPath(value) {
  if (typeof value !== 'string') return null
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) return null
  if (value.startsWith('/login') || value.startsWith('/register') || value.startsWith('/account-suspended')) return null
  return value
}

/**
 * @param {unknown} state
 * @returns {string | null}
 */
export function redirectTarget(state) {
  if (typeof state !== 'object' || state === null) return null
  return safeRedirectPath(state.from)
}
