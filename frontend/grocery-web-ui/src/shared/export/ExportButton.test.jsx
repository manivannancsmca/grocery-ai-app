import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockApi, problem } from '../../test/mockApi'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import { ExportButton } from './ExportButton'

const CSV_HEADERS = {
  'content-disposition': 'attachment; filename="orders-2026-11-15-1030.csv"',
  'x-export-rows': '1234',
}

function Host({ total, sort = null }) {
  const [filters] = useState({ status: 'DELIVERED', q: '', storeId: undefined })
  return (
    <ExportButton
      path="/admin/orders/export"
      label="orders"
      params={{ status: filters.status, q: filters.q || undefined, storeId: filters.storeId }}
      total={total}
      sort={sort}
      sortable={['order', 'total']}
    />
  )
}

function render(element) {
  signInAs(['SUPER_ADMIN'])
  return renderApp('/admin/orders', [{ path: '/admin/orders', element }])
}

describe('ExportButton', () => {
  let clicked

  beforeEach(() => {
    clicked = []
    // jsdom has no object URLs and no real downloads: record what would be saved.
    URL.createObjectURL = vi.fn(() => 'blob:export')
    URL.revokeObjectURL = vi.fn()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () {
      clicked.push(this.download)
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('downloads every matching row in the chosen format, with the list filters and the table sort', async () => {
    const user = userEvent.setup()
    const api = mockApi({ 'GET /admin/orders/export': { status: 200, data: 'Order number\r\n', headers: CSV_HEADERS } })
    render(<Host total={1234} sort={{ key: 'total', direction: 'descending' }} />)

    await user.click(screen.getByRole('button', { name: 'Export orders' }))
    const menu = screen.getByRole('menu', { name: 'Export orders' })
    expect(menu).toHaveTextContent('All 1,234 rows that match your filters, not just this page.')
    await user.click(within(menu).getByRole('menuitem', { name: 'CSV (.csv)' }))

    expect(await screen.findByText('Orders exported')).toBeInTheDocument()
    expect(screen.getByText('orders-2026-11-15-1030.csv · 1,234 rows')).toBeInTheDocument()
    expect(clicked).toEqual(['orders-2026-11-15-1030.csv'])
    expect(api.callsTo('GET /admin/orders/export')[0]?.params).toEqual({
      status: 'DELIVERED',
      sort: 'total,desc',
      format: 'csv',
    })
  })

  it('keeps the list order when the table is sorted by a column the server cannot sort by', async () => {
    const user = userEvent.setup()
    const api = mockApi({ 'GET /admin/orders/export': { status: 200, data: '', headers: CSV_HEADERS } })
    render(<Host total={3} sort={{ key: 'customer', direction: 'ascending' }} />)

    await user.click(screen.getByRole('button', { name: 'Export orders' }))
    expect(screen.getByRole('menu')).toHaveTextContent('The file keeps the list’s own order.')
    await user.click(screen.getByRole('menuitem', { name: 'Excel (.xlsx)' }))

    await screen.findByText('Orders exported')
    expect(api.callsTo('GET /admin/orders/export')[0]?.params).toEqual({ status: 'DELIVERED', format: 'xlsx' })
  })

  it('offers a PDF only up to its row limit', async () => {
    const user = userEvent.setup()
    mockApi({})
    render(<Host total={12_000} />)

    await user.click(screen.getByRole('button', { name: 'Export orders' }))
    const pdf = screen.getByRole('menuitem', { name: 'PDF (.pdf)' })
    expect(pdf).toHaveAttribute('aria-disabled', 'true')
    expect(pdf).toHaveAccessibleDescription('Up to 5,000 rows: choose CSV or Excel')
  })

  it('shows the server’s reason when an export is refused', async () => {
    const user = userEvent.setup()
    const refusal = problem(
      422,
      'EXPORT_TOO_LARGE',
      'This export has 1,200,000 rows; the limit is 1,000,000. Narrow the filters.',
    )
    // A blob request gets its error body as a Blob, like the browser does.
    mockApi({ 'GET /admin/orders/export': { status: 422, data: new Blob([JSON.stringify(refusal.data)]) } })
    render(<Host />)

    await user.click(screen.getByRole('button', { name: 'Export orders' }))
    await user.click(screen.getByRole('menuitem', { name: 'CSV (.csv)' }))

    expect(await screen.findByText('Could not export orders')).toBeInTheDocument()
    expect(screen.getByText(/the limit is 1,000,000\. Narrow the filters\./)).toBeInTheDocument()
    expect(clicked).toEqual([])
  })

  it('shows a running export with its progress, and cancels it', async () => {
    const user = userEvent.setup()
    mockApi({ 'GET /admin/orders/export': { status: 200, data: '', headers: CSV_HEADERS, delayMs: 300 } })
    render(<Host total={40} />)

    await user.click(screen.getByRole('button', { name: 'Export orders' }))
    await user.click(screen.getByRole('menuitem', { name: 'PDF (.pdf)' }))

    const tray = await screen.findByRole('list', { name: 'Exports in progress' })
    expect(tray).toHaveTextContent('Exporting orders · PDF')
    expect(tray).toHaveTextContent('40 rows')
    expect(screen.getByRole('button', { name: 'Exporting orders…' })).toBeDisabled()
    await user.click(within(tray).getByRole('button', { name: 'Cancel exporting orders' }))

    expect(await screen.findByText('Export of orders cancelled')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('list', { name: 'Exports in progress' })).not.toBeInTheDocument())
    expect(clicked).toEqual([])
    expect(screen.getByRole('button', { name: 'Export orders' })).toBeEnabled()
  })
})
