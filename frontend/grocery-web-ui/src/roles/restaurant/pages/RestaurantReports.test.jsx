import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { indiaDay, shiftDay } from '../../../shared/utils/indiaDate'
import { mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { salesReport } from '../../../test/storeOrderFixtures'

function setup(routes = {}) {
  signInAs(['RESTAURANT_OWNER'], { restaurantId: 'r1' })
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /restaurant/reports/sales': (request) => ({
      status: 200,
      data: salesReport(String(request.params.from), String(request.params.to)),
    }),
    ...routes,
  })
}

function tile(summary, label) {
  return within(summary).getByText(label).closest('div')
}

describe('Restaurant sales reports', { timeout: 20_000 }, () => {
  it('shows the last 7 days by default: KPI tiles in rupees, daily bars with a table, and the top items', async () => {
    const user = userEvent.setup()
    const api = setup()
    renderApp('/restaurant/reports')

    const today = indiaDay()
    const summary = await screen.findByLabelText('Sales summary')
    expect(api.callsTo('GET /restaurant/reports/sales')[0]?.params).toEqual({ from: shiftDay(today, -6), to: today })
    expect(screen.getByLabelText('From')).toHaveValue(shiftDay(today, -6))
    expect(screen.getByLabelText('To')).toHaveValue(today)
    expect(screen.getByRole('button', { name: 'Last 7 days' })).toHaveAttribute('aria-pressed', 'true')

    expect(tile(summary, 'Orders received')).toHaveTextContent('12')
    expect(tile(summary, 'Orders accepted')).toHaveTextContent('9')
    expect(tile(summary, 'Acceptance rate')).toHaveTextContent('75%')
    expect(tile(summary, 'Rejected')).toHaveTextContent('2')
    expect(tile(summary, 'Rejected')).toHaveTextContent('1 automatically')
    expect(tile(summary, 'Cancelled')).toHaveTextContent('1')
    expect(tile(summary, 'Gross sales')).toHaveTextContent('₹2,340')
    expect(tile(summary, 'Average order value')).toHaveTextContent('₹292.50')
    expect(tile(summary, 'Items sold')).toHaveTextContent('31')

    // The bars are a picture; the same numbers are a table (always there for screen readers, shown on request).
    const table = screen.getByRole('table', { name: 'Daily sales and orders' })
    expect(within(table).getAllByRole('row')).toHaveLength(1 + 7)
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('₹480')
    expect(screen.getByRole('figure', { name: /Gross sales/ })).toBeInTheDocument()
    expect(screen.getByRole('figure', { name: /Orders received/ })).toBeInTheDocument()
    const toggle = screen.getByRole('button', { name: 'Show as table' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(screen.getByRole('button', { name: 'Hide table' })).toHaveAttribute('aria-expanded', 'true')

    const top = screen.getByRole('table', { name: 'Top items' })
    expect(within(top).getByText('Ghee Roast Dosa').closest('tr')).toHaveTextContent('8')
    // Indian digit grouping.
    expect(within(top).getByText('Family Thali Pack').closest('tr')).toHaveTextContent('₹1,25,000')
  })

  it('validates the range (start before end, at most 92 days) before asking the server', async () => {
    const api = setup()
    const { router } = renderApp('/restaurant/reports')
    await screen.findByLabelText('Sales summary')
    const today = indiaDay()

    fireEvent.change(screen.getByLabelText('From'), { target: { value: shiftDay(today, -99) } })
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose at most 92 days — this range has 100.')
    expect(screen.queryByLabelText('Sales summary')).not.toBeInTheDocument()
    expect(api.callsTo('GET /restaurant/reports/sales').some((call) => call.params.from === shiftDay(today, -99))).toBe(
      false,
    )
    expect(new URLSearchParams(router.state.location.search).get('from')).toBe(shiftDay(today, -99))

    fireEvent.change(screen.getByLabelText('From'), { target: { value: shiftDay(today, 1) } })
    expect(await screen.findByRole('alert')).toHaveTextContent('The start date must be on or before the end date.')

    // 92 days is fine.
    fireEvent.change(screen.getByLabelText('From'), { target: { value: shiftDay(today, -91) } })
    await waitFor(() =>
      expect(api.callsTo('GET /restaurant/reports/sales').at(-1)?.params).toEqual({
        from: shiftDay(today, -91),
        to: today,
      }),
    )
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('explains a range the server refuses (400 INVALID_PARAMETER)', async () => {
    setup({
      'GET /restaurant/reports/sales': problem(400, 'INVALID_PARAMETER', 'The range may cover at most 92 days.'),
    })
    renderApp('/restaurant/reports?from=2026-11-01&to=2026-11-11')

    expect(await screen.findByText('This range cannot be shown')).toBeInTheDocument()
    expect(screen.getByText('The range may cover at most 92 days.')).toBeInTheDocument()
  })
})
