import { useCallback, useState } from 'react'

/** The parts of Razorpay Checkout (checkout.js) the app uses. */

export const RAZORPAY_CHECKOUT_URL = 'https://checkout.razorpay.com/v1/checkout.js'

/**
 * @typedef {object} RazorpaySuccessResponse
 * @property {string} razorpay_payment_id
 * @property {string} razorpay_order_id
 * @property {string} razorpay_signature
 */

/**
 * @typedef {object} RazorpayFailureResponse
 * @property {{ code?: string, description?: string, reason?: string, metadata?: { order_id?: string, payment_id?: string } }} error
 */

/**
 * @typedef {object} RazorpayOptions
 * @property {string} key
 * @property {string} order_id
 * @property {number} amount Paise.
 * @property {string} currency
 * @property {string} name
 * @property {string} [description]
 * @property {{ name?: string, email?: string, contact?: string }} [prefill]
 * @property {Record<string, string>} [notes]
 * @property {{ color?: string }} [theme]
 * @property {(response: RazorpaySuccessResponse) => void} handler
 * @property {{ ondismiss?: () => void, escape?: boolean }} [modal]
 */

/**
 * @typedef {object} RazorpayInstance
 * @property {() => void} open
 * @property {() => void} [close]
 * @property {(event: 'payment.failed', callback: (response: RazorpayFailureResponse) => void) => void} on
 */

/** @typedef {new (options: RazorpayOptions) => RazorpayInstance} RazorpayConstructor */

/** @type {Promise<RazorpayConstructor> | null} */
let loading = null

/**
 * Loads checkout.js once per page (later calls share the same promise). Resolves at once when the
 * global already exists — tests install a fake `window.Razorpay`, so the real script is never fetched.
 * A failed load can be retried.
 * @returns {Promise<RazorpayConstructor>}
 */
export function loadRazorpayCheckout() {
  if (window.Razorpay) return Promise.resolve(window.Razorpay)
  if (loading) return loading
  loading = new Promise((resolve, reject) => {
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

/** @typedef {'idle' | 'loading' | 'ready' | 'error'} RazorpayScriptState */

/** Small loader hook around `loadRazorpayCheckout` that also reports whether the script is loading. */
export function useRazorpayScript() {
  const [state, setState] = useState(() => (typeof window !== 'undefined' && window.Razorpay ? 'ready' : 'idle'))

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
