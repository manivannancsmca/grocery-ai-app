import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { httpClient } from '../../shared/api/httpClient'
import { toPage } from '../../shared/api/pages'
import { cleanParams, type PageResponse } from '../../shared/api/types'

/**
 * The email log (notification-service — Phase 12 contract §5): every email the outbox sent or tried to send.
 * A sender job retries after 1, 5 and 30 minutes, then gives up (FAILED).
 */

export const EMAIL_STATUSES = ['PENDING', 'SENT', 'FAILED'] as const
export type EmailStatus = (typeof EMAIL_STATUSES)[number]

export function isEmailStatus(value: unknown): value is EmailStatus {
  return typeof value === 'string' && (EMAIL_STATUSES as readonly string[]).includes(value)
}

/** One `email_outbox` row. */
export interface EmailLogEntry {
  id?: string
  notificationId?: string | null
  to: string
  subject: string
  /** The notification type, e.g. `ORDER_CONFIRMED`. */
  type: string
  status: EmailStatus | (string & {})
  attempts: number
  lastError: string | null
  createdAt: string
  sentAt: string | null
}

export interface EmailLogParams {
  status?: EmailStatus
  /** Recipient address (contains). */
  to?: string
  page?: number
  size?: number
}

export const emailLogKeys = {
  all: ['admin', 'emails'] as const,
  list: (params: EmailLogParams) => [...emailLogKeys.all, params] as const,
}

export async function listEmailLog(params: EmailLogParams): Promise<PageResponse<EmailLogEntry>> {
  return toPage((await httpClient.get<PageResponse<EmailLogEntry> | EmailLogEntry[]>('/admin/notifications/emails', { params: cleanParams(params) })).data)
}

export function useEmailLog(params: EmailLogParams) {
  return useQuery({ queryKey: emailLogKeys.list(params), queryFn: () => listEmailLog(params), placeholderData: keepPreviousData })
}
