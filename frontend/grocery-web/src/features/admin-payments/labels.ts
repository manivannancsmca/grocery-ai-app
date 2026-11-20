import type { BadgeTone } from '../../shared/components/ui'
import { humanizeToken } from '../../shared/utils/format'
import type { PaymentRefundStatus, PaymentStatus, RefundReason, RefundState } from '../payments/api'

export const PAYMENT_STATUS_LABELS: Record<PaymentStatus, string> = {
  CREATED: 'Created',
  PENDING: 'Pending',
  CAPTURED: 'Captured',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
}

export const PAYMENT_STATUS_TONES: Record<PaymentStatus, BadgeTone> = {
  CREATED: 'gray',
  PENDING: 'amber',
  CAPTURED: 'green',
  EXPIRED: 'red',
  CANCELLED: 'gray',
}

export const REFUND_STATUS_LABELS: Record<PaymentRefundStatus, string> = {
  NONE: 'No refund',
  PENDING: 'Refund pending',
  PARTIAL: 'Partly refunded',
  FULL: 'Fully refunded',
}

export const REFUND_STATUS_TONES: Record<PaymentRefundStatus, BadgeTone> = { NONE: 'gray', PENDING: 'amber', PARTIAL: 'violet', FULL: 'violet' }

export const REFUND_REASON_LABELS: Record<RefundReason, string> = {
  ORDER_CANCELLED: 'Order cancelled',
  LATE_CAPTURE: 'Paid after cancellation',
  ADMIN: 'Admin refund',
}

export const REFUND_STATE_TONES: Record<RefundState, BadgeTone> = { PENDING: 'amber', PROCESSED: 'green', FAILED: 'red' }

/** Razorpay's payment `method` values. */
const PAYMENT_METHOD_LABELS: Record<string, string> = {
  upi: 'UPI',
  card: 'Card',
  netbanking: 'Net banking',
  wallet: 'Wallet',
  emi: 'EMI',
  cardless_emi: 'Cardless EMI',
  paylater: 'Pay later',
}

export function paymentMethodLabel(method: string): string {
  return PAYMENT_METHOD_LABELS[method.toLowerCase()] ?? humanizeToken(method)
}
