import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { partnerStats } from '../../test/deliveryFixtures'
import { mockApi } from '../../test/mockApi'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import { PartnerPerformance } from './PartnerPerformance'
import { customRangeError, formatMinutes, presetRange } from './statsPeriod'

const WEEK = partnerStats({
  totals: {
    ...partnerStats().totals,
    assigned: 6,
    delivered: 4,
    failed: 1,
    cancelled: 1,
    inProgress: 1,
    offersPending: 2,
    offersReceived: 8,
    offersAccepted: 6,
    offersRejected: 1,
    offersExpired: 1,
    successRate: 0.8,
    acceptanceRate: 0.75,
    distanceKm: 11.6,
    earnings: 169.6,
    avgPickupMinutes: 9.5,
    avgRideMinutes: 14,
    avgTotalMinutes: 23.5,
    onlineMinutes: 305,
  },
})

function renderPerformance(path = '/delivery') {
  signInAs(['DELIVERY_PARTNER'])
  const api = mockApi({ 'GET /delivery/stats': { status: 200, data: WEEK } })
  const utils = renderApp(path, [{ path: '/delivery', element: <PartnerPerformance /> }])
  return { api, ...utils }
}

describe('Partner performance', { timeout: 20_000 }, () => {
  it('shows the partner’s own numbers for this week and the charts behind them', async () => {
    const { api } = renderPerformance()

    const headline = await screen.findByLabelText('Headline numbers')
    expect(headline).toHaveTextContent('Estimated earnings')
    expect(headline).toHaveTextContent('₹169.60')
    expect(headline).toHaveTextContent('of 6 assigned')
    expect(headline).toHaveTextContent('80%')
    expect(headline).toHaveTextContent('24 min') // 23.5 min, rounded
    expect(headline).toHaveTextContent('11.6 km')
    expect(headline).toHaveTextContent('5 h 5 min')
    expect(screen.getByText(/In progress now/)).toHaveTextContent('1')
    expect(screen.getByText(/Offers waiting/)).toHaveTextContent('2')

    // This week (the default) is asked for, from Monday to today.
    expect(api.callsTo('GET /delivery/stats')[0]?.params).toEqual(presetRange('week'))
    expect(screen.getByRole('button', { name: 'This week' })).toHaveAttribute('aria-pressed', 'true')

    for (const chart of [
      'Deliveries by day',
      'How your trips ended',
      'Estimated earnings',
      'Offer responses',
      'Where the time goes',
      'Online time',
    ]) {
      expect(screen.getByRole('region', { name: chart })).toBeInTheDocument()
    }
    const offers = screen.getByRole('region', { name: 'Offer responses' })
    expect(within(offers).getByText('Acceptance rate')).toBeInTheDocument()
    expect(offers).toHaveTextContent('75%')
    const trips = screen.getByRole('region', { name: 'How your trips ended' })
    expect(within(trips).getByText('Success rate (delivered vs not delivered)')).toBeInTheDocument()
    expect(trips).toHaveTextContent('80%')
    const times = screen.getByRole('region', { name: 'Where the time goes' })
    expect(times).toHaveTextContent('Getting to the pickup')
    expect(times).toHaveTextContent('Whole delivery')
    expect(
      screen.getByText(/Earnings are an estimate: ₹25.00 per delivered order plus ₹6.00 per km/),
    ).toBeInTheDocument()
  })

  it('switches between today, this month and custom days, and explains a wrong custom range', async () => {
    const user = userEvent.setup()
    const { api, router } = renderPerformance()
    await screen.findByLabelText('Headline numbers')

    await user.click(screen.getByRole('button', { name: 'Today' }))
    await waitFor(() => expect(api.callsTo('GET /delivery/stats').at(-1)?.params).toEqual(presetRange('today')))
    expect(new URLSearchParams(router.state.location.search).get('period')).toBe('today')

    await user.click(screen.getByRole('button', { name: 'This month' }))
    await waitFor(() => expect(api.callsTo('GET /delivery/stats').at(-1)?.params).toEqual(presetRange('month')))

    await user.click(screen.getByRole('button', { name: 'Custom' }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-01-01' } })
    expect(await screen.findByRole('alert')).toHaveTextContent(/at most 92 days/)
  })

  it('folds the charts behind "More insights" on phones and small tablets (they always show from lg)', async () => {
    const user = userEvent.setup()
    renderPerformance()

    const more = await screen.findByRole('button', { name: /More insights/ })
    expect(more).toHaveAttribute('aria-expanded', 'false')
    const charts = document.getElementById(more.getAttribute('aria-controls') ?? '')
    expect(charts).toHaveClass('max-lg:hidden')
    expect(within(charts).getByRole('region', { name: 'Deliveries by day' })).toBeInTheDocument()

    await user.click(more)
    expect(more).toHaveAttribute('aria-expanded', 'true')
    expect(charts).not.toHaveClass('max-lg:hidden')
    // The headline numbers stay above, folded or not.
    expect(screen.getByLabelText('Headline numbers')).toHaveTextContent('₹169.60')
  })

  it('says so when nothing happened in the period', async () => {
    signInAs(['DELIVERY_PARTNER'])
    mockApi({ 'GET /delivery/stats': { status: 200, data: partnerStats() } })
    renderApp('/delivery', [{ path: '/delivery', element: <PartnerPerformance /> }])
    expect(await screen.findByText('No activity in this period yet')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Deliveries by day' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /More insights/ })).not.toBeInTheDocument()
  })
})

describe('Performance periods', () => {
  it('starts this week on Monday and this month on the 1st (India days)', () => {
    expect(presetRange('today', '2026-11-15')).toEqual({ from: '2026-11-15', to: '2026-11-15' })
    expect(presetRange('week', '2026-11-15')).toEqual({ from: '2026-11-09', to: '2026-11-15' }) // a Sunday
    expect(presetRange('week', '2026-11-09')).toEqual({ from: '2026-11-09', to: '2026-11-09' }) // a Monday
    expect(presetRange('month', '2026-11-15')).toEqual({ from: '2026-11-01', to: '2026-11-15' })
  })

  it('checks custom ranges and writes minutes as hours', () => {
    expect(customRangeError('2026-11-01', '2026-11-15')).toBeNull()
    expect(customRangeError('2026-11-15', '2026-11-01')).toMatch(/on or before/)
    expect(customRangeError('', '2026-11-01')).toMatch(/start date and an end date/)
    expect(customRangeError('2026-01-01', '2026-11-15')).toMatch(/at most 92 days/)
    expect(formatMinutes(0)).toBe('0 min')
    expect(formatMinutes(0.2)).toBe('< 1 min')
    expect(formatMinutes(45)).toBe('45 min')
    expect(formatMinutes(120)).toBe('2 h')
    expect(formatMinutes(305)).toBe('5 h 5 min')
    expect(formatMinutes(null)).toBe('—')
  })
})
