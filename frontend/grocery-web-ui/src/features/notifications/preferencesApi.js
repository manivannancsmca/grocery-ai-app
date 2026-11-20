import { httpClient } from '../../shared/api/httpClient'

/**
 * Email preferences (notification-service — Phase 12 contract §5). Every category is on by default; in-app
 * notifications are always sent. Categories: orders, refunds and returns → `orders`; account decisions → `account`;
 * assistant approvals → `approvals`.
 */

export const EMAIL_CATEGORIES = ['orders', 'account', 'approvals']
/** @typedef {(typeof EMAIL_CATEGORIES)[number]} EmailCategory */

/** @typedef {Record<EmailCategory, boolean>} EmailPreferences */

/**
 * @typedef {object} NotificationPreferences
 * @property {EmailPreferences} email
 */

/** @type {EmailPreferences} */
export const DEFAULT_EMAIL_PREFERENCES = { orders: true, account: true, approvals: true }

/** Its own root, so the inbox refreshes on every notification never re-read the preferences. */
export const notificationPreferenceKeys = {
  all: ['notification-preferences'],
}

/**
 * Missing categories count as on (the default).
 * @param {{ email?: Partial<EmailPreferences> | null } | null | undefined} preferences
 * @returns {NotificationPreferences}
 */
function normalize(preferences) {
  const email = preferences?.email ?? {}
  return {
    email: {
      orders: email.orders !== false,
      account: email.account !== false,
      approvals: email.approvals !== false,
    },
  }
}

/** @returns {Promise<NotificationPreferences>} */
export async function fetchNotificationPreferences() {
  return normalize((await httpClient.get('/notifications/preferences')).data)
}

/**
 * Answers the saved preferences (a 204 is read as "saved as sent").
 * @param {NotificationPreferences} body
 * @returns {Promise<NotificationPreferences>}
 */
export async function saveNotificationPreferences(body) {
  const response = await httpClient.put('/notifications/preferences', body)
  return response.data && typeof response.data === 'object' ? normalize(response.data) : normalize(body)
}
