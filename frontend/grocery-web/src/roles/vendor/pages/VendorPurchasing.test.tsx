import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { PurchaseOrderRequest, PurchaseOrderView, ReceiveRequest, SupplierRequest } from '../../../features/purchasing/api'
import { type MockHandler, type MockReply, type MockRequest, mockApi, problem } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { purchaseOrder, supplier } from '../../../test/storeAdminFixtures'

const products = [
  { id: 'p1', name: 'Tomato 500 g', unitLabel: '500 g', sku: 'TOM-500', status: 'PUBLISHED' },
  { id: 'p2', name: 'Onion 1 kg', unitLabel: '1 kg', sku: null, status: 'PUBLISHED' },
]

function vendorApi(routes: Record<string, MockHandler> = {}) {
  signInAs(['VENDOR'], { vendorId: 'v1' })
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /vendor/suppliers': { status: 200, data: [supplier()] },
    'GET /vendor/products': (request) =>
      pageOf(products.filter((product) => !request.params.q || product.name.toLowerCase().includes(String(request.params.q).toLowerCase()))),
    ...routes,
  })
}

/** A purchase-order server: send, receive (by line), close, cancel. */
function orderServer(initial: PurchaseOrderView) {
  const server = { order: initial }
  const routes: Record<string, MockHandler> = {
    'GET /vendor/purchase-orders/:id': () => ({ status: 200, data: server.order }),
    'POST /vendor/purchase-orders/:id/send': () => {
      server.order = { ...server.order, status: 'SENT', sentAt: '2026-11-13T10:00:00Z' }
      return { status: 200, data: server.order }
    },
    'POST /vendor/purchase-orders/:id/close': () => {
      server.order = { ...server.order, status: 'CLOSED' }
      return { status: 200, data: server.order }
    },
    'POST /vendor/purchase-orders/:id/receive': (request) => receive(request),
  }
  function receive(request: MockRequest): MockReply {
      const body = request.body as ReceiveRequest
      const lines = server.order.lines.map((line) => {
        const received = body.lines.find((item) => item.productId === line.productId)?.quantity ?? 0
        return { ...line, receivedQuantity: line.receivedQuantity + received }
      })
      const complete = lines.every((line) => line.receivedQuantity >= line.orderedQuantity)
      server.order = {
        ...server.order,
        lines,
        status: complete ? 'RECEIVED' : 'PARTIALLY_RECEIVED',
        receipts: [...(server.order.receipts ?? []), { id: `rc${(server.order.receipts ?? []).length + 1}`, receivedAt: '2026-11-13T11:00:00Z', note: body.note ?? null, lines: body.lines }],
      }
      return { status: 200, data: server.order }
  }
  return { server, routes, receive }
}

describe('Vendor purchasing — suppliers', { timeout: 30_000 }, () => {
  it('adds a supplier (validated), and explains why one with open orders cannot be deactivated', async () => {
    const user = userEvent.setup()
    const api = vendorApi({
      'POST /vendor/suppliers': (request) => ({ status: 201, data: supplier({ ...(request.body as SupplierRequest), id: 'sup2' }) }),
      'DELETE /vendor/suppliers/:id': problem(409, 'SUPPLIER_IN_USE', 'Supplier has open purchase orders.'),
    })
    renderApp('/vendor/suppliers')

    const table = await screen.findByRole('table', { name: 'Suppliers' })
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Green Valley Farms')
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('2 days')
    expect(within(table).getByRole('link', { name: 'New order' })).toHaveAttribute('href', '/vendor/purchase-orders/new?supplierId=sup1')

    await user.click(screen.getByRole('button', { name: 'Add supplier' }))
    const dialog = await screen.findByRole('dialog', { name: 'New supplier' })
    await user.type(within(dialog).getByLabelText('GSTIN'), 'NOTAGSTIN')
    await user.type(within(dialog).getByLabelText('Lead time (days)'), '120')
    await user.click(within(dialog).getByRole('button', { name: 'Add supplier' }))
    expect(await within(dialog).findByText('Enter the supplier’s name.')).toBeInTheDocument()
    expect(within(dialog).getByText(/Enter a valid 15-character GSTIN/)).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a value at least 0 and at most 90.')).toBeInTheDocument()
    expect(api.callsTo('POST /vendor/suppliers')).toHaveLength(0)

    await user.type(within(dialog).getByLabelText('Name', { exact: false }), 'Hosur Wholesale')
    await user.clear(within(dialog).getByLabelText('GSTIN'))
    await user.type(within(dialog).getByLabelText('GSTIN'), '33abcde1234f1z5')
    await user.clear(within(dialog).getByLabelText('Lead time (days)'))
    await user.type(within(dialog).getByLabelText('Lead time (days)'), '3')
    await user.type(within(dialog).getByLabelText('Phone'), '98765 43210')
    await user.click(within(dialog).getByRole('button', { name: 'Add supplier' }))
    await waitFor(() => expect(api.callsTo('POST /vendor/suppliers')).toHaveLength(1))
    expect(api.callsTo('POST /vendor/suppliers')[0]?.body).toEqual({
      name: 'Hosur Wholesale',
      contactName: null,
      phone: '9876543210',
      email: null,
      gstin: '33ABCDE1234F1Z5',
      address: null,
      leadTimeDays: 3,
      notes: null,
    })

    await user.click(within(screen.getByRole('table', { name: 'Suppliers' })).getByRole('button', { name: 'Deactivate Green Valley Farms' }))
    const confirm = await screen.findByRole('dialog', { name: 'Deactivate Green Valley Farms?' })
    await user.click(within(confirm).getByRole('button', { name: 'Deactivate' }))
    expect(await within(confirm).findByRole('alert')).toHaveTextContent('Green Valley Farms still has open purchase orders. Receive, close or cancel them first.')
  })
})

