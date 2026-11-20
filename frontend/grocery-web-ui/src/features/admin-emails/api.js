import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { httpClient } from '../../shared/api/httpClient'
import { toPage } from '../../shared/api/pages'
import { cleanParams } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/**
 * The email log (notification-service — Phase 12 contract §5): every email the outbox sent or tried to send.
 * A sender job retries after 1, 5 and 30 minutes, then gives up (FAILED).
 */

export const EMAIL_STATUSES = ['PENDING', 'SENT', 'FAILED']
/** @typedef {(typeof EMAIL_STATUSES)[number]} EmailStatus */

/**
 * @param {unknown} value
 * @returns {value is EmailStatus}
 */
export function isEmailStatus(value) {
  return typeof value === 'string' && EMAIL_STATUSES.includes(value)
}

/**
 * One `email_outbox` row.
 * @typedef {object} EmailLogEntry
 * @property {string} [id]
 * @property {string | null} [notificationId]
 * @property {string} to
 * @property {string} subject
 * @property {string} type The notification type, e.g. `ORDER_CONFIRMED`.
 * @property {EmailStatus | (string & {})} status
 * @property {number} attempts
 * @property {string | null} lastError
 * @property {string} createdAt
 * @property {string | null} sentAt
 */

/**
 * @typedef {object} EmailLogParams
 * @property {EmailStatus} [status]
 * @property {string} [to] Recipient address (contains).
 * @property {number} [page]
 * @property {number} [size]
 */

export const emailLogKeys = {
  all: ['admin', 'emails'],
  list: (params) => [...emailLogKeys.all, params],
}

/**
 * @param {EmailLogParams} params
 * @returns {Promise<PageResponse<EmailLogEntry>>}
 */
export async function listEmailLog(params) {
  return toPage((await httpClient.get('/admin/notifications/emails', { params: cleanParams(params) })).data)
}

/** @param {EmailLogParams} params */
export function useEmailLog(params) {
  return useQuery({
    queryKey: emailLogKeys.list(params),
    queryFn: () => listEmailLog(params),
    placeholderData: keepPreviousData,
  })
}
