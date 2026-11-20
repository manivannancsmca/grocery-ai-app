/**
 * Calendar days in India time (`Asia/Kolkata`) as `YYYY-MM-DD` strings — the unit of the store reports and
 * of the "closed today" list. Day arithmetic works on the date strings (UTC midnight), never on local time.
 */

export const INDIA_TIME_ZONE = 'Asia/Kolkata'

const DAY_MS = 86_400_000

// en-CA formats as YYYY-MM-DD.
const indiaDayFormat = new Intl.DateTimeFormat('en-CA', { timeZone: INDIA_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit' })
const shortDay = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', timeZone: 'UTC' })
const longDay = new Intl.DateTimeFormat('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })

/** `YYYY-MM-DD` (an `<input type="date">` value). */
export function isIsoDay(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(dayToUtc(value))
}

function dayToUtc(day: string): number {
  const [year, month, date] = day.split('-').map(Number)
  if (year === undefined || month === undefined || date === undefined) return Number.NaN
  return Date.UTC(year, month - 1, date)
}

/** The India-time day of an instant (now by default). */
export function indiaDay(at: number | string | Date = Date.now()): string {
  const instant = typeof at === 'string' ? new Date(at) : at
  return indiaDayFormat.format(instant)
}

/** `day` moved by `days` (negative = earlier). */
export function shiftDay(day: string, days: number): string {
  return new Date(dayToUtc(day) + days * DAY_MS).toISOString().slice(0, 10)
}

/** Number of days from `from` to `to`, both included (`2026-11-05`…`2026-11-11` → 7). */
export function daysInRange(from: string, to: string): number {
  return Math.round((dayToUtc(to) - dayToUtc(from)) / DAY_MS) + 1
}

/** `5 Nov` */
export function formatShortDay(day: string): string {
  const utc = dayToUtc(day)
  return Number.isFinite(utc) ? shortDay.format(utc) : day
}

/** `Thu, 5 Nov 2026` */
export function formatLongDay(day: string): string {
  const utc = dayToUtc(day)
  return Number.isFinite(utc) ? longDay.format(utc) : day
}
