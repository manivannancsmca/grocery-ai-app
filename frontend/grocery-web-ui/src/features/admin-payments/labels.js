import { humanizeToken } from '../../shared/utils/format'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../payments/api').PaymentRefundStatus} PaymentRefundStatus */
/** @typedef {import('../payments/api').PaymentStatus} PaymentStatus */
/** @typedef {import('../payments/api').RefundReason} RefundReason */
/** @typedef {import('../payments/api').RefundState} RefundState */

/** @type {Record<PaymentStatus, string>} */
export const PAYMENT_STATUS_LABELS = {
  CREATED: 'Created',
  PENDING: 'Pending',
  CAPTURED: 'Captured',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
}

/** @type {Record<PaymentStatus, BadgeTone>} */
export const PAYMENT_STATUS_TONES = {
  CREATED: 'gray',
  PENDING: 'amber',
  CAPTURED: 'green',
  EXPIRED: 'red',
  CANCELLED: 'gray',
}

/** @type {Record<PaymentRefundStatus, string>} */
export const REFUND_STATUS_LABELS = {
  NONE: 'No refund',
  PENDING: 'Refund pending',
  PARTIAL: 'Partly refunded',
  FULL: 'Fully refunded',
}

/** @type {Record<PaymentRefundStatus, BadgeTone>} */
export const REFUND_STATUS_TONES = { NONE: 'gray', PENDING: 'amber', PARTIAL: 'violet', FULL: 'violet' }

/** @type {Record<RefundReason, string>} */
export const REFUND_REASON_LABELS = {
  ORDER_CANCELLED: 'Order cancelled',
  LATE_CAPTURE: 'Paid after cancellation',
  ADMIN: 'Admin refund',
}

/** @type {Record<RefundState, BadgeTone>} */
export const REFUND_STATE_TONES = { PENDING: 'amber', PROCESSED: 'green', FAILED: 'red' }

/**
 * Razorpay's payment `method` values.
 * @type {Record<string, string>}
 */
const PAYMENT_METHOD_LABELS = {
  upi: 'UPI',
  card: 'Card',
  netbanking: 'Net banking',
  wallet: 'Wallet',
  emi: 'EMI',
  cardless_emi: 'Cardless EMI',
  paylater: 'Pay later',
}

/**
 * @param {string} method
 * @returns {string}
 */
export function paymentMethodLabel(method) {
  return PAYMENT_METHOD_LABELS[method.toLowerCase()] ?? humanizeToken(method)
}
