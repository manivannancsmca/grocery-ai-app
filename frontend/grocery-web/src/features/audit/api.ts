import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse } from '../../shared/api/types'

/**
 * The central audit trail (reporting-service, `GET /api/admin/audit`, README "Audit trail"): one search across what
 * people and the system did anywhere on the platform. SUPER_ADMIN and OPS_ADMIN only (it has sign-in addresses).
 */

export const AUDIT_CATEGORIES = [
  'SECURITY',
  'ACCOUNTS',
  'STORES',
  'PARTNERS',
  'PRICES',
  'STOCK',
  'ORDERS',
  'DELIVERIES',
  'PAYMENTS',
  'REVIEWS',
  'AI',
  'SETTINGS',
] as const
export type AuditCategory = (typeof AUDIT_CATEGORIES)[number]

export const AUDIT_CATEGORY_LABELS: Record<AuditCategory, string> = {
  SECURITY: 'Sign-in',
  ACCOUNTS: 'Accounts',
  STORES: 'Stores',
  PARTNERS: 'Delivery partners',
  PRICES: 'Prices',
  STOCK: 'Stock',
  ORDERS: 'Orders',
  DELIVERIES: 'Deliveries',
  PAYMENTS: 'Payments',
  REVIEWS: 'Reviews',
  AI: 'AI assistants',
  SETTINGS: 'Settings',
}

/** In what capacity someone acted. */
export const AUDIT_ACTOR_ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Admin',
  STORE: 'Store',
  PARTNER: 'Delivery partner',
  CUSTOMER: 'Customer',
  USER: 'Signed-in user',
  SYSTEM: 'System',
  ANONYMOUS: 'Not signed in',
}

export const AUDIT_TARGET_LABELS: Record<string, string> = {
  USER: 'Account',
  VENDOR: 'Grocery store',
  RESTAURANT: 'Restaurant',
  PARTNER: 'Delivery partner',
  ORDER: 'Order',
  PRODUCT: 'Product',
  REVIEW: 'Review',
  APPROVAL: 'Approval',
  SETTING: 'Setting',
}

export interface AuditEntryView {
  id: string
  occurredAt: string
  category: AuditCategory
  action: string
  source: string
  actor: { id: string | null; role: string; name: string | null; email: string | null }
  target: { type: string; id: string | null; label: string | null } | null
  summary: string
  detail: string | null
  ipAddress: string | null
  userAgent: string | null
}

export interface AuditParams {
  q?: string
  /** Comma-separated categories (the server also takes the parameter repeated). */
  category?: string
  actorId?: string
  targetType?: string
  targetId?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

export const auditKeys = {
  all: ['audit'] as const,
  list: (params: AuditParams) => [...auditKeys.all, 'list', params] as const,
}

export async function listAudit(params: AuditParams): Promise<PageResponse<AuditEntryView>> {
  return (await httpClient.get<PageResponse<AuditEntryView>>('/admin/audit', { params: cleanParams(params) })).data
}

export function useAuditTrail(params: AuditParams) {
  return useQuery({ queryKey: auditKeys.list(params), queryFn: () => listAudit(params), placeholderData: keepPreviousData })
}

export function auditCategoryLabel(category: string): string {
  return AUDIT_CATEGORY_LABELS[category as AuditCategory] ?? category
}

/** "LOGIN_FAILURE" → "Login failure" (the summary is the sentence; this is the code, for the filter-minded). */
export function auditActionLabel(action: string): string {
  const words = action.replace(/_/g, ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}
