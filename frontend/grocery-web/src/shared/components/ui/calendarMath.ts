/**
 * Calendar arithmetic for the date picker, on `YYYY-MM-DD` strings (UTC midnight, never local time), like
 * `shared/utils/indiaDate`.
 */

export interface MonthView {
  year: number
  /** 0-based, as `Date` uses it. */
  month: number
}

export interface GridDay {
  day: string
  /** False for the leading and trailing days of the neighbouring months. */
  inMonth: boolean
}

const monthLabel = new Intl.DateTimeFormat('en-IN', { month: 'long', year: 'numeric', timeZone: 'UTC' })

function iso(utc: number): string {
  return new Date(utc).toISOString().slice(0, 10)
}

/** The month a `YYYY-MM-DD` day (or a `YYYY-MM-DDTHH:mm` value) belongs to; `fallback` when it is not a day. */
export function monthOf(value: string, fallback: MonthView): MonthView {
  const match = /^(\d{4})-(\d{2})-\d{2}/.exec(value)
  if (!match) return fallback
  return { year: Number(match[1]), month: Number(match[2]) - 1 }
}

export function addMonths(view: MonthView, delta: number): MonthView {
  const index = view.year * 12 + view.month + delta
  return { year: Math.floor(index / 12), month: ((index % 12) + 12) % 12 }
}

/** `November 2026` */
export function formatMonth(view: MonthView): string {
  return monthLabel.format(Date.UTC(view.year, view.month, 1))
}

/** Six weeks, Sunday first, covering the month (so the grid never changes height). */
export function monthGrid(view: MonthView): GridDay[][] {
  const first = Date.UTC(view.year, view.month, 1)
  const start = first - new Date(first).getUTCDay() * 86_400_000
  return Array.from({ length: 6 }, (_, week) =>
    Array.from({ length: 7 }, (_, weekday) => {
      const utc = start + (week * 7 + weekday) * 86_400_000
      return { day: iso(utc), inMonth: new Date(utc).getUTCMonth() === view.month }
    }),
  )
}

/** The day `days` away, keeping to the same weekday on a ±7 move. */
export function moveDay(day: string, days: number): string {
  const [year, month, date] = day.split('-').map(Number)
  return iso(Date.UTC(year ?? 1970, (month ?? 1) - 1, (date ?? 1) + days))
}

/** The same day of the month `delta` months away, clamped to that month's length (31 Jan + 1 → 28/29 Feb). */
export function moveMonth(day: string, delta: number): string {
  const [year, month, date] = day.split('-').map(Number)
  const target = addMonths({ year: year ?? 1970, month: (month ?? 1) - 1 }, delta)
  const length = new Date(Date.UTC(target.year, target.month + 1, 0)).getUTCDate()
  return iso(Date.UTC(target.year, target.month, Math.min(date ?? 1, length)))
}

/** Whether `day` is inside the optional `min`…`max` (inclusive; only the date part of a datetime bound counts). */
export function inRange(day: string, min?: string, max?: string): boolean {
  if (min && day < min.slice(0, 10)) return false
  if (max && day > max.slice(0, 10)) return false
  return true
}
