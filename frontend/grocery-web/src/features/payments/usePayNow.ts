import { useEffect, useRef, useState } from 'react'
import { errorMessage, hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import {
  type CheckoutSession,
  fetchCheckoutSession,
  PAYMENT_ERRORS,
  type PaymentVerification,
  simulatePayment,
  type SimulatorOutcome,
  verifyPayment,
} from './api'
import { useRazorpayScript } from './razorpay'

/** `409 PAYMENT_NOT_READY`: the payment is still being created (stock check / event on its way). */
export const PAYMENT_NOT_READY_RETRY_MS = 1_000
export const PAYMENT_NOT_READY_ATTEMPTS = 10

/** Brand colour of the Razorpay Checkout modal (emerald-600). */
const CHECKOUT_THEME_COLOR = '#059669'

/**
 * idle → preparing (checkout session, retried while not ready) → checkout (Razorpay modal or the
 * simulator dialog is open) → verifying (signature posted to verify) → idle.
 */
export type PayPhase = 'idle' | 'preparing' | 'checkout' | 'verifying'

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function verifyFailureMessage(error: unknown): string {
  if (hasErrorCode(error, PAYMENT_ERRORS.invalidSignature)) {
    return 'We could not verify this payment. If money left your account, it will be confirmed or refunded automatically.'
  }
  if (hasErrorCode(error, PAYMENT_ERRORS.failed)) {
    return problemString(error, 'errorDescription') ?? problemString(error, 'detail') ?? 'The payment failed. You can try again.'
  }
  return `We could not confirm the payment yet: ${errorMessage(error)} We keep checking — please don’t pay twice.`
}

function sessionFailureMessage(error: unknown): string {
  if (hasErrorCode(error, PAYMENT_ERRORS.providerUnavailable)) {
    return 'The payment provider is not reachable right now. Please try again in a moment.'
  }
  if (hasErrorCode(error, PAYMENT_ERRORS.notReady)) return 'Your payment is taking longer than usual to prepare. Please try again.'
  return `Could not start the payment: ${errorMessage(error)}`
}

interface SimulatorState {
  session: CheckoutSession
  pending: SimulatorOutcome | null
  error: string | null
}

/**
 * "Pay now" for one order: fetches the checkout session, then opens Razorpay Checkout (checkout.js,
 * loaded once) or — in local simulator mode — the in-app test-payment dialog. A successful payment's
 * ids and signature are posted to `/payments/razorpay/verify`; `onPaymentChange` then re-reads the order.
 */
export function usePayNow(orderId: string, onPaymentChange: () => void) {
  const toast = useToast()
  const razorpay = useRazorpayScript()
  const [phase, setPhase] = useState<PayPhase>('idle')
  const [failure, setFailure] = useState<string | null>(null)
  const [simulator, setSimulator] = useState<SimulatorState | null>(null)
  // payment-service has the money; order-service confirms the order a moment later (PaymentCaptured event).
  // Until then "Pay now" must not come back.
  const [paid, setPaid] = useState(false)
  const mounted = useRef(true)
  // The latest callback, for handlers Razorpay calls later.
  const onChange = useRef(onPaymentChange)

  useEffect(() => {
    onChange.current = onPaymentChange
  })

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  function update(apply: () => void) {
    if (mounted.current) apply()
  }

  async function verify(ids: PaymentVerification) {
    update(() => setPhase('verifying'))
    try {
      await verifyPayment(ids)
      update(() => {
        setFailure(null)
        setPaid(true)
      })
      toast.success('Payment received. Confirming your order…')
    } catch (error) {
      update(() => setFailure(verifyFailureMessage(error)))
    } finally {
      update(() => setPhase('idle'))
      onChange.current()
    }
  }

  async function loadSession(): Promise<CheckoutSession | null> {
    for (let attempt = 1; ; attempt++) {
      try {
        return await fetchCheckoutSession(orderId)
      } catch (error) {
        if (hasErrorCode(error, PAYMENT_ERRORS.notReady) && attempt < PAYMENT_NOT_READY_ATTEMPTS && mounted.current) {
          await sleep(PAYMENT_NOT_READY_RETRY_MS)
          continue
        }
        if (hasErrorCode(error, PAYMENT_ERRORS.alreadyCaptured)) {
          update(() => setPaid(true))
          toast.info('This order is already paid. Updating it now…')
        } else if (hasErrorCode(error, PAYMENT_ERRORS.expired)) {
          update(() => setFailure('The time to pay for this order is over, so it will be cancelled.'))
        } else if (hasErrorCode(error, PAYMENT_ERRORS.cancelled)) {
          update(() => setFailure('This order was cancelled, so it can no longer be paid.'))
        } else {
          update(() => setFailure(sessionFailureMessage(error)))
          return null
        }
        onChange.current()
        return null
      }
    }
  }

  async function openRazorpay(session: CheckoutSession) {
    let Razorpay
    try {
      Razorpay = await razorpay.load()
    } catch (error) {
      update(() => {
        setFailure(errorMessage(error))
        setPhase('idle')
      })
      return
    }
    if (!mounted.current) return
    const checkout = new Razorpay({
      key: session.keyId,
      order_id: session.razorpayOrderId,
      amount: session.amount,
      currency: session.currency,
      name: session.merchantName,
      description: session.description,
      prefill: {
        name: session.prefill.name ?? undefined,
        email: session.prefill.email ?? undefined,
        contact: session.prefill.contact ?? undefined,
      },
      notes: { orderId: session.orderId, orderNumber: session.orderNumber },
      theme: { color: CHECKOUT_THEME_COLOR },
      handler: (response) =>
        void verify({
          razorpayOrderId: response.razorpay_order_id,
          razorpayPaymentId: response.razorpay_payment_id,
          razorpaySignature: response.razorpay_signature,
        }),
      modal: {
        ondismiss: () => update(() => setPhase((current) => (current === 'checkout' ? 'idle' : current))),
      },
    })
    // Razorpay keeps its window open after a decline so the customer can try another method.
    checkout.on('payment.failed', (response) => {
      update(() => setFailure(response.error.description || 'The payment was declined. You can try again.'))
      onChange.current()
    })
    setPhase('checkout')
    checkout.open()
  }

  async function start() {
    if (phase !== 'idle') return
    setFailure(null)
    setPhase('preparing')
    const session = await loadSession()
    if (!session) {
      update(() => setPhase('idle'))
      return
    }
    if (!mounted.current) return
    if (session.provider === 'SIMULATOR') {
      setSimulator({ session, pending: null, error: null })
      setPhase('checkout')
      return
    }
    await openRazorpay(session)
  }

  async function simulate(outcome: SimulatorOutcome) {
    if (!simulator || simulator.pending) return
    setSimulator({ ...simulator, pending: outcome, error: null })
    let ids: PaymentVerification | null
    try {
      ids = await simulatePayment(orderId, outcome)
    } catch (error) {
      update(() => setSimulator((current) => current && { ...current, pending: null, error: errorMessage(error) }))
      return
    }
    update(() => setSimulator(null))
    if (outcome === 'SUCCESS' && ids) {
      // Exactly what Razorpay Checkout's handler does.
      await verify(ids)
      return
    }
    update(() => {
      setFailure(
        outcome === 'SUCCESS'
          ? 'The test payment did not return a payment signature. Please try again.'
          : 'The test payment was declined. You can try again.',
      )
      setPhase('idle')
    })
    onChange.current()
  }

  function closeSimulator() {
    if (simulator?.pending) return
    setSimulator(null)
    setPhase('idle')
  }

  return {
    phase,
    failure,
    /** The payment was verified (or was already captured); the order is being confirmed. */
    paid,
    start,
    simulator: simulator && { ...simulator, pay: simulate, close: closeSimulator },
  }
}
