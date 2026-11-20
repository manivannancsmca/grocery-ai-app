export const CORRELATION_ID_HEADER = 'X-Correlation-Id'

/**
 * A new correlation ID per request. The gateway and every service log it, so one ID
 * links a browser action to backend log lines and Kafka events.
 * @returns {string}
 */
export function newCorrelationId() {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // crypto.randomUUID only exists in secure contexts (https or localhost).
  // Fallback for e.g. a phone opening the dev server by LAN IP over plain http.
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
