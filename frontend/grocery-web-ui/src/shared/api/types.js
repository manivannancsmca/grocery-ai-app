/**
 * Paged list returned by every admin list endpoint (contract §1).
 * @template T
 * @typedef {object} PageResponse
 * @property {T[]} content
 * @property {number} page
 * @property {number} size
 * @property {number} totalElements
 * @property {number} totalPages
 */

export const DEFAULT_PAGE_SIZE = 20

/**
 * Drops empty values so they are not sent as `?status=&query=`.
 * @template {object} T
 * @param {T} params
 * @returns {Partial<T>}
 */
export function cleanParams(params) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== ''),
  )
}

/**
 * Encodes one path segment (IDs are UUIDs, but never trust a value pasted into a URL).
 * @param {string} value
 * @returns {string}
 */
export function segment(value) {
  return encodeURIComponent(value)
}
