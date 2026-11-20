import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'
import { IDEMPOTENCY_KEY_HEADER } from '../orders/api'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../payments/api').PaymentRefundStatus} PaymentRefundStatus */
/** @typedef {import('../payments/api').PaymentStatus} PaymentStatus */
/** @typedef {import('../payments/api').PaymentView} PaymentView */
/** @typedef {import('../payments/api').RefundView} RefundView */

/** Admin payment monitoring and refunds (payment-service — Phase 6 contract §4). Reads: any admin role. */

/**
 * @typedef {object} AdminPaymentListParams
 * @property {PaymentStatus} [status]
 * @property {PaymentRefundStatus} [refundStatus]
 * @property {string} [orderId]
 * @property {string} [from] Dates (`YYYY-MM-DD`).
 * @property {string} [to]
 * @property {number} [page]
 * @property {number} [size]
 */

/**
 * @typedef {object} CreateRefundRequest
 * @property {string} orderId
 * @property {number} [amount] Absent → everything not yet refunded.
 * @property {string} note Required, ≤ 300.
 */

export const REFUND_LIMITS = { note: 300 }

export const REFUND_ERRORS = {
  notCaptured: 'PAYMENT_NOT_CAPTURED',
  exceedsCaptured: 'REFUND_EXCEEDS_CAPTURED',
  notFound: 'PAYMENT_NOT_FOUND',
}

/**
 * One payment the reconciliation job recovered (Phase 10 §3).
 * @typedef {object} ReconciledPayment
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {string} outcome What the provider said, e.g. CAPTURED, AUTHORIZED (then captured) or FAILED.
 * @property {string} at
 */

/**
 * `GET /api/admin/payments/reconciliation` — the job that recovers lost verify calls and webhooks.
 * @typedef {object} PaymentReconciliation
 * @property {string | null} lastRunAt Null until the job has run once.
 * @property {number} checked
 * @property {number} recovered
 * @property {number} failures
 * @property {ReconciledPayment[]} lastRecovered The last 20, newest first.
 */

export const adminPaymentKeys = {
  all: ['admin', 'payments'],
  list: (params) => [...adminPaymentKeys.all, 'list', params],
  detail: (id) => [...adminPaymentKeys.all, 'detail', id],
  /** Under `all`: a recovered payment's ADMIN_ORDERS_CHANGED refreshes it too. */
  reconciliation: () => [...adminPaymentKeys.all, 'reconciliation'],
}

/**
 * Rows come without `attempts`. Newest first.
 * @param {AdminPaymentListParams} params
 * @returns {Promise<PageResponse<PaymentView>>}
 */
export async function listAdminPayments(params) {
  return (await httpClient.get('/admin/payments', { params: cleanParams(params) })).data
}

/**
 * @param {string} id
 * @returns {Promise<PaymentView>}
 */
export async function fetchAdminPayment(id) {
  return (await httpClient.get(`/admin/payments/${segment(id)}`)).data
}

/**
 * SUPER_ADMIN, FINANCE_ADMIN · `201 RefundView` (reason ADMIN) · 409 PAYMENT_NOT_CAPTURED ·
 * 422 REFUND_EXCEEDS_CAPTURED (property `refundable`) · 404 PAYMENT_NOT_FOUND.
 * @param {CreateRefundRequest} body
 * @param {string} idempotencyKey
 * @returns {Promise<RefundView>}
 */
export async function createRefund(body, idempotencyKey) {
  return (await httpClient.post('/admin/refunds', body, { headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } })).data
}

/**
 * Phase 10 §3 · any admin. Read defensively: missing counts read 0, a missing list reads empty.
 * @returns {Promise<PaymentReconciliation>}
 */
export async function fetchPaymentReconciliation() {
  const data = (await httpClient.get('/admin/payments/reconciliation')).data
  if (typeof data !== 'object' || data === null || Array.isArray(data))
    throw new Error('Unexpected answer from the payment service.')
  const raw = data
  const count = (value) => (typeof value === 'number' && Number.isFinite(value) ? value : 0)
  return {
    lastRunAt: typeof raw.lastRunAt === 'string' && raw.lastRunAt ? raw.lastRunAt : null,
    checked: count(raw.checked),
    recovered: count(raw.recovered),
    failures: count(raw.failures),
    lastRecovered: Array.isArray(raw.lastRecovered) ? raw.lastRecovered : [],
  }
}

/**
 * What can still be refunded: captured amount minus refunds that are processed or still running.
 * @param {Pick<PaymentView, 'status' | 'amount' | 'refundedAmount' | 'refunds'>} payment
 * @returns {number}
 */
export function refundableAmount(payment) {
  if (payment.status !== 'CAPTURED') return 0
  const inFlightOrDone = payment.refunds
    .filter((refund) => refund.status !== 'FAILED')
    .reduce((sum, refund) => sum + refund.amount, 0)
  const used = Math.max(payment.refundedAmount, inFlightOrDone)
  return Math.max(0, Math.round((payment.amount - used) * 100) / 100)
}
