import { newCorrelationId } from '../../shared/api/correlation'

/**
 * The `Idempotency-Key` of the current checkout attempt. One key per attempt, kept in sessionStorage
 * together with what was being ordered (address + previewed total), so a retry after a network error,
 * a reload or a double click sends the same key and order-service creates the order only once.
 * A different address or total is a new attempt and gets a new key.
 */
const STORAGE_KEY = 'grocery.checkout.attempt'

interface StoredAttempt {
  key: string
  fingerprint: string
}

/** What identifies one checkout attempt. */
export function checkoutFingerprint(addressId: string | null, total: number): string {
  return `${addressId ?? 'default'}|${total.toFixed(2)}`
}

function read(): StoredAttempt | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<StoredAttempt>
    return typeof parsed.key === 'string' && typeof parsed.fingerprint === 'string' ? { key: parsed.key, fingerprint: parsed.fingerprint } : null
  } catch {
    return null
  }
}

function write(attempt: StoredAttempt): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(attempt))
  } catch {
    // Storage unavailable (private mode quota): the key then only lives for this call.
  }
}

/** The key of this attempt: the stored one when it belongs to the same address + total, otherwise a new one. */
export function checkoutKeyFor(fingerprint: string): string {
  const stored = read()
  if (stored && stored.fingerprint === fingerprint) return stored.key
  return renewCheckoutKey(fingerprint)
}

/** A brand-new key for this attempt (after `422 IDEMPOTENCY_KEY_REUSED`). */
export function renewCheckoutKey(fingerprint: string): string {
  const key = newCorrelationId()
  write({ key, fingerprint })
  return key
}

/** Forget the key (after success or a definitive refusal). */
export function clearCheckoutKey(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // ignore
  }
}

/** The previewed order changed (other address, new total): the stored key belongs to an old attempt. */
export function discardStaleCheckoutKey(fingerprint: string): void {
  const stored = read()
  if (stored && stored.fingerprint !== fingerprint) clearCheckoutKey()
}
