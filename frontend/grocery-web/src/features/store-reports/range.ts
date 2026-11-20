import { daysInRange, indiaDay, isIsoDay, shiftDay } from '../../shared/utils/indiaDate'
import { DEFAULT_REPORT_DAYS, MAX_REPORT_DAYS, type ReportRange } from './api'

/** The last `days` days including today (India time). */
export function lastDays(days: number, today: string = indiaDay()): ReportRange {
  return { from: shiftDay(today, -(days - 1)), to: today }
}

export function defaultReportRange(today: string = indiaDay()): ReportRange {
  return lastDays(DEFAULT_REPORT_DAYS, today)
}

/** Why a chosen range cannot be requested, or null when it can (the server answers 400 INVALID_PARAMETER otherwise). */
export function reportRangeError(from: string, to: string): string | null {
  if (!isIsoDay(from) || !isIsoDay(to)) return 'Choose a start date and an end date.'
  if (from > to) return 'The start date must be on or before the end date.'
  const days = daysInRange(from, to)
  if (days > MAX_REPORT_DAYS) return `Choose at most ${MAX_REPORT_DAYS} days — this range has ${days}.`
  return null
}
