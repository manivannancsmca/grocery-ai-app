import { useCallback, useState } from 'react'

/** The parts of Razorpay Checkout (checkout.js) the app uses. */

export const RAZORPAY_CHECKOUT_URL = 'https://checkout.razorpay.com/v1/checkout.js'

export interface RazorpaySuccessResponse {
  razorpay_payment_id: string
  razorpay_order_id: string
  razorpay_signature: string
}

export interface RazorpayFailureResponse {
  error: {
    code?: string
    description?: string
    reason?: string
    metadata?: { order_id?: string; payment_id?: string }
  }
}

export interface RazorpayOptions {
  key: string
  order_id: string
  /** Paise. */
  amount: number
  currency: string
  name: string
  description?: string
  prefill?: { name?: string; email?: string; contact?: string }
  notes?: Record<string, string>
  theme?: { color?: string }
  handler: (response: RazorpaySuccessResponse) => void
  modal?: { ondismiss?: () => void; escape?: boolean }
}

export interface RazorpayInstance {
  open: () => void
  close?: () => void
  on: (event: 'payment.failed', callback: (response: RazorpayFailureResponse) => void) => void
}

export type RazorpayConstructor = new (options: RazorpayOptions) => RazorpayInstance

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor
  }
}

let loading: Promise<RazorpayConstructor> | null = null

/**
 * Loads checkout.js once per page (later calls share the same promise). Resolves at once when the
 * global already exists — tests install a fake `window.Razorpay`, so the real script is never fetched.
 * A failed load can be retried.
 */
export function loadRazorpayCheckout(): Promise<RazorpayConstructor> {
  if (window.Razorpay) return Promise.resolve(window.Razorpay)
  if (loading) return loading
  loading = new Promise<RazorpayConstructor>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = RAZORPAY_CHECKOUT_URL
    script.async = true
    script.onload = () => {
      if (window.Razorpay) resolve(window.Razorpay)
      else fail()
    }
    script.onerror = fail
    function fail() {
      script.remove()
      loading = null
      reject(new Error('The payment window could not be loaded. Check your connection and try again.'))
    }
    document.head.appendChild(script)
  })
  return loading
}

export type RazorpayScriptState = 'idle' | 'loading' | 'ready' | 'error'

/** Small loader hook around `loadRazorpayCheckout` that also reports whether the script is loading. */
export function useRazorpayScript() {
  const [state, setState] = useState<RazorpayScriptState>(() => (typeof window !== 'undefined' && window.Razorpay ? 'ready' : 'idle'))

  const load = useCallback(async () => {
    setState((current) => (current === 'ready' ? current : 'loading'))
    try {
      const Razorpay = await loadRazorpayCheckout()
      setState('ready')
      return Razorpay
    } catch (error) {
      setState('error')
      throw error
    }
  }, [])

  return { state, load }
}
