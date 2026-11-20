import { httpClient } from '../../shared/api/httpClient'

/**
 * Email preferences (notification-service — Phase 12 contract §5). Every category is on by default; in-app
 * notifications are always sent. Categories: orders, refunds and returns → `orders`; account decisions → `account`;
 * assistant approvals → `approvals`.
 */

export const EMAIL_CATEGORIES = ['orders', 'account', 'approvals'] as const
export type EmailCategory = (typeof EMAIL_CATEGORIES)[number]

export type EmailPreferences = Record<EmailCategory, boolean>

export interface NotificationPreferences {
  email: EmailPreferences
}

export const DEFAULT_EMAIL_PREFERENCES: EmailPreferences = { orders: true, account: true, approvals: true }

/** Its own root, so the inbox refreshes on every notification never re-read the preferences. */
export const notificationPreferenceKeys = {
  all: ['notification-preferences'] as const,
}

/** Missing categories count as on (the default). */
function normalize(preferences: { email?: Partial<EmailPreferences> | null } | null | undefined): NotificationPreferences {
  const email: Partial<EmailPreferences> = preferences?.email ?? {}
  return {
    email: {
      orders: email.orders !== false,
      account: email.account !== false,
      approvals: email.approvals !== false,
    },
  }
}

export async function fetchNotificationPreferences(): Promise<NotificationPreferences> {
  return normalize((await httpClient.get<NotificationPreferences>('/notifications/preferences')).data)
}

/** Answers the saved preferences (a 204 is read as "saved as sent"). */
export async function saveNotificationPreferences(body: NotificationPreferences): Promise<NotificationPreferences> {
  const response = await httpClient.put<NotificationPreferences | ''>('/notifications/preferences', body)
  return response.data && typeof response.data === 'object' ? normalize(response.data) : normalize(body)
}
