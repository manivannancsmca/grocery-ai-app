import { newCorrelationId } from '../../shared/api/correlation'

/**
 * The `Idempotency-Key` of the current checkout attempt. One key per attempt, kept in sessionStorage
 * together with what was being ordered (address + previewed total), so a retry after a network error,
 * a reload or a double click sends the same key and order-service creates the order only once.
 * A different address or total is a new attempt and gets a new key.
 */
const STORAGE_KEY = 'grocery.checkout.attempt'

/**
 * @typedef {object} StoredAttempt
 * @property {string} key
 * @property {string} fingerprint
 */

/**
 * What identifies one checkout attempt.
 * @param {string | null} addressId
 * @param {number} total
 * @returns {string}
 */
export function checkoutFingerprint(addressId, total) {
  return `${addressId ?? 'default'}|${total.toFixed(2)}`
}

/** @returns {StoredAttempt | null} */
function read() {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    return typeof parsed.key === 'string' && typeof parsed.fingerprint === 'string'
      ? { key: parsed.key, fingerprint: parsed.fingerprint }
      : null
  } catch {
    return null
  }
}

/**
 * @param {StoredAttempt} attempt
 * @returns {void}
 */
function write(attempt) {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(attempt))
  } catch {
    // Storage unavailable (private mode quota): the key then only lives for this call.
  }
}

/**
 * The key of this attempt: the stored one when it belongs to the same address + total, otherwise a new one.
 * @param {string} fingerprint
 * @returns {string}
 */
export function checkoutKeyFor(fingerprint) {
  const stored = read()
  if (stored && stored.fingerprint === fingerprint) return stored.key
  return renewCheckoutKey(fingerprint)
}

/**
 * A brand-new key for this attempt (after `422 IDEMPOTENCY_KEY_REUSED`).
 * @param {string} fingerprint
 * @returns {string}
 */
export function renewCheckoutKey(fingerprint) {
  const key = newCorrelationId()
  write({ key, fingerprint })
  return key
}

/**
 * Forget the key (after success or a definitive refusal).
 * @returns {void}
 */
export function clearCheckoutKey() {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // ignore
  }
}

/**
 * The previewed order changed (other address, new total): the stored key belongs to an old attempt.
 * @param {string} fingerprint
 * @returns {void}
 */
export function discardStaleCheckoutKey(fingerprint) {
  const stored = read()
  if (stored && stored.fingerprint !== fingerprint) clearCheckoutKey()
}
