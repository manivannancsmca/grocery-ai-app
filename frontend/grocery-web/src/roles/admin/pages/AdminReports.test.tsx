import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { indiaDay, shiftDay } from '../../../shared/utils/indiaDate'
import { type MockHandler, mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const overview = {
  grossSales: 125000,
  netSales: 121500.5,
  orders: 412,
  averageOrderValue: 303.4,
  cancellationRate: 0.06,
  refunds: 3499.5,
  // 250.00 of the total comes from orders placed before the funding was recorded: in neither part
  couponDiscounts: { platform: 2100, store: 650.25, total: 3000.25, items: 2600.25, deliveryFee: 400 },
  newCustomers: 37,
}

function daily(from: string, to: string) {
  const rows = []
  for (let day = from; day <= to; day = shiftDay(day, 1)) rows.push({ date: day, orders: 10, gmv: 3000, discounts: 50, refunds: 0, net: 3000 })
  if (rows[0]) rows[0] = { ...rows[0], orders: 25, gmv: 9876, refunds: 120, net: 9756 }
  return rows
}

/** A report downloaded as a file (`?format=csv|xlsx|pdf`), as the server names it. */
function file(name: string, format: unknown, rows?: number) {
  const headers: Record<string, string> = { 'content-disposition': `attachment; filename="${name}.${String(format)}"` }
  if (rows !== undefined) headers['x-export-rows'] = String(rows)
  return { status: 200, data: '', headers }
}

function setup(routes: Record<string, MockHandler> = {}) {
  signInAs(['FINANCE_ADMIN'], { id: 'admin-1' })
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /admin/reports/overview': (request) => (request.params.format ? file('report-overview-2026-11-16-0910', request.params.format, 17) : { status: 200, data: overview }),
    'GET /admin/reports/sales/daily': (request) =>
      request.params.format ? file('report-daily-sales-2026-11-16-0910', request.params.format, 10) : { status: 200, data: daily(String(request.params.from), String(request.params.to)) },
    'GET /admin/reports/stores/top': (request) => {
      if (request.params.format) return file('stores-top_2026-11-01_2026-11-10', request.params.format)
      return {
        status: 200,
        data:
          request.params.metric === 'orders'
            ? [{ storeId: 'r1', storeName: 'Spice Route', storeType: 'RESTAURANT', orders: 150, gmv: 40000 }]
            : [
                { storeId: 'v1', storeName: 'Fresh Mart', storeType: 'GROCERY', orders: 120, gmv: 64000 },
                { storeId: 'r1', storeName: 'Spice Route', storeType: 'RESTAURANT', orders: 150, gmv: 40000 },
              ],
      }
    },
    'GET /admin/reports/products/top': { status: 200, data: [{ itemId: 'p1', name: 'Tomato 500 g', storeName: 'Fresh Mart', units: 820, revenue: 40180 }] },
    'GET /admin/reports/delivery/performance': {
      status: 200,
      data: [{ date: '2026-11-12', deliveries: 61, avgConfirmToAssignedMinutes: 2.4, avgAssignedToPickedUpMinutes: 11, avgPickedUpToDeliveredMinutes: 18.6, failed: 2 }],
    },
    'GET /admin/reports/promotions': {
      status: 200,
      data: [
        { couponCode: 'WELCOME10', couponName: 'Welcome offer', orders: 42, discountGiven: 3150.5, gmv: 18200, fundedBy: 'PLATFORM' },
        { couponCode: 'FRESH20', couponName: 'Fresh Mart weekend', orders: 9, discountGiven: 540, gmv: 4100, fundedBy: 'STORE' },
        // only orders placed before the coupon's name and funding were recorded
        { couponCode: 'OLDDEAL', couponName: null, orders: 3, discountGiven: 120, gmv: 900, fundedBy: null },
      ],
    },
    'GET /admin/reports/ratings': {
      status: 200,
      data: {
        lowest: [{ targetType: 'RESTAURANT', targetId: 'r9', targetName: 'Slow Kitchen', averageRating: 2.1, ratingCount: 14 }],
        highest: [{ targetType: 'PRODUCT', targetId: 'p1', targetName: 'Tomato 500 g', averageRating: 4.8, ratingCount: 120 }],
      },
    },
    'GET /admin/reports/purchasing': { status: 200, data: [{ vendorId: 'v1', vendorName: 'Fresh Mart', date: '2026-11-12', units: 160, cost: 3650 }] },
    ...routes,
  })
}

function tile(summary: HTMLElement, label: string): HTMLElement {
  return within(summary).getByText(label).closest('div') as HTMLElement
}

