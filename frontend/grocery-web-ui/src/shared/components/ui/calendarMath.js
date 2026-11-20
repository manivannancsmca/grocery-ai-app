/**
 * Calendar arithmetic for the date picker, on `YYYY-MM-DD` strings (UTC midnight, never local time), like
 * `shared/utils/indiaDate`.
 */

/**
 * @typedef {object} MonthView
 * @property {number} year
 * @property {number} month 0-based, as `Date` uses it.
 */

/**
 * @typedef {object} GridDay
 * @property {string} day
 * @property {boolean} inMonth False for the leading and trailing days of the neighbouring months.
 */

const monthLabel = new Intl.DateTimeFormat('en-IN', { month: 'long', year: 'numeric', timeZone: 'UTC' })

/**
 * @param {number} utc
 * @returns {string}
 */
function iso(utc) {
  return new Date(utc).toISOString().slice(0, 10)
}

/**
 * The month a `YYYY-MM-DD` day (or a `YYYY-MM-DDTHH:mm` value) belongs to; `fallback` when it is not a day.
 * @param {string} value
 * @param {MonthView} fallback
 * @returns {MonthView}
 */
export function monthOf(value, fallback) {
  const match = /^(\d{4})-(\d{2})-\d{2}/.exec(value)
  if (!match) return fallback
  return { year: Number(match[1]), month: Number(match[2]) - 1 }
}

/**
 * @param {MonthView} view
 * @param {number} delta
 * @returns {MonthView}
 */
export function addMonths(view, delta) {
  const index = view.year * 12 + view.month + delta
  return { year: Math.floor(index / 12), month: ((index % 12) + 12) % 12 }
}

/**
 * `November 2026`
 * @param {MonthView} view
 * @returns {string}
 */
export function formatMonth(view) {
  return monthLabel.format(Date.UTC(view.year, view.month, 1))
}

/**
 * Six weeks, Sunday first, covering the month (so the grid never changes height).
 * @param {MonthView} view
 * @returns {GridDay[][]}
 */
export function monthGrid(view) {
  const first = Date.UTC(view.year, view.month, 1)
  const start = first - new Date(first).getUTCDay() * 86_400_000
  return Array.from({ length: 6 }, (_, week) =>
    Array.from({ length: 7 }, (_, weekday) => {
      const utc = start + (week * 7 + weekday) * 86_400_000
      return { day: iso(utc), inMonth: new Date(utc).getUTCMonth() === view.month }
    }),
  )
}

/**
 * The day `days` away, keeping to the same weekday on a ±7 move.
 * @param {string} day
 * @param {number} days
 * @returns {string}
 */
export function moveDay(day, days) {
  const [year, month, date] = day.split('-').map(Number)
  return iso(Date.UTC(year ?? 1970, (month ?? 1) - 1, (date ?? 1) + days))
}

/**
 * The same day of the month `delta` months away, clamped to that month's length (31 Jan + 1 → 28/29 Feb).
 * @param {string} day
 * @param {number} delta
 * @returns {string}
 */
export function moveMonth(day, delta) {
  const [year, month, date] = day.split('-').map(Number)
  const target = addMonths({ year: year ?? 1970, month: (month ?? 1) - 1 }, delta)
  const length = new Date(Date.UTC(target.year, target.month + 1, 0)).getUTCDate()
  return iso(Date.UTC(target.year, target.month, Math.min(date ?? 1, length)))
}

/**
 * Whether `day` is inside the optional `min`…`max` (inclusive; only the date part of a datetime bound counts).
 * @param {string} day
 * @param {string} [min]
 * @param {string} [max]
 * @returns {boolean}
 */
export function inRange(day, min, max) {
  if (min && day < min.slice(0, 10)) return false
  if (max && day > max.slice(0, 10)) return false
  return true
}
