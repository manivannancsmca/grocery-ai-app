import { daysInRange, indiaDay, isIsoDay, shiftDay } from '../../shared/utils/indiaDate'
import { DEFAULT_REPORT_DAYS, MAX_REPORT_DAYS } from './api'

/** @typedef {import('./api').ReportRange} ReportRange */

/**
 * The last `days` days including today (India time).
 * @param {number} days
 * @param {string} [today=indiaDay()]
 * @returns {ReportRange}
 */
export function lastDays(days, today = indiaDay()) {
  return { from: shiftDay(today, -(days - 1)), to: today }
}

/**
 * @param {string} [today=indiaDay()]
 * @returns {ReportRange}
 */
export function defaultReportRange(today = indiaDay()) {
  return lastDays(DEFAULT_REPORT_DAYS, today)
}

/**
 * Why a chosen range cannot be requested, or null when it can (the server answers 400 INVALID_PARAMETER otherwise).
 * @param {string} from
 * @param {string} to
 * @returns {string | null}
 */
export function reportRangeError(from, to) {
  if (!isIsoDay(from) || !isIsoDay(to)) return 'Choose a start date and an end date.'
  if (from > to) return 'The start date must be on or before the end date.'
  const days = daysInRange(from, to)
  if (days > MAX_REPORT_DAYS) return `Choose at most ${MAX_REPORT_DAYS} days — this range has ${days}.`
  return null
}
