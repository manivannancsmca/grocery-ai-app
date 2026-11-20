import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import { IDEMPOTENCY_KEY_HEADER } from '../orders/api'
import type { PaymentRefundStatus, PaymentStatus, PaymentView, RefundView } from '../payments/api'

/** Admin payment monitoring and refunds (payment-service — Phase 6 contract §4). Reads: any admin role. */

export interface AdminPaymentListParams {
  status?: PaymentStatus
  refundStatus?: PaymentRefundStatus
  orderId?: string
  /** Dates (`YYYY-MM-DD`). */
  from?: string
  to?: string
  page?: number
  size?: number
}

export interface CreateRefundRequest {
  orderId: string
  /** Absent → everything not yet refunded. */
  amount?: number
  /** Required, ≤ 300. */
  note: string
}

export const REFUND_LIMITS = { note: 300 } as const

export const REFUND_ERRORS = {
  notCaptured: 'PAYMENT_NOT_CAPTURED',
  exceedsCaptured: 'REFUND_EXCEEDS_CAPTURED',
  notFound: 'PAYMENT_NOT_FOUND',
} as const

/** One payment the reconciliation job recovered (Phase 10 §3). */
export interface ReconciledPayment {
  orderId: string
  orderNumber: string
  /** What the provider said, e.g. CAPTURED, AUTHORIZED (then captured) or FAILED. */
  outcome: string
  at: string
}

/** `GET /api/admin/payments/reconciliation` — the job that recovers lost verify calls and webhooks. */
export interface PaymentReconciliation {
  /** Null until the job has run once. */
  lastRunAt: string | null
  checked: number
  recovered: number
  failures: number
  /** The last 20, newest first. */
  lastRecovered: ReconciledPayment[]
}

export const adminPaymentKeys = {
  all: ['admin', 'payments'] as const,
  list: (params: AdminPaymentListParams) => [...adminPaymentKeys.all, 'list', params] as const,
  detail: (id: string) => [...adminPaymentKeys.all, 'detail', id] as const,
  /** Under `all`: a recovered payment's ADMIN_ORDERS_CHANGED refreshes it too. */
  reconciliation: () => [...adminPaymentKeys.all, 'reconciliation'] as const,
}

/** Rows come without `attempts`. Newest first. */
export async function listAdminPayments(params: AdminPaymentListParams): Promise<PageResponse<PaymentView>> {
  return (await httpClient.get<PageResponse<PaymentView>>('/admin/payments', { params: cleanParams(params) })).data
}

export async function fetchAdminPayment(id: string): Promise<PaymentView> {
  return (await httpClient.get<PaymentView>(`/admin/payments/${segment(id)}`)).data
}

/**
 * SUPER_ADMIN, FINANCE_ADMIN · `201 RefundView` (reason ADMIN) · 409 PAYMENT_NOT_CAPTURED ·
 * 422 REFUND_EXCEEDS_CAPTURED (property `refundable`) · 404 PAYMENT_NOT_FOUND.
 */
export async function createRefund(body: CreateRefundRequest, idempotencyKey: string): Promise<RefundView> {
  return (await httpClient.post<RefundView>('/admin/refunds', body, { headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } })).data
}

/** Phase 10 §3 · any admin. Read defensively: missing counts read 0, a missing list reads empty. */
export async function fetchPaymentReconciliation(): Promise<PaymentReconciliation> {
  const data: unknown = (await httpClient.get<unknown>('/admin/payments/reconciliation')).data
  if (typeof data !== 'object' || data === null || Array.isArray(data)) throw new Error('Unexpected answer from the payment service.')
  const raw = data as Partial<Record<keyof PaymentReconciliation, unknown>>
  const count = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : 0)
  return {
    lastRunAt: typeof raw.lastRunAt === 'string' && raw.lastRunAt ? raw.lastRunAt : null,
    checked: count(raw.checked),
    recovered: count(raw.recovered),
    failures: count(raw.failures),
    lastRecovered: Array.isArray(raw.lastRecovered) ? (raw.lastRecovered as ReconciledPayment[]) : [],
  }
}

/** What can still be refunded: captured amount minus refunds that are processed or still running. */
export function refundableAmount(payment: Pick<PaymentView, 'status' | 'amount' | 'refundedAmount' | 'refunds'>): number {
  if (payment.status !== 'CAPTURED') return 0
  const inFlightOrDone = payment.refunds.filter((refund) => refund.status !== 'FAILED').reduce((sum, refund) => sum + refund.amount, 0)
  const used = Math.max(payment.refundedAmount, inFlightOrDone)
  return Math.max(0, Math.round((payment.amount - used) * 100) / 100)
}
