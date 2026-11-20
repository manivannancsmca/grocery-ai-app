import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockApi } from '../../../test/mockApi'
import { adminOrderSummary, pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

describe('Admin orders export', () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => 'blob:export')
    URL.revokeObjectURL = vi.fn()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('exports every order matching the URL filters, in the order the table is sorted by', async () => {
    const user = userEvent.setup()
    signInAs(['FINANCE_ADMIN'])
    const api = mockApi({
      'GET /admin/orders/export': {
        status: 200,
        data: '',
        headers: { 'content-disposition': 'attachment; filename="orders-2026-11-15-1030.xlsx"', 'x-export-rows': '57' },
      },
      'GET /admin/orders': pageOf([adminOrderSummary()], { totalElements: 57, totalPages: 3 }),
    })
    renderApp('/admin/orders?status=CONFIRMED&type=GROCERY&from=2026-11-01&to=2026-11-15&page=2')

    const table = await screen.findByRole('table', { name: 'Orders' })
    await user.click(within(table).getByRole('button', { name: /Total/ }))
    await user.click(within(table).getByRole('button', { name: /Total/ }))
    await user.click(screen.getByRole('button', { name: 'Export orders' }))
    expect(screen.getByRole('menu')).toHaveTextContent('All 57 rows that match your filters, not just this page.')
    await user.click(screen.getByRole('menuitem', { name: 'Excel (.xlsx)' }))

    expect(await screen.findByText('orders-2026-11-15-1030.xlsx · 57 rows')).toBeInTheDocument()
    // the filters of the list (never its page), and the column order the table shows
    expect(api.callsTo('GET /admin/orders/export')[0]?.params).toEqual({
      status: 'CONFIRMED',
      type: 'GROCERY',
      from: '2026-11-01',
      to: '2026-11-15',
      sort: 'total,desc',
      format: 'xlsx',
    })
  })
})
