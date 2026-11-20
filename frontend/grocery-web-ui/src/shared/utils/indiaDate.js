/**
 * Calendar days in India time (`Asia/Kolkata`) as `YYYY-MM-DD` strings — the unit of the store reports and
 * of the "closed today" list. Day arithmetic works on the date strings (UTC midnight), never on local time.
 */

export const INDIA_TIME_ZONE = 'Asia/Kolkata'

const DAY_MS = 86_400_000

// en-CA formats as YYYY-MM-DD.
const indiaDayFormat = new Intl.DateTimeFormat('en-CA', {
  timeZone: INDIA_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})
const shortDay = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', timeZone: 'UTC' })
const longDay = new Intl.DateTimeFormat('en-IN', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  timeZone: 'UTC',
})

/**
 * `YYYY-MM-DD` (an `<input type="date">` value).
 * @param {unknown} value
 * @returns {value is string}
 */
export function isIsoDay(value) {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(dayToUtc(value))
}

/**
 * @param {string} day
 * @returns {number}
 */
function dayToUtc(day) {
  const [year, month, date] = day.split('-').map(Number)
  if (year === undefined || month === undefined || date === undefined) return Number.NaN
  return Date.UTC(year, month - 1, date)
}

/**
 * The India-time day of an instant (now by default).
 * @param {number | string | Date} [at=Date.now()]
 * @returns {string}
 */
export function indiaDay(at = Date.now()) {
  const instant = typeof at === 'string' ? new Date(at) : at
  return indiaDayFormat.format(instant)
}

/**
 * `day` moved by `days` (negative = earlier).
 * @param {string} day
 * @param {number} days
 * @returns {string}
 */
export function shiftDay(day, days) {
  return new Date(dayToUtc(day) + days * DAY_MS).toISOString().slice(0, 10)
}

/**
 * Number of days from `from` to `to`, both included (`2026-11-05`…`2026-11-11` → 7).
 * @param {string} from
 * @param {string} to
 * @returns {number}
 */
export function daysInRange(from, to) {
  return Math.round((dayToUtc(to) - dayToUtc(from)) / DAY_MS) + 1
}

/**
 * `5 Nov`
 * @param {string} day
 * @returns {string}
 */
export function formatShortDay(day) {
  const utc = dayToUtc(day)
  return Number.isFinite(utc) ? shortDay.format(utc) : day
}

/**
 * `Thu, 5 Nov 2026`
 * @param {string} day
 * @returns {string}
 */
export function formatLongDay(day) {
  const utc = dayToUtc(day)
  return Number.isFinite(utc) ? longDay.format(utc) : day
}