describe('Admin reports', { timeout: 30_000 }, () => {
  it('shows the KPIs, the daily chart (with a table), top stores and products, deliveries, promotions, ratings and purchasing', async () => {
    const user = userEvent.setup()
    const api = setup()
    const { router } = renderApp('/admin/reports')
    const today = indiaDay()
    const from = shiftDay(today, -29)

    const kpis = await screen.findByLabelText('Key figures')
    expect(api.callsTo('GET /admin/reports/overview')[0]?.params).toEqual({ from, to: today })
    expect(tile(kpis, 'Gross sales (GMV)')).toHaveTextContent('₹1,25,000')
    expect(tile(kpis, 'Net sales')).toHaveTextContent('₹1,21,500.50')
    expect(tile(kpis, 'Orders')).toHaveTextContent('412')
    expect(tile(kpis, 'Average order value')).toHaveTextContent('₹303.40')
    expect(tile(kpis, 'Cancellation rate')).toHaveTextContent('6%')
    expect(tile(kpis, 'Refunds')).toHaveTextContent('₹3,499.50')
    expect(tile(kpis, 'Coupon discounts')).toHaveTextContent('₹3,000.25')
    expect(tile(kpis, 'Coupon discounts')).toHaveTextContent('Platform ₹2,100 · stores ₹650.25 · not recorded ₹250')
    expect(tile(kpis, 'New customers')).toHaveTextContent('37')
    expect(screen.getByRole('button', { name: 'Last 30 days' })).toHaveAttribute('aria-pressed', 'true')

    // Daily chart: bars (a picture) and the same numbers as a table.
    expect(await screen.findByRole('figure', { name: /Gross sales \(GMV\)/ })).toHaveTextContent('Highest day ₹9,876')
    expect(screen.getByRole('figure', { name: /^Orders/ })).toBeInTheDocument()
    const dailyTable = screen.getByRole('table', { name: 'Daily sales' })
    expect(within(dailyTable).getAllByRole('row')).toHaveLength(1 + 30)
    expect(within(dailyTable).getAllByRole('row')[1]).toHaveTextContent('₹9,876')
    await user.click(screen.getByRole('button', { name: 'Show as table' }))
    expect(screen.getByRole('button', { name: 'Hide table' })).toHaveAttribute('aria-expanded', 'true')

    const stores = await screen.findByRole('table', { name: 'Top stores' })
    expect(within(stores).getAllByRole('row')[1]).toHaveTextContent('Fresh Mart')
    expect(within(stores).getAllByRole('row')[1]).toHaveTextContent('₹64,000')
    expect(api.callsTo('GET /admin/reports/stores/top')[0]?.params).toEqual({ from, to: today, metric: 'gmv', limit: 10 })
    await user.selectOptions(screen.getByRole('combobox', { name: 'Rank by' }), 'Orders')
    await waitFor(() => expect(api.callsTo('GET /admin/reports/stores/top').at(-1)?.params).toMatchObject({ metric: 'orders' }))
    expect(new URLSearchParams(router.state.location.search).get('metric')).toBe('orders')

    expect(await screen.findByRole('table', { name: 'Top products and dishes' })).toHaveTextContent('Tomato 500 g')
    const delivery = await screen.findByRole('table', { name: 'Delivery performance' })
    expect(delivery).toHaveTextContent('61')
    expect(delivery).toHaveTextContent('18.6 min')
    expect(screen.getByRole('figure', { name: /^Deliveries/ })).toBeInTheDocument()
    const promotions = await screen.findByRole('table', { name: 'Promotions' })
    const coupon = (code: string) => within(promotions).getByText(code).closest('tr') as HTMLElement
    expect(coupon('WELCOME10')).toHaveTextContent('Welcome offer')
    expect(coupon('WELCOME10')).toHaveTextContent('Platform')
    expect(coupon('FRESH20')).toHaveTextContent('Fresh Mart weekend')
    expect(coupon('FRESH20')).toHaveTextContent('Store')
    expect(coupon('OLDDEAL')).toHaveTextContent('Not recorded')        // unknown, never shown as the platform's
    expect(coupon('OLDDEAL')).not.toHaveTextContent('Platform')
    expect(await screen.findByRole('table', { name: 'Lowest rated' })).toHaveTextContent('Slow Kitchen')
    expect(screen.getByRole('table', { name: 'Highest rated' })).toHaveTextContent('4.80★')
    expect(await screen.findByRole('table', { name: 'Purchasing' })).toHaveTextContent('₹3,650')

    // Daily-sales filters reach the request.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Store type' }), 'Restaurants')
    await waitFor(() => expect(api.callsTo('GET /admin/reports/sales/daily').at(-1)?.params).toEqual({ from, to: today, type: 'RESTAURANT' }))
  })

  it('shows the coupon discounts without a funding split when the server has none', async () => {
    setup({ 'GET /admin/reports/overview': { status: 200, data: { ...overview, couponDiscounts: { platform: null, store: null, total: 69 } } } })
    renderApp('/admin/reports')

    const kpis = await screen.findByLabelText('Key figures')
    expect(tile(kpis, 'Coupon discounts')).toHaveTextContent('₹69')
    expect(tile(kpis, 'Coupon discounts')).toHaveTextContent('Funding not recorded')
    expect(tile(kpis, 'Coupon discounts')).not.toHaveTextContent('Platform')
  })

  it('exports a section as PDF, Excel or CSV for its period and its own filters, through the signed-in client', async () => {
    const user = userEvent.setup()
    const original = { createObjectURL: URL.createObjectURL, revokeObjectURL: URL.revokeObjectURL }
    URL.createObjectURL = vi.fn(() => 'blob:report')
    URL.revokeObjectURL = vi.fn()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const api = setup()
    renderApp('/admin/reports?from=2026-11-01&to=2026-11-10')
    await screen.findByLabelText('Key figures')

    // The overview as PDF: every format is offered, the menu says which period the file covers.
    await user.click(screen.getByRole('button', { name: 'Export overview' }))
    const menu = screen.getByRole('menu')
    expect(within(menu).getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      expect.stringContaining('CSV (.csv)'),
      expect.stringContaining('Excel (.xlsx)'),
      expect.stringContaining('PDF (.pdf)'),
    ])
    expect(within(menu).getByRole('menuitem', { name: 'PDF (.pdf)' })).toBeEnabled()
    expect(menu).toHaveTextContent(/This section for Sun, 1 Nov,? 2026 to Tue, 10 Nov,? 2026, with its filters\./)
    await user.click(within(menu).getByRole('menuitem', { name: 'PDF (.pdf)' }))
    expect(await screen.findByText('report-overview-2026-11-16-0910.pdf · 17 rows')).toBeInTheDocument()
    const pdf = api.callsTo('GET /admin/reports/overview').find((call) => call.params.format === 'pdf')
    expect(pdf?.params).toEqual({ from: '2026-11-01', to: '2026-11-10', format: 'pdf' })
    expect(pdf?.headers.get('Authorization')).toBe('Bearer test-access-token')
    expect(click).toHaveBeenCalledOnce()

    // Daily sales as Excel, with the section's own filter.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Store type' }), 'Restaurants')
    await user.click(screen.getByRole('button', { name: 'Export daily sales' }))
    await user.click(screen.getByRole('menuitem', { name: 'Excel (.xlsx)' }))
    expect(await screen.findByText('report-daily-sales-2026-11-16-0910.xlsx · 10 rows')).toBeInTheDocument()
    expect(api.callsTo('GET /admin/reports/sales/daily').find((call) => call.params.format === 'xlsx')?.params).toEqual({
      from: '2026-11-01',
      to: '2026-11-10',
      type: 'RESTAURANT',
      format: 'xlsx',
    })

    // Top stores as CSV (the report's own columns; no row count from the server).
    await user.click(screen.getByRole('button', { name: 'Export top stores' }))
    await user.click(screen.getByRole('menuitem', { name: 'CSV (.csv)' }))
    expect(await screen.findByText('stores-top_2026-11-01_2026-11-10.csv')).toBeInTheDocument()
    expect(api.callsTo('GET /admin/reports/stores/top').find((call) => call.params.format === 'csv')?.params).toEqual({
      from: '2026-11-01',
      to: '2026-11-10',
      metric: 'gmv',
      limit: 10,
      format: 'csv',
    })
    click.mockRestore()
    Object.assign(URL, original)
  })

  it('validates the range (start before end, at most 366 days) before asking the server', async () => {
    const api = setup()
    renderApp('/admin/reports')
    await screen.findByLabelText('Key figures')
    const today = indiaDay()

    fireEvent.change(screen.getByLabelText('From'), { target: { value: shiftDay(today, -366) } })
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose at most 366 days — this range has 367.')
    expect(screen.queryByLabelText('Key figures')).not.toBeInTheDocument()
    expect(api.callsTo('GET /admin/reports/overview').some((call) => call.params.from === shiftDay(today, -366))).toBe(false)

    fireEvent.change(screen.getByLabelText('From'), { target: { value: shiftDay(today, 1) } })
    expect(await screen.findByRole('alert')).toHaveTextContent('The start date must be on or before the end date.')

    fireEvent.change(screen.getByLabelText('From'), { target: { value: shiftDay(today, -365) } })
    await waitFor(() => expect(api.callsTo('GET /admin/reports/overview').at(-1)?.params).toEqual({ from: shiftDay(today, -365), to: today }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('a section that fails shows its own error; the others still load', async () => {
    setup({ 'GET /admin/reports/promotions': problem(503, 'SERVICE_UNAVAILABLE', 'Reporting is catching up.') })
    renderApp('/admin/reports')
    expect(await screen.findByText('Could not load promotions')).toBeInTheDocument()
    expect(screen.getByText('Reporting is catching up.')).toBeInTheDocument()
    expect(await screen.findByLabelText('Key figures')).toBeInTheDocument()
  })
})
