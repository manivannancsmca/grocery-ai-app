import { indiaDay, shiftDay } from '../../../shared/utils/indiaDate'
import type { HistoryParams } from '../api'

/**
 * The filters of the partner's trip history, kept in the URL (`?status=FAILED&period=7d`) so a reload, the back
 * button and a shared link keep them. The server filters by the India-time day a trip ended on, both days included.
 */

export type OutcomeFilter = 'all' | 'DELIVERED' | 'FAILED'
/** Days up to today (India time): today only, the last 7 or 30 days, or every trip. */
export type PeriodFilter = 'all' | 'today' | '7d' | '30d'

export interface HistoryFilters {
  outcome: OutcomeFilter
  period: PeriodFilter
}

/** "Not delivered" is the page's word for FAILED (the trip badges and the overview say it too). */
export const OUTCOME_FILTERS: readonly { value: OutcomeFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'FAILED', label: 'Not delivered' },
]

export const PERIOD_FILTERS: readonly { value: PeriodFilter; label: string }[] = [
  { value: 'all', label: 'All time' },
  { value: 'today', label: 'Today' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
]

/** The URL keys. "All" is left out of the URL, so the unfiltered page keeps its plain address. */
export const FILTER_PARAMS = { outcome: 'status', period: 'period' } as const

/** The filters in the URL; an unknown value (an old or hand-edited link) counts as "all". */
export function filtersFromParams(params: URLSearchParams): HistoryFilters {
  const status = params.get(FILTER_PARAMS.outcome)
  const period = params.get(FILTER_PARAMS.period)
  return {
    outcome: OUTCOME_FILTERS.find((option) => option.value === status && status !== 'all')?.value ?? 'all',
    period: PERIOD_FILTERS.find((option) => option.value === period && period !== 'all')?.value ?? 'all',
  }
}

export function isFiltered(filters: HistoryFilters): boolean {
  return filters.outcome !== 'all' || filters.period !== 'all'
}

/** The India-time days of a period, both included, up to `today`; null = all time. */
export function periodRange(period: PeriodFilter, today: string = indiaDay()): { from: string; to: string } | null {
  if (period === 'today') return { from: today, to: today }
  if (period === '7d') return { from: shiftDay(today, -6), to: today }
  if (period === '30d') return { from: shiftDay(today, -29), to: today }
  return null
}

/** The filters as the history and its export take them: `status`, `from`, `to` (absent when "all"). */
export function historyQuery(filters: HistoryFilters, today: string = indiaDay()): Pick<HistoryParams, 'status' | 'from' | 'to'> {
  const range = periodRange(filters.period, today)
  return {
    ...(filters.outcome === 'all' ? {} : { status: filters.outcome }),
    ...(range ?? {}),
  }
}

/** What the filters ask for, for the "no match" message: "delivered trips in the last 7 days", "trips today". */
export function filtersText(filters: HistoryFilters): string {
  const trips = { all: 'trips', DELIVERED: 'delivered trips', FAILED: 'trips marked not delivered' }[filters.outcome]
  const when = { all: '', today: ' today', '7d': ' in the last 7 days', '30d': ' in the last 30 days' }[filters.period]
  return `${trips}${when}`
}
