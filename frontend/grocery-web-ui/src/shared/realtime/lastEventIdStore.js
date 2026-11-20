/** @typedef {import('./sseClient').LastEventIdStore} LastEventIdStore */

const STORAGE_PREFIX = 'grocery.realtime.lastEventId.'

/**
 * @param {string} userId
 * @returns {string}
 */
export function lastEventIdStorageKey(userId) {
  return `${STORAGE_PREFIX}${userId}`
}

/**
 * The last notification id this tab has seen, per user: in memory, mirrored to sessionStorage so a page
 * reload resumes the stream (`Last-Event-ID`) instead of missing what arrived meanwhile. sessionStorage is
 * per tab, and keyed by user so another account signing in on the same tab never resumes someone else's stream.
 * @param {string} userId
 * @returns {LastEventIdStore}
 */
export function createLastEventIdStore(userId) {
  const key = lastEventIdStorageKey(userId)
  let current = null
  try {
    current = sessionStorage.getItem(key)
  } catch {
    // Storage disabled (privacy mode): memory only.
  }

  return {
    get: () => current,
    set: (id) => {
      current = id
      try {
        sessionStorage.setItem(key, id)
      } catch {
        // Memory only.
      }
    },
  }
}
