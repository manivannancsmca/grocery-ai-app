import { httpClient } from '../../shared/api/httpClient'
import { segment } from '../../shared/api/types'

/** Payments (payment-service — Phase 6 contract §3). */

export type PaymentProvider = 'RAZORPAY' | 'SIMULATOR'
export const PAYMENT_STATUSES = ['CREATED', 'PENDING', 'CAPTURED', 'EXPIRED', 'CANCELLED'] as const
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number]
export const REFUND_STATUSES = ['NONE', 'PENDING', 'PARTIAL', 'FULL'] as const
/** `PaymentView.refundStatus`. */
export type PaymentRefundStatus = (typeof REFUND_STATUSES)[number]

export function isPaymentStatus(value: unknown): value is PaymentStatus {
  return typeof value === 'string' && (PAYMENT_STATUSES as readonly string[]).includes(value)
}

export function isRefundStatus(value: unknown): value is PaymentRefundStatus {
  return typeof value === 'string' && (REFUND_STATUSES as readonly string[]).includes(value)
}

export type RefundReason = 'ORDER_CANCELLED' | 'LATE_CAPTURE' | 'ADMIN'
export type RefundState = 'PENDING' | 'PROCESSED' | 'FAILED'

/** Everything Razorpay Checkout needs (the key secret never leaves payment-service). */
export interface CheckoutSession {
  provider: PaymentProvider
  keyId: string
  razorpayOrderId: string
  /** Paise (integer) — what Checkout expects. */
  amount: number
  /** Rupees. */
  displayAmount: number
  currency: string
  orderId: string
  orderNumber: string
  merchantName: string
  description: string
  prefill: { name?: string | null; email?: string | null; contact?: string | null }
  expiresAt: string
}

/** The three values Razorpay Checkout (or the simulator) hands back after a successful payment. */
export interface PaymentVerification {
  razorpayOrderId: string
  razorpayPaymentId: string
  razorpaySignature: string
}

export interface PaymentAttempt {
  razorpayPaymentId: string
  status: 'FAILED' | 'CAPTURED' | 'AUTHORIZED' | (string & {})
  method: string | null
  errorCode: string | null
  errorDescription: string | null
  at: string
}

export interface RefundView {
  id: string
  paymentId: string
  orderId: string
  amount: number
  reason: RefundReason
  note: string | null
  status: RefundState
  razorpayRefundId: string | null
  failureReason: string | null
  /** Admin user id for ADMIN refunds. */
  requestedBy: string | null
  createdAt: string
  processedAt: string | null
}

export interface PaymentView {
  id: string
  orderId: string
  orderNumber: string
  customerId: string
  status: PaymentStatus
  amount: number
  currency: string
  method: string | null
  razorpayOrderId: string
  razorpayPaymentId: string | null
  refundedAmount: number
  refundStatus: PaymentRefundStatus
  expiresAt: string
  capturedAt: string | null
  createdAt: string
  /** Absent in admin list rows. */
  attempts?: PaymentAttempt[]
  refunds: RefundView[]
}

export type SimulatorOutcome = 'SUCCESS' | 'FAIL'

export const PAYMENT_ERRORS = {
  notReady: 'PAYMENT_NOT_READY',
  notFound: 'PAYMENT_NOT_FOUND',
  alreadyCaptured: 'PAYMENT_ALREADY_CAPTURED',
  expired: 'PAYMENT_EXPIRED',
  cancelled: 'PAYMENT_CANCELLED',
  providerUnavailable: 'PAYMENT_PROVIDER_UNAVAILABLE',
  invalidSignature: 'INVALID_SIGNATURE',
  failed: 'PAYMENT_FAILED',
} as const

export const paymentKeys = {
  all: ['payments'] as const,
  forOrder: (orderId: string) => [...paymentKeys.all, 'order', orderId] as const,
}

const forOrder = (orderId: string) => `/payments/orders/${segment(orderId)}`

/** 409 PAYMENT_NOT_READY (retry after a second) / PAYMENT_ALREADY_CAPTURED / PAYMENT_EXPIRED / PAYMENT_CANCELLED. */
export async function fetchCheckoutSession(orderId: string): Promise<CheckoutSession> {
  return (await httpClient.get<CheckoutSession>(`${forOrder(orderId)}/checkout`)).data
}

/** 400 INVALID_SIGNATURE · 404 PAYMENT_NOT_FOUND · 409 PAYMENT_FAILED. Idempotent. */
export async function verifyPayment(body: PaymentVerification): Promise<PaymentView> {
  return (await httpClient.post<PaymentView>('/payments/razorpay/verify', body)).data
}

/** The customer's own payment (any admin may read any). */
export async function fetchOrderPayment(orderId: string): Promise<PaymentView> {
  return (await httpClient.get<PaymentView>(forOrder(orderId))).data
}

/**
 * Simulator mode only (404 with real Razorpay keys). SUCCESS answers the signed ids to post to verify;
 * FAIL records a declined attempt like a `payment.failed` webhook.
 */
export async function simulatePayment(orderId: string, outcome: SimulatorOutcome): Promise<PaymentVerification | null> {
  const response = await httpClient.post<PaymentVerification | ''>(`/payments/simulator/orders/${segment(orderId)}/pay`, { outcome })
  const data = response.data
  return outcome === 'SUCCESS' && data && typeof data === 'object' ? data : null
}
