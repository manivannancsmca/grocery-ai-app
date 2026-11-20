import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const STATS = {
  date: '2026-11-11',
  timezone: 'Asia/Kolkata',
  orders: 42,
  byStatus: { CREATED: 1, AWAITING_PAYMENT: 3, CONFIRMED: 30, REJECTED: 2, CANCELLED: 6 },
  byType: { GROCERY: 25, RESTAURANT: 17 },
  confirmedRevenue: 10250,
  averageOrderValue: 341.67,
  currency: 'INR',
}

function tile(stats, label) {
  return within(stats).getByText(label).closest('div')
}

describe('Admin dashboard — today’s orders', () => {
  it('shows today’s orders, confirmed revenue, average order value and counts by status', async () => {
    signInAs(['OPS_ADMIN'])
    const api = mockApi({ 'GET /admin/stats': { status: 200, data: STATS } })
    renderApp('/admin')

    const section = await screen.findByRole('region', { name: /^Today’s orders/ })
    const stats = await within(section).findByRole('list', { name: 'Today’s orders by status' })
    expect(tile(section, 'Orders placed')).toHaveTextContent('42')
    expect(tile(section, 'Orders placed')).toHaveTextContent('Grocery 25 · Restaurant 17')
    expect(tile(section, 'Confirmed revenue')).toHaveTextContent('₹10,250')
    expect(tile(section, 'Average order value')).toHaveTextContent('₹341.67')
    expect(tile(section, 'Open orders')).toHaveTextContent('4')
    expect(within(stats).getByText(/^Confirmed/)).toHaveTextContent('Confirmed 30')
    expect(within(stats).getByText(/^Cancelled/)).toHaveTextContent('Cancelled 6')
    expect(section).toHaveTextContent('India time')

    // Without `date` the server answers for today (India time).
    expect(api.callsTo('GET /admin/stats')[0]?.params).toEqual({})
    expect(within(section).getByRole('link', { name: 'Orders →' })).toHaveAttribute('href', '/admin/orders')
    expect(within(section).getByRole('link', { name: 'Payments →' })).toHaveAttribute('href', '/admin/payments')
    expect(screen.queryByText('Orders & payments')).not.toBeInTheDocument()
  })

  it('offers a retry when the stats cannot be loaded', async () => {
    const user = userEvent.setup()
    signInAs(['FINANCE_ADMIN'])
    let calls = 0
    mockApi({
      'GET /admin/stats': () => {
        calls++
        return calls === 1 ? problem(503, 'SERVICE_UNAVAILABLE', 'Order service is down') : { status: 200, data: STATS }
      },
    })
    renderApp('/admin')

    const section = await screen.findByRole('region', { name: /^Today’s orders/ })
    const alert = await within(section).findByRole('alert')
    expect(alert).toHaveTextContent('Could not load today’s order stats: Order service is down')
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))

    await waitFor(() => expect(tile(section, 'Orders placed')).toHaveTextContent('42'))
    expect(within(section).queryByRole('alert')).not.toBeInTheDocument()
  })
})
