import { indiaDay, shiftDay } from '../../../shared/utils/indiaDate'

/** @typedef {import('../api').HistoryParams} HistoryParams */

/**
 * The filters of the partner's trip history, kept in the URL (`?status=FAILED&period=7d`) so a reload, the back
 * button and a shared link keep them. The server filters by the India-time day a trip ended on, both days included.
 */

/** @typedef {'all' | 'DELIVERED' | 'FAILED'} OutcomeFilter */
/**
 * Days up to today (India time): today only, the last 7 or 30 days, or every trip.
 * @typedef {'all' | 'today' | '7d' | '30d'} PeriodFilter
 */

/**
 * @typedef {object} HistoryFilters
 * @property {OutcomeFilter} outcome
 * @property {PeriodFilter} period
 */

/**
 * "Not delivered" is the page's word for FAILED (the trip badges and the overview say it too).
 * @type {readonly { value: OutcomeFilter, label: string }[]}
 */
export const OUTCOME_FILTERS = [
  { value: 'all', label: 'All' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'FAILED', label: 'Not delivered' },
]

/** @type {readonly { value: PeriodFilter, label: string }[]} */
export const PERIOD_FILTERS = [
  { value: 'all', label: 'All time' },
  { value: 'today', label: 'Today' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
]

/** The URL keys. "All" is left out of the URL, so the unfiltered page keeps its plain address. */
export const FILTER_PARAMS = { outcome: 'status', period: 'period' }

/**
 * The filters in the URL; an unknown value (an old or hand-edited link) counts as "all".
 * @param {URLSearchParams} params
 * @returns {HistoryFilters}
 */
export function filtersFromParams(params) {
  const status = params.get(FILTER_PARAMS.outcome)
  const period = params.get(FILTER_PARAMS.period)
  return {
    outcome: OUTCOME_FILTERS.find((option) => option.value === status && status !== 'all')?.value ?? 'all',
    period: PERIOD_FILTERS.find((option) => option.value === period && period !== 'all')?.value ?? 'all',
  }
}

/**
 * @param {HistoryFilters} filters
 * @returns {boolean}
 */
export function isFiltered(filters) {
  return filters.outcome !== 'all' || filters.period !== 'all'
}

/**
 * The India-time days of a period, both included, up to `today`; null = all time.
 * @param {PeriodFilter} period
 * @param {string} [today=indiaDay()]
 * @returns {{ from: string, to: string } | null}
 */
export function periodRange(period, today = indiaDay()) {
  if (period === 'today') return { from: today, to: today }
  if (period === '7d') return { from: shiftDay(today, -6), to: today }
  if (period === '30d') return { from: shiftDay(today, -29), to: today }
  return null
}

/**
 * The filters as the history and its export take them: `status`, `from`, `to` (absent when "all").
 * @param {HistoryFilters} filters
 * @param {string} [today=indiaDay()]
 * @returns {Pick<HistoryParams, 'status' | 'from' | 'to'>}
 */
export function historyQuery(filters, today = indiaDay()) {
  const range = periodRange(filters.period, today)
  return {
    ...(filters.outcome === 'all' ? {} : { status: filters.outcome }),
    ...(range ?? {}),
  }
}

/**
 * What the filters ask for, for the "no match" message: "delivered trips in the last 7 days", "trips today".
 * @param {HistoryFilters} filters
 * @returns {string}
 */
export function filtersText(filters) {
  const trips = { all: 'trips', DELIVERED: 'delivered trips', FAILED: 'trips marked not delivered' }[filters.outcome]
  const when = { all: '', today: ' today', '7d': ' in the last 7 days', '30d': ' in the last 30 days' }[filters.period]
  return `${trips}${when}`
}
