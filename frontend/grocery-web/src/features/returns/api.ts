import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'

/**
 * Returns and partial refunds (order-service — Phase 12 contract §3). The customer of a DELIVERED order may ask for
 * lines and quantities back within the return window (a platform setting, 48 h by default; `OrderView.returnable`);
 * the refund is computed by the server (prorated by the order's coupon discount, plus the paid delivery fee when
 * everything left is returned).
 */

export const RETURN_REASONS = ['DAMAGED', 'WRONG_ITEM', 'MISSING', 'QUALITY', 'OTHER'] as const
export type ReturnReason = (typeof RETURN_REASONS)[number]

export const RETURN_STATUSES = ['REQUESTED', 'ESCALATED', 'APPROVED', 'REFUNDED', 'REJECTED'] as const
export type ReturnStatus = (typeof RETURN_STATUSES)[number]

export const RETURN_LIMITS = { note: 500 } as const
/** Hours after delivery a return may be requested (`ORDER_RETURN_WINDOW`). */
export const RETURN_WINDOW_HOURS = 48

export const RETURN_ERRORS = {
  /** Wrong state, window over, or another request is still open. */
  notAllowed: 'RETURN_NOT_ALLOWED',
  quantityExceeded: 'RETURN_QUANTITY_EXCEEDED',
} as const

export interface ReturnItem {
  itemId: string
  name: string
  quantity: number
  /** This line's refund (after the order's coupon discount). */
  refundAmount: number
}

/** `ReturnView`. */
export interface ReturnView {
  id: string
  /** e.g. `RT-7K3M9QX2`. */
  returnNumber: string
  orderId: string
  orderNumber: string
  orderType: 'GROCERY' | 'RESTAURANT' | (string & {})
  storeId: string
  storeName: string
  status: ReturnStatus | (string & {})
  reason: ReturnReason | (string & {})
  note: string | null
  items: ReturnItem[]
  /** Total refund, incl. the paid delivery fee when the request returns everything that was left. */
  refundAmount: number
  /** Whether the goods went back to stock (null until decided; always false for restaurants). */
  restock: boolean | null
  /** When the store must decide by (a platform setting, 24 h by default), after which it is escalated to admins. */
  decideBy: string | null
  requestedAt: string
  decidedAt: string | null
  decidedByRole: string | null
  /** The store's or admin's comment (required on a rejection). */
  comment: string | null
  refundedAt: string | null
}

/** `OrderView.returns` — the order's return requests, newest or oldest first. */
export interface OrderReturnSummary {
  id: string
  returnNumber: string
  status: ReturnStatus | (string & {})
  refundAmount: number
  requestedAt: string
  /** The lines asked back (optional in the summary; used to work out what can still be returned). */
  items?: Array<{ itemId: string; quantity: number; name?: string }>
}

export interface ReturnRequestBody {
  items: Array<{ itemId: string; quantity: number }>
  reason: ReturnReason
  /** ≤ 500. */
  note?: string
}

export interface MyReturnParams {
  page?: number
  size?: number
}

export const MY_RETURNS_PAGE_SIZE = 10

export const returnKeys = {
  all: ['returns'] as const,
  /** The customer's return list (`GET /api/returns`). */
  mine: () => [...returnKeys.all, 'mine'] as const,
  mineList: (params: MyReturnParams) => [...returnKeys.mine(), params] as const,
  /** One order's returns (`GET /api/orders/{id}/returns`). */
  forOrder: (orderId: string) => [...returnKeys.all, 'order', orderId] as const,
}

/** 201 ReturnView (with the computed refund); 422 RETURN_NOT_ALLOWED / RETURN_QUANTITY_EXCEEDED. */
export async function requestReturn(orderId: string, body: ReturnRequestBody): Promise<ReturnView> {
  return (await httpClient.post<ReturnView>(`/orders/${segment(orderId)}/returns`, body)).data
}

export async function fetchOrderReturns(orderId: string): Promise<ReturnView[]> {
  return (await httpClient.get<ReturnView[]>(`/orders/${segment(orderId)}/returns`)).data
}

/** The customer's own returns, newest first. */
export async function fetchMyReturns(params: MyReturnParams): Promise<PageResponse<ReturnView>> {
  return (await httpClient.get<PageResponse<ReturnView>>('/returns', { params: cleanParams(params) })).data
}
