import { daysInRange, indiaDay, isIsoDay, shiftDay } from '../../shared/utils/indiaDate'
import { MAX_STATS_DAYS } from './api'

/** @typedef {import('./api').StatsRange} StatsRange */

/**
 * The periods a partner picks on their dashboard.
 * @typedef {'today' | 'week' | 'month' | 'custom'} StatsPeriod
 */

/** @type {readonly { value: StatsPeriod, label: string }[]} */
export const STATS_PERIODS = [
  { value: 'today', label: 'Today' },
  { value: 'week', label: 'This week' },
  { value: 'month', label: 'This month' },
  { value: 'custom', label: 'Custom' },
]

/**
 * @param {unknown} value
 * @returns {value is StatsPeriod}
 */
export function isStatsPeriod(value) {
  return STATS_PERIODS.some((period) => period.value === value)
}

/**
 * The days of a preset period, India time, up to today: today; this week from Monday; this month from the 1st.
 * (`custom` has no preset: it uses the dates the partner typed.)
 * @param {Exclude<StatsPeriod, 'custom'>} period
 * @param {string} [today=indiaDay()]
 * @returns {StatsRange}
 */
export function presetRange(period, today = indiaDay()) {
  if (period === 'today') return { from: today, to: today }
  if (period === 'month') return { from: `${today.slice(0, 8)}01`, to: today }
  // Monday = 0 … Sunday = 6 (a YYYY-MM-DD day read as UTC midnight has the right weekday).
  const sinceMonday = (new Date(`${today}T00:00:00Z`).getUTCDay() + 6) % 7
  return { from: shiftDay(today, -sinceMonday), to: today }
}

/**
 * Why custom dates cannot be asked for, or null when they can (the server answers 400 otherwise).
 * @param {string} from
 * @param {string} to
 * @returns {string | null}
 */
export function customRangeError(from, to) {
  if (!isIsoDay(from) || !isIsoDay(to)) return 'Choose a start date and an end date.'
  if (from > to) return 'The start date must be on or before the end date.'
  const days = daysInRange(from, to)
  if (days > MAX_STATS_DAYS) return `Choose at most ${MAX_STATS_DAYS} days — this range has ${days}.`
  return null
}

/**
 * "1 h 25 min", "40 min", "< 1 min" (more than nothing), "0 min".
 * @param {number | null | undefined} minutes
 * @returns {string}
 */
export function formatMinutes(minutes) {
  if (minutes === null || minutes === undefined || !Number.isFinite(minutes)) return '—'
  if (minutes > 0 && minutes < 0.5) return '< 1 min'
  const whole = Math.round(minutes)
  const hours = Math.floor(whole / 60)
  const rest = whole % 60
  if (hours === 0) return `${rest} min`
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
}