describe('Vendor purchasing — purchase orders', { timeout: 30_000 }, () => {
  it('creates a draft for a supplier from the vendor’s own products', async () => {
    const user = userEvent.setup()
    const draft = purchaseOrder({ id: 'po9', number: 'PO-NEW00009', status: 'DRAFT', sentAt: null })
    const { routes } = orderServer(draft)
    const api = vendorApi({
      ...routes,
      'POST /vendor/purchase-orders': (request) => {
        const body = request.body as PurchaseOrderRequest
        return { status: 201, data: { ...draft, expectedAt: body.expectedAt, lines: body.lines.map((line) => ({ ...line, productName: products.find((p) => p.id === line.productId)?.name ?? '', receivedQuantity: 0 })) } }
      },
    })
    const { router } = renderApp('/vendor/purchase-orders/new?supplierId=sup1')

    expect(await screen.findByRole('heading', { level: 1, name: 'New purchase order' })).toBeInTheDocument()
    expect(await screen.findByRole('combobox', { name: /Supplier/ })).toHaveValue('sup1')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    expect(await screen.findByText('Add at least one product.')).toBeInTheDocument()
    expect(api.callsTo('POST /vendor/purchase-orders')).toHaveLength(0)

    await user.type(screen.getByRole('searchbox', { name: 'Add a product' }), 'toma')
    const results = await screen.findByRole('list', { name: 'Matching products' })
    await waitFor(() => expect(within(results).getAllByRole('listitem')).toHaveLength(1))
    await user.click(within(results).getByRole('button', { name: 'Add Tomato 500 g' }))
    expect(within(results).getByRole('button', { name: 'Add Tomato 500 g' })).toBeDisabled()

    const quantity = screen.getByRole('textbox', { name: 'Quantity of Tomato 500 g' })
    await user.clear(quantity)
    await user.type(quantity, '0')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    expect(await screen.findByText('Enter a value at least 1 and at most 1,00,000.')).toBeInTheDocument()
    expect(screen.getByText('Enter the unit cost.')).toBeInTheDocument()

    await user.clear(quantity)
    await user.type(quantity, '12')
    await user.type(screen.getByRole('textbox', { name: 'Unit cost of Tomato 500 g' }), '18.50')
    expect(screen.getByRole('table', { name: 'Order lines' })).toHaveTextContent('₹222.00')
    fireEvent.change(screen.getByLabelText('Expected on'), { target: { value: '2026-11-20' } })
    await user.click(screen.getByRole('button', { name: 'Save draft' }))

    await waitFor(() => expect(api.callsTo('POST /vendor/purchase-orders')).toHaveLength(1))
    expect(api.callsTo('POST /vendor/purchase-orders')[0]?.body).toEqual({
      supplierId: 'sup1',
      expectedAt: '2026-11-20',
      notes: null,
      lines: [{ productId: 'p1', orderedQuantity: 12, unitCost: 18.5 }],
    })
    await waitFor(() => expect(router.state.location.pathname).toBe('/vendor/purchase-orders/po9'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Purchase order PO-NEW00009' })).toBeInTheDocument()
  })

  it('sends a draft, receives part of it (with an Idempotency-Key), then refuses more than is open and closes it short', async () => {
    const user = userEvent.setup()
    const { routes, receive } = orderServer(purchaseOrder({ status: 'DRAFT', sentAt: null }))
    let refuse = false
    const api = vendorApi({
      ...routes,
      'POST /vendor/purchase-orders/:id/receive': async (request) => {
        if (refuse) return { ...problem(422, 'RECEIPT_EXCEEDS_ORDERED', 'More than ordered.'), data: { ...problem(422, 'RECEIPT_EXCEEDS_ORDERED').data, productId: 'p2' } }
        return receive(request)
      },
    })
    renderApp('/vendor/purchase-orders/po1')

    const actions = await screen.findByRole('group', { name: 'Order actions' })
    expect(within(actions).getByRole('link', { name: 'Edit' })).toHaveAttribute('href', '/vendor/purchase-orders/po1/edit')
    expect(within(actions).queryByRole('button', { name: 'Receive goods' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Printable view' })).toHaveAttribute('href', '/vendor/purchase-orders/po1/print')
    expect(screen.getByRole('table', { name: 'Purchase order lines' })).toHaveTextContent('₹365.00')

    // Send.
    await user.click(within(actions).getByRole('button', { name: 'Send to supplier' }))
    const send = await screen.findByRole('dialog', { name: 'Send this purchase order?' })
    await user.click(within(send).getByRole('button', { name: 'Send to supplier' }))
    await waitFor(() => expect(api.callsTo('POST /vendor/purchase-orders/:id/send')).toHaveLength(1))
    const sentActions = await screen.findByRole('group', { name: 'Order actions' })
    await waitFor(() => expect(within(sentActions).queryByRole('link', { name: 'Edit' })).not.toBeInTheDocument())

    // Receive part: 4 tomatoes (client validation first: not more than is open).
    await user.click(within(sentActions).getByRole('button', { name: 'Receive goods' }))
    let dialog = await screen.findByRole('dialog', { name: 'Receive goods — PO-7K3M9QX2' })
    await user.click(within(dialog).getByRole('button', { name: 'Record receipt' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Enter the quantity received for at least one product.')
    await user.type(within(dialog).getByLabelText('Tomato 500 g'), '11')
    await user.click(within(dialog).getByRole('button', { name: 'Record receipt' }))
    expect(await within(dialog).findByText('At most 10 — that is what is still open.')).toBeInTheDocument()
    await user.clear(within(dialog).getByLabelText('Tomato 500 g'))
    await user.type(within(dialog).getByLabelText('Tomato 500 g'), '4')
    await user.type(within(dialog).getByLabelText('Note (optional)'), 'First crates')
    await user.click(within(dialog).getByRole('button', { name: 'Record receipt' }))

    await waitFor(() => expect(api.callsTo('POST /vendor/purchase-orders/:id/receive')).toHaveLength(1))
    const first = api.callsTo('POST /vendor/purchase-orders/:id/receive')[0]
    expect(first?.body).toEqual({ lines: [{ productId: 'p1', quantity: 4 }], note: 'First crates' })
    const firstKey = first?.headers.get('Idempotency-Key')
    expect(typeof firstKey).toBe('string')
    expect(String(firstKey).length).toBeGreaterThan(8)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Receive goods — PO-7K3M9QX2' })).not.toBeInTheDocument())
    expect(await screen.findByText('Partially received')).toBeInTheDocument()
    const lines = screen.getByRole('table', { name: 'Purchase order lines' })
    expect(within(lines).getByRole('row', { name: /Tomato 500 g/ })).toHaveTextContent(/10\s*4\s*6/)
    expect(screen.getByRole('list', { name: 'Goods receipts' })).toHaveTextContent('4 × Tomato 500 g')

    // The server refuses (received elsewhere meanwhile): the dialog explains, the order is re-read.
    refuse = true
    const reads = api.callsTo('GET /vendor/purchase-orders/:id').length
    await user.click(within(screen.getByRole('group', { name: 'Order actions' })).getByRole('button', { name: 'Receive goods' }))
    dialog = await screen.findByRole('dialog', { name: 'Receive goods — PO-7K3M9QX2' })
    await user.click(within(dialog).getByRole('button', { name: 'Fill in everything open' }))
    expect(within(dialog).getByLabelText('Tomato 500 g')).toHaveValue('6')
    expect(within(dialog).getByLabelText('Onion 1 kg')).toHaveValue('6')
    await user.click(within(dialog).getByRole('button', { name: 'Record receipt' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Onion 1 kg: more than what is still open on this order')
    await waitFor(() => expect(api.callsTo('GET /vendor/purchase-orders/:id').length).toBeGreaterThan(reads))
    const second = api.callsTo('POST /vendor/purchase-orders/:id/receive')[1]
    expect(second?.headers.get('Idempotency-Key')).not.toBe(firstKey)
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))

    // Close short.
    await user.click(within(screen.getByRole('group', { name: 'Order actions' })).getByRole('button', { name: 'Close short' }))
    const close = await screen.findByRole('dialog', { name: 'Close this purchase order short?' })
    await user.click(within(close).getByRole('button', { name: 'Close order' }))
    await waitFor(() => expect(api.callsTo('POST /vendor/purchase-orders/:id/close')).toHaveLength(1))
    expect(await screen.findByText('Closed (short)')).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Order actions' })).not.toBeInTheDocument()
  })

  it('a cancel the server refuses (409 INVALID_STATUS_TRANSITION) names the current status', async () => {
    const user = userEvent.setup()
    const { routes } = orderServer(purchaseOrder())
    vendorApi({
      ...routes,
      'POST /vendor/purchase-orders/:id/cancel': { ...problem(409, 'INVALID_STATUS_TRANSITION'), data: { ...problem(409, 'INVALID_STATUS_TRANSITION').data, currentStatus: 'PARTIALLY_RECEIVED' } },
    })
    renderApp('/vendor/purchase-orders/po1')

    await user.click(within(await screen.findByRole('group', { name: 'Order actions' })).getByRole('button', { name: 'Cancel order' }))
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this purchase order?' })
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This purchase order is partially received now, so that is no longer possible.')
  })

  it('lists purchase orders by status and supplier, and prints one', async () => {
    const user = userEvent.setup()
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined)
    const { routes } = orderServer(purchaseOrder())
    const api = vendorApi({
      ...routes,
      'GET /vendor/purchase-orders': pageOf([purchaseOrder()]),
      'GET /vendors/me': { status: 200, data: { id: 'v1', status: 'APPROVED', businessName: 'Fresh Mart', gstin: '29AAAAA0000A1Z5', address: { line1: '1 Main Rd', city: 'Bengaluru', state: 'Karnataka', postalCode: '560001' } } },
    })
    const { router } = renderApp('/vendor/purchase-orders')

    const table = await screen.findByRole('table', { name: 'Purchase orders' })
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('PO-7K3M9QX2')
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Sent')
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('₹365.00')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'Partially received')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Supplier' }), 'Green Valley Farms')
    await waitFor(() =>
      expect(api.callsTo('GET /vendor/purchase-orders').at(-1)?.params).toEqual({ status: 'PARTIALLY_RECEIVED', supplierId: 'sup1', page: 0, size: 20 }),
    )

    await router.navigate('/vendor/purchase-orders/po1/print')
    const sheet = await screen.findByRole('article', { name: 'Purchase order PO-7K3M9QX2' })
    expect(within(sheet).getByRole('region', { name: 'Supplier' })).toHaveTextContent('Green Valley Farms')
    expect(within(sheet).getByRole('region', { name: 'Supplier' })).toHaveTextContent('GSTIN 29ABCDE1234F1Z5')
    expect(await within(sheet).findByText('Fresh Mart')).toBeInTheDocument()
    expect(within(sheet).getByRole('table', { name: 'Lines of PO-7K3M9QX2' })).toHaveTextContent('₹365.00')
    await user.click(screen.getByRole('button', { name: 'Print' }))
    expect(print).toHaveBeenCalledOnce()
    print.mockRestore()
  })
})
