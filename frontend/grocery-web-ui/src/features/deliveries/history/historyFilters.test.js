import { describe, expect, it } from 'vitest'
import { filtersFromParams, filtersText, historyQuery, isFiltered, periodRange } from './historyFilters'

describe('history filters', () => {
  it('reads the filters from the URL; unknown values mean "all"', () => {
    expect(filtersFromParams(new URLSearchParams('status=FAILED&period=7d'))).toEqual({
      outcome: 'FAILED',
      period: '7d',
    })
    expect(filtersFromParams(new URLSearchParams('status=DELIVERED&period=today'))).toEqual({
      outcome: 'DELIVERED',
      period: 'today',
    })
    expect(filtersFromParams(new URLSearchParams(''))).toEqual({ outcome: 'all', period: 'all' })
    expect(filtersFromParams(new URLSearchParams('status=ASSIGNED&period=year'))).toEqual({
      outcome: 'all',
      period: 'all',
    })
    expect(filtersFromParams(new URLSearchParams('status=delivered&period=all'))).toEqual({
      outcome: 'all',
      period: 'all',
    })
    expect(isFiltered({ outcome: 'all', period: 'all' })).toBe(false)
    expect(isFiltered({ outcome: 'all', period: '30d' })).toBe(true)
  })

  it('turns a period into India-time days up to today, both included', () => {
    expect(periodRange('today', '2026-11-17')).toEqual({ from: '2026-11-17', to: '2026-11-17' })
    expect(periodRange('7d', '2026-11-17')).toEqual({ from: '2026-11-11', to: '2026-11-17' })
    expect(periodRange('30d', '2026-03-01')).toEqual({ from: '2026-01-31', to: '2026-03-01' })
    expect(periodRange('all', '2026-11-17')).toBeNull()
  })

  it('sends only the filters that are set, to the list and the export alike', () => {
    expect(historyQuery({ outcome: 'all', period: 'all' }, '2026-11-17')).toEqual({})
    expect(historyQuery({ outcome: 'FAILED', period: 'all' }, '2026-11-17')).toEqual({ status: 'FAILED' })
    expect(historyQuery({ outcome: 'DELIVERED', period: '7d' }, '2026-11-17')).toEqual({
      status: 'DELIVERED',
      from: '2026-11-11',
      to: '2026-11-17',
    })
  })

  it('says what the filters ask for', () => {
    expect(filtersText({ outcome: 'DELIVERED', period: '7d' })).toBe('delivered trips in the last 7 days')
    expect(filtersText({ outcome: 'FAILED', period: 'today' })).toBe('trips marked not delivered today')
    expect(filtersText({ outcome: 'all', period: '30d' })).toBe('trips in the last 30 days')
  })
})
