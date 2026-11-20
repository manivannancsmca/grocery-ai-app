const dateTimeFormat = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' })
const dateFormat = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' })
const timeFormat = new Intl.DateTimeFormat('en-IN', { timeStyle: 'short' })
const percentFormat = new Intl.NumberFormat('en-IN', { style: 'percent', maximumFractionDigits: 0 })

/**
 * @param {string | null | undefined} value
 * @returns {Date | null}
 */
function parse(value) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/**
 * @param {string | null | undefined} value
 * @returns {string}
 */
export function formatDateTime(value) {
  const date = parse(value)
  return date ? dateTimeFormat.format(date) : '—'
}

/**
 * @param {string | null | undefined} value
 * @returns {string}
 */
export function formatDate(value) {
  const date = parse(value)
  return date ? dateFormat.format(date) : '—'
}

/**
 * Time of day only, e.g. `10:35 am` (order boards: everything there happens today).
 * @param {string | null | undefined} value
 * @returns {string}
 */
export function formatTime(value) {
  const date = parse(value)
  return date ? timeFormat.format(date) : '—'
}

/**
 * A 0–1 ratio as a whole percent: `0.75` → `75%`.
 * @param {number | null | undefined} ratio
 * @returns {string}
 */
export function formatPercent(ratio) {
  if (ratio === null || ratio === undefined || !Number.isFinite(ratio)) return '—'
  return percentFormat.format(ratio)
}

/**
 * How long ago, for feeds: `Just now`, `5 min ago`, `3 h ago`, `Yesterday`, `4 days ago`, then the date.
 * A time slightly in the future (clock skew) reads `Just now`.
 * @param {string | null | undefined} value
 * @param {number} [now=Date.now()]
 * @returns {string}
 */
export function formatRelativeTime(value, now = Date.now()) {
  const date = parse(value)
  if (!date) return '—'
  const seconds = Math.round((now - date.getTime()) / 1000)
  if (seconds < 45) return 'Just now'
  const minutes = Math.max(1, Math.round(seconds / 60))
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} h ago`
  const days = Math.round(hours / 24)
  if (days <= 1) return 'Yesterday'
  if (days < 7) return `${days} days ago`
  return dateFormat.format(date)
}

/**
 * `NORTH_INDIAN` → `North Indian`
 * @param {string} token
 * @returns {string}
 */
export function humanizeToken(token) {
  return token
    .toLowerCase()
    .split('_')
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

/**
 * @typedef {object} AddressLike
 * @property {string | null} [line1]
 * @property {string | null} [line2]
 * @property {string | null} [landmark]
 * @property {string | null} [city]
 * @property {string | null} [state]
 * @property {string | null} [postalCode]
 * @property {string | null} [country]
 */

/**
 * One-line postal address, skipping empty parts.
 * @param {AddressLike | null | undefined} address
 * @returns {string}
 */
export function formatAddress(address) {
  if (!address) return ''
  const cityLine = [address.city, address.state].filter(Boolean).join(', ')
  const cityWithPin = [cityLine, address.postalCode].filter(Boolean).join(' ')
  return [address.line1, address.line2, address.landmark, cityWithPin, address.country]
    .filter((part) => Boolean(part && part.trim()))
    .join(', ')
}

/**
 * An Indian mobile number as people read it: `+919840055004` → `+91 98400 55004`; anything else unchanged.
 * @param {string} phone
 * @returns {string}
 */
export function formatPhone(phone) {
  const match = /^\+91(\d{5})(\d{5})$/.exec(phone)
  return match ? `+91 ${match[1]} ${match[2]}` : phone
}

/**
 * @param {string | null | undefined} name
 * @returns {string}
 */
export function initials(name) {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean)
  const letters = parts.length > 1 ? [parts[0], parts[parts.length - 1]] : parts
  return letters.map((part) => part?.charAt(0).toUpperCase() ?? '').join('') || '?'
}

const inrWhole = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 })
const inrPaise = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

/**
 * Indian-rupee price: `249` → `₹249`, `249.5` → `₹249.50`, `125000` → `₹1,25,000`.
 * Another currency code (Phase 4 product prices carry one) is formatted with the same en-IN grouping.
 * @param {number | null | undefined} value
 * @param [currency='INR']
 * @returns {string}
 */
export function formatPrice(value, currency = 'INR') {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  const whole = Number.isInteger(value)
  if (currency === 'INR') return (whole ? inrWhole : inrPaise).format(value)
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency,
    minimumFractionDigits: whole ? 0 : 2,
    maximumFractionDigits: whole ? 0 : 2,
  }).format(value)
}

/**
 * Money with paise always shown (Phase 4 grocery prices): `49` → `₹49.00`, `125000` → `₹1,25,000.00`.
 * Indian digit grouping for every currency code.
 * @param {number | null | undefined} value
 * @param [currency='INR']
 * @returns {string}
 */
export function formatMoney(value, currency = 'INR') {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  if (currency === 'INR') return inrPaise.format(value)
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

/**
 * Signed change in percent: `-48.98` → `−48.98%`, `12.5` → `+12.5%`, `0` → `0%`.
 * @param {number | null | undefined} value
 * @returns {string}
 */
export function formatSignedPercent(value) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  const magnitude = Math.abs(value).toLocaleString('en-IN', { maximumFractionDigits: 2 })
  return `${value > 0 ? '+' : value < 0 ? '−' : ''}${magnitude}%`
}

/**
 * Whole-number counts with Indian grouping: `1000000` → `10,00,000`.
 * @param {number | null | undefined} value
 * @returns {string}
 */
export function formatCount(value) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return value.toLocaleString('en-IN')
}

/**
 * Time left as `m:ss` (`754_000` → `12:34`); never negative.
 * @param {number} remainingMs
 * @returns {string}
 */
export function formatCountdown(remainingMs) {
  const totalSeconds = Math.max(0, Math.ceil(remainingMs / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

/**
 * `2.437` → `2.4 km`, `0.35` → `350 m`.
 * @param {number | null | undefined} km
 * @returns {string | null}
 */
export function formatDistanceKm(km) {
  if (km === null || km === undefined || !Number.isFinite(km)) return null
  if (km < 1) return `${Math.max(Math.round(km * 1000), 10)} m`
  return `${km < 10 ? km.toFixed(1) : Math.round(km)} km`
}
