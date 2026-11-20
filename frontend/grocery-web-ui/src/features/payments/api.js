import { httpClient } from '../../shared/api/httpClient'
import { segment } from '../../shared/api/types'

/** Payments (payment-service — Phase 6 contract §3). */

/** @typedef {'RAZORPAY' | 'SIMULATOR'} PaymentProvider */
export const PAYMENT_STATUSES = ['CREATED', 'PENDING', 'CAPTURED', 'EXPIRED', 'CANCELLED']
/** @typedef {(typeof PAYMENT_STATUSES)[number]} PaymentStatus */
export const REFUND_STATUSES = ['NONE', 'PENDING', 'PARTIAL', 'FULL']
/**
 * `PaymentView.refundStatus`.
 * @typedef {(typeof REFUND_STATUSES)[number]} PaymentRefundStatus
 */

/**
 * @param {unknown} value
 * @returns {value is PaymentStatus}
 */
export function isPaymentStatus(value) {
  return typeof value === 'string' && PAYMENT_STATUSES.includes(value)
}

/**
 * @param {unknown} value
 * @returns {value is PaymentRefundStatus}
 */
export function isRefundStatus(value) {
  return typeof value === 'string' && REFUND_STATUSES.includes(value)
}

/** @typedef {'ORDER_CANCELLED' | 'LATE_CAPTURE' | 'ADMIN'} RefundReason */
/** @typedef {'PENDING' | 'PROCESSED' | 'FAILED'} RefundState */

/**
 * Everything Razorpay Checkout needs (the key secret never leaves payment-service).
 * @typedef {object} CheckoutSession
 * @property {PaymentProvider} provider
 * @property {string} keyId
 * @property {string} razorpayOrderId
 * @property {number} amount Paise (integer) — what Checkout expects.
 * @property {number} displayAmount Rupees.
 * @property {string} currency
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {string} merchantName
 * @property {string} description
 * @property {{ name?: string | null, email?: string | null, contact?: string | null }} prefill
 * @property {string} expiresAt
 */

/**
 * The three values Razorpay Checkout (or the simulator) hands back after a successful payment.
 * @typedef {object} PaymentVerification
 * @property {string} razorpayOrderId
 * @property {string} razorpayPaymentId
 * @property {string} razorpaySignature
 */

/**
 * @typedef {object} PaymentAttempt
 * @property {string} razorpayPaymentId
 * @property {'FAILED' | 'CAPTURED' | 'AUTHORIZED' | (string & {})} status
 * @property {string | null} method
 * @property {string | null} errorCode
 * @property {string | null} errorDescription
 * @property {string} at
 */

/**
 * @typedef {object} RefundView
 * @property {string} id
 * @property {string} paymentId
 * @property {string} orderId
 * @property {number} amount
 * @property {RefundReason} reason
 * @property {string | null} note
 * @property {RefundState} status
 * @property {string | null} razorpayRefundId
 * @property {string | null} failureReason
 * @property {string | null} requestedBy Admin user id for ADMIN refunds.
 * @property {string} createdAt
 * @property {string | null} processedAt
 */

/**
 * @typedef {object} PaymentView
 * @property {string} id
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {string} customerId
 * @property {PaymentStatus} status
 * @property {number} amount
 * @property {string} currency
 * @property {string | null} method
 * @property {string} razorpayOrderId
 * @property {string | null} razorpayPaymentId
 * @property {number} refundedAmount
 * @property {PaymentRefundStatus} refundStatus
 * @property {string} expiresAt
 * @property {string | null} capturedAt
 * @property {string} createdAt
 * @property {PaymentAttempt[]} [attempts] Absent in admin list rows.
 * @property {RefundView[]} refunds
 */

/** @typedef {'SUCCESS' | 'FAIL'} SimulatorOutcome */

export const PAYMENT_ERRORS = {
  notReady: 'PAYMENT_NOT_READY',
  notFound: 'PAYMENT_NOT_FOUND',
  alreadyCaptured: 'PAYMENT_ALREADY_CAPTURED',
  expired: 'PAYMENT_EXPIRED',
  cancelled: 'PAYMENT_CANCELLED',
  providerUnavailable: 'PAYMENT_PROVIDER_UNAVAILABLE',
  invalidSignature: 'INVALID_SIGNATURE',
  failed: 'PAYMENT_FAILED',
}

export const paymentKeys = {
  all: ['payments'],
  forOrder: (orderId) => [...paymentKeys.all, 'order', orderId],
}

/** @param {string} orderId */
const forOrder = (orderId) => `/payments/orders/${segment(orderId)}`

/**
 * 409 PAYMENT_NOT_READY (retry after a second) / PAYMENT_ALREADY_CAPTURED / PAYMENT_EXPIRED / PAYMENT_CANCELLED.
 * @param {string} orderId
 * @returns {Promise<CheckoutSession>}
 */
export async function fetchCheckoutSession(orderId) {
  return (await httpClient.get(`${forOrder(orderId)}/checkout`)).data
}

/**
 * 400 INVALID_SIGNATURE · 404 PAYMENT_NOT_FOUND · 409 PAYMENT_FAILED. Idempotent.
 * @param {PaymentVerification} body
 * @returns {Promise<PaymentView>}
 */
export async function verifyPayment(body) {
  return (await httpClient.post('/payments/razorpay/verify', body)).data
}

/**
 * The customer's own payment (any admin may read any).
 * @param {string} orderId
 * @returns {Promise<PaymentView>}
 */
export async function fetchOrderPayment(orderId) {
  return (await httpClient.get(forOrder(orderId))).data
}

/**
 * Simulator mode only (404 with real Razorpay keys). SUCCESS answers the signed ids to post to verify;
 * FAIL records a declined attempt like a `payment.failed` webhook.
 * @param {string} orderId
 * @param {SimulatorOutcome} outcome
 * @returns {Promise<PaymentVerification | null>}
 */
export async function simulatePayment(orderId, outcome) {
  const response = await httpClient.post(`/payments/simulator/orders/${segment(orderId)}/pay`, { outcome })
  const data = response.data
  return outcome === 'SUCCESS' && data && typeof data === 'object' ? data : null
}
