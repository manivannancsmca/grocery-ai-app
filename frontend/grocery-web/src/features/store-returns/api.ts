import { httpClient } from '../../shared/api/httpClient'
import { toPage } from '../../shared/api/pages'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'

/**
 * Returns & partial refunds as the store and the admins decide them (order-service — Phase 12 contract §3).
 * The customer asks within the return window (48 h of delivery by default); the store approves (stating whether the
 * goods can be restocked — grocery only) or rejects with a comment within the decision time (24 h by default), after
 * which the request escalates to admins. Both times are platform settings. The refund is computed by the server.
 */

export const RETURN_STATUSES = ['REQUESTED', 'ESCALATED', 'APPROVED', 'REFUNDED', 'REJECTED'] as const
export type ReturnStatus = (typeof RETURN_STATUSES)[number]

export function isReturnStatus(value: unknown): value is ReturnStatus {
  return typeof value === 'string' && (RETURN_STATUSES as readonly string[]).includes(value)
}

/** Waiting for a decision (store: REQUESTED; admins also ESCALATED). */
export function isOpenReturn(status: string): boolean {
  return status === 'REQUESTED' || status === 'ESCALATED'
}

export const RETURN_REASONS = ['DAMAGED', 'WRONG_ITEM', 'MISSING', 'QUALITY', 'OTHER'] as const
export type ReturnReason = (typeof RETURN_REASONS)[number]

export const RETURN_LIMITS = { comment: 300 } as const

export const RETURN_ERRORS = {
  notPending: 'RETURN_NOT_PENDING',
  notFound: 'RETURN_NOT_FOUND',
} as const

export interface ReturnItem {
  itemId: string
  name: string
  quantity: number
  refundAmount: number
}

/** `ReturnView`. */
export interface ReturnView {
  id: string
  /** `RT-7K3M9QX2`. */
  returnNumber: string
  orderId: string
  orderNumber: string
  orderType: 'GROCERY' | 'RESTAURANT' | (string & {})
  storeId: string
  storeName: string | null
  status: ReturnStatus | (string & {})
  reason: ReturnReason | (string & {})
  note: string | null
  items: ReturnItem[]
  refundAmount: number
  /** Set at approval (always false for restaurant orders); null before. */
  restock: boolean | null
  /** The request escalates to admins if the store has not decided by then. */
  decideBy: string | null
  requestedAt: string
  decidedAt: string | null
  /** `VENDOR`, `RESTAURANT_OWNER`, `SUPPORT`, … */
  decidedByRole: string | null
  /** The rejection comment. */
  comment: string | null
  refundedAt: string | null
}

export interface ReturnListParams {
  status?: ReturnStatus
  page?: number
  size?: number
}

export interface AdminReturnListParams extends ReturnListParams {
  storeId?: string
}

/** Who decides: the store (its own returns) or an admin (any, including escalated ones). */
export type ReturnDecider = 'store' | 'admin'

export const returnKeys = {
  all: ['store-returns'] as const,
  store: () => [...returnKeys.all, 'store'] as const,
  storeList: (params: ReturnListParams) => [...returnKeys.store(), params] as const,
  admin: () => [...returnKeys.all, 'admin'] as const,
  adminList: (params: AdminReturnListParams) => [...returnKeys.admin(), params] as const,
}

function normalise(view: ReturnView): ReturnView {
  return { ...view, items: view.items ?? [] }
}

const base = (decider: ReturnDecider) => (decider === 'admin' ? '/admin/returns' : '/store/returns')

/** The caller's store's returns (newest first). */
export async function listStoreReturns(params: ReturnListParams): Promise<PageResponse<ReturnView>> {
  const page = toPage((await httpClient.get<PageResponse<ReturnView> | ReturnView[]>('/store/returns', { params: cleanParams(params) })).data)
  return { ...page, content: page.content.map(normalise) }
}

/** Without `status`: REQUESTED and ESCALATED (the server's default). */
export async function listAdminReturns(params: AdminReturnListParams): Promise<PageResponse<ReturnView>> {
  const page = toPage((await httpClient.get<PageResponse<ReturnView> | ReturnView[]>('/admin/returns', { params: cleanParams(params) })).data)
  return { ...page, content: page.content.map(normalise) }
}

/** `restock` is forced to false by the server for restaurant orders. 409 RETURN_NOT_PENDING (`currentStatus`). */
export async function approveReturn(decider: ReturnDecider, id: string, restock: boolean): Promise<ReturnView> {
  return normalise((await httpClient.post<ReturnView>(`${base(decider)}/${segment(id)}/approve`, { restock })).data)
}

/** `comment` required, ≤ 300; it is shown to the customer. 409 RETURN_NOT_PENDING (`currentStatus`). */
export async function rejectReturn(decider: ReturnDecider, id: string, comment: string): Promise<ReturnView> {
  return normalise((await httpClient.post<ReturnView>(`${base(decider)}/${segment(id)}/reject`, { comment })).data)
}
