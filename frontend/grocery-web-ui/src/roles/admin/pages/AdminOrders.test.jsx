import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { adminOrderSummary, adminOrderView, pageOf, paymentView, refundView } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const confirmedRow = adminOrderSummary({
  id: 'o2',
  orderNumber: 'GA-CONF0002',
  status: 'CONFIRMED',
  payment: { status: 'PAID' },
  customerId: 'cust-2',
  total: 1250,
})

/** A paid, confirmed order (₹125) with one admin refund of ₹25. */
function paidOrder(overrides = {}) {
  const base = adminOrderView()
  return adminOrderView({
    status: 'CONFIRMED',
    payment: { ...base.payment, status: 'PARTIALLY_REFUNDED', paidAt: '2026-11-11T10:03:00Z', refundedAmount: 25 },
    timeline: [...base.timeline, { status: 'CONFIRMED', at: '2026-11-11T10:03:00Z', reason: null }],
    ...overrides,
  })
}

function setup(roles, routes = {}) {
  signInAs(roles)
  const server = { order: paidOrder() }
  const api = mockApi({
    'GET /admin/orders': pageOf([
      adminOrderSummary({ status: 'CONFIRMED', payment: { status: 'PARTIALLY_REFUNDED' } }),
      confirmedRow,
    ]),
    'GET /admin/orders/:id': () => ({ status: 200, data: server.order }),
    'GET /payments/orders/:orderId': {
      status: 200,
      data: paymentView({ refundedAmount: 25, refundStatus: 'PARTIAL', refunds: [refundView()] }),
    },
    'POST /admin/orders/:id/cancel': (request) => {
      const { reason } = request.body
      server.order = paidOrder({
        status: 'CANCELLED',
        cancellable: false,
        payment: { ...server.order.payment, status: 'REFUND_PENDING' },
        cancellation: { reason: 'ADMIN_CANCELLED', note: reason, by: 'ADMIN', at: '2026-11-11T11:00:00Z' },
      })
      return { status: 200, data: server.order }
    },
    ...routes,
  })
  return { api, server }
}

async function openOrder(user, orderNumber = 'GA-7K3F9Q2M') {
  const table = await screen.findByRole('table', { name: 'Orders' })
  await user.click(within(table).getByRole('button', { name: `View order ${orderNumber}` }))
  const drawer = await screen.findByRole('dialog', { name: `Order ${orderNumber}` })
  await within(drawer).findByRole('heading', { name: 'Status history' })
  return drawer
}

describe('Admin orders', () => {
  it('lists orders and puts status, type, order number and dates in the URL and the request', async () => {
    const user = userEvent.setup()
    const { api } = setup(['FINANCE_ADMIN'])
    const { router } = renderApp('/admin/orders')

    const table = await screen.findByRole('table', { name: 'Orders' })
    const row = within(table).getByText('GA-CONF0002').closest('tr')
    expect(row).toHaveTextContent('Green Basket')
    expect(row).toHaveTextContent('Basmati Rice · 2 items')
    expect(row).toHaveTextContent('cust-2')
    expect(row).toHaveTextContent('₹1,250.00')
    expect(row).toHaveTextContent('Confirmed')
    expect(row).toHaveTextContent('Paid')
    expect(api.callsTo('GET /admin/orders')[0]?.params).toEqual({ page: 0, size: 20 })

    await user.selectOptions(screen.getByLabelText('Status'), 'CONFIRMED')
    await user.selectOptions(screen.getByLabelText('Type'), 'RESTAURANT')
    await user.type(screen.getByLabelText('Order number'), 'ga-conf0002')
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-11-01' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-11-11' } })

    await waitFor(() =>
      expect(api.callsTo('GET /admin/orders').at(-1)?.params).toEqual({
        status: 'CONFIRMED',
        type: 'RESTAURANT',
        q: 'ga-conf0002',
        from: '2026-11-01',
        to: '2026-11-11',
        page: 0,
        size: 20,
      }),
    )
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('CONFIRMED')
    expect(new URLSearchParams(router.state.location.search).get('from')).toBe('2026-11-01')
  })

  it('counts the active filters and clears them all at once', async () => {
    const user = userEvent.setup()
    const { api } = setup(['SUPPORT'])
    const { router } = renderApp('/admin/orders?status=CONFIRMED&type=GROCERY&storeId=s1&page=2')

    const filters = await screen.findByRole('region', { name: 'Search and filters' })
    expect(filters).toHaveTextContent('3 active')
    await user.click(within(filters).getByRole('button', { name: 'Clear all' }))

    await waitFor(() => expect(api.callsTo('GET /admin/orders').at(-1)?.params).toEqual({ page: 0, size: 20 }))
    expect(router.state.location.search).toBe('')
    expect(within(filters).queryByRole('button', { name: 'Clear all' })).not.toBeInTheDocument()
  })

  it('shows the timeline, items, address, customer and the payment with attempts and refunds', async () => {
    const user = userEvent.setup()
    const { api } = setup(['FINANCE_ADMIN'])
    renderApp('/admin/orders')

    const drawer = await openOrder(user)
    expect(within(drawer).getByText('cust-1')).toBeInTheDocument()
    expect(within(drawer).getByText('Basmati Rice')).toBeInTheDocument()
    expect(within(drawer).getByText('12 MG Road')).toBeInTheDocument()
    expect(within(within(drawer).getByRole('list', { name: 'Status history' })).getAllByRole('listitem')).toHaveLength(
      3,
    )
    expect(
      within(drawer)
        .getByText(/^Item total/)
        .closest('dl'),
    ).toHaveTextContent('Total₹125.00')

    // GET /api/payments/orders/{orderId}
    expect(await within(drawer).findByRole('list', { name: 'Payment attempts' })).toHaveTextContent(
      'Payment declined by the bank',
    )
    expect(within(drawer).getByRole('list', { name: 'Refunds' })).toHaveTextContent('Damaged item')
    expect(api.callsTo('GET /payments/orders/:orderId')[0]?.pathParams.orderId).toBe('o1')

    // Finance admins refund but do not cancel.
    expect(within(drawer).queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
    expect(within(drawer).getByText(/can view orders but not cancel them/)).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Issue refund' })).toBeInTheDocument()
  })

  it('lets support cancel with a required reason', async () => {
    const user = userEvent.setup()
    const { api } = setup(['SUPPORT'])
    renderApp('/admin/orders')

    const drawer = await openOrder(user)
    expect(within(drawer).queryByRole('button', { name: 'Issue refund' })).not.toBeInTheDocument()
    await user.click(within(drawer).getByRole('button', { name: 'Cancel order' }))

    const dialog = await screen.findByRole('dialog', { name: 'Cancel order GA-7K3F9Q2M' })
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }))
    expect(await within(dialog).findByText(/enter a reason/i)).toBeInTheDocument()
    expect(api.callsTo('POST /admin/orders/:id/cancel')).toHaveLength(0)

    await user.type(within(dialog).getByLabelText(/Reason/), 'Customer called support')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }))

    expect(await screen.findByText(/Order GA-7K3F9Q2M cancelled\./)).toBeInTheDocument()
    expect(api.callsTo('POST /admin/orders/:id/cancel')[0]?.body).toEqual({ reason: 'Customer called support' })
    expect(api.callsTo('POST /admin/orders/:id/cancel')[0]?.pathParams.id).toBe('o1')
    expect(screen.queryByRole('dialog', { name: 'Cancel order GA-7K3F9Q2M' })).not.toBeInTheDocument()
    expect(await within(drawer).findByText('Cancelled by an admin')).toBeInTheDocument()
    expect(within(drawer).getByText('“Customer called support”')).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
  })

  it('refreshes the order when it can no longer be cancelled (409 ORDER_NOT_CANCELLABLE)', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'], { 'POST /admin/orders/:id/cancel': problem(409, 'ORDER_NOT_CANCELLABLE') })
    renderApp('/admin/orders')

    const drawer = await openOrder(user)
    await user.click(within(drawer).getByRole('button', { name: 'Cancel order' }))
    const dialog = await screen.findByRole('dialog', { name: 'Cancel order GA-7K3F9Q2M' })
    const reads = api.callsTo('GET /admin/orders/:id').length
    await user.type(within(dialog).getByLabelText(/Reason/), 'Duplicate order')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }))

    expect(await screen.findByText('This order can no longer be cancelled. It has been refreshed.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Cancel order GA-7K3F9Q2M' })).not.toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /admin/orders/:id').length).toBeGreaterThan(reads))
  })

  it('offers the Phase 8 statuses in the status filter', async () => {
    const user = userEvent.setup()
    const { api } = setup(['SUPPORT'])
    renderApp('/admin/orders')

    await screen.findByRole('table', { name: 'Orders' })
    const status = screen.getByLabelText('Status')
    for (const label of ['Accepted', 'Preparing', 'Ready for pickup']) {
      expect(within(status).getByRole('option', { name: label })).toBeInTheDocument()
    }
    await user.selectOptions(status, 'READY_FOR_PICKUP')
    await waitFor(() =>
      expect(api.callsTo('GET /admin/orders').at(-1)?.params).toMatchObject({ status: 'READY_FOR_PICKUP' }),
    )
  })

  it('lets support cancel an order the store already accepted (adminCancellable), though the customer no longer can', async () => {
    const user = userEvent.setup()
    const accepted = paidOrder({
      status: 'ACCEPTED',
      cancellable: false,
      adminCancellable: true,
      acceptedAt: '2026-11-11T10:05:00Z',
      readyInMinutes: 20,
      readyBy: '2026-11-11T10:25:00Z',
    })
    setup(['SUPPORT'], { 'GET /admin/orders/:id': { status: 200, data: accepted } })
    renderApp('/admin/orders')

    const drawer = await openOrder(user)
    expect(within(drawer).getByText('Accepted by the store')).toBeInTheDocument()
    expect(within(drawer).getByText(/\(20 min\)/)).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Cancel order' })).toBeInTheDocument()
  })

  it('hides the cancel action when adminCancellable is false, even if cancellable says otherwise', async () => {
    const user = userEvent.setup()
    setup(['OPS_ADMIN'], {
      'GET /admin/orders/:id': { status: 200, data: paidOrder({ cancellable: true, adminCancellable: false }) },
    })
    renderApp('/admin/orders')

    const drawer = await openOrder(user)
    expect(within(drawer).queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
    expect(within(drawer).queryByText(/can view orders but not cancel them/)).not.toBeInTheDocument()
  })

  it('labels a store rejection and the STORE actor', async () => {
    const user = userEvent.setup()
    setup(['FINANCE_ADMIN'], {
      'GET /admin/orders/:id': {
        status: 200,
        data: paidOrder({
          status: 'CANCELLED',
          cancellable: false,
          adminCancellable: false,
          cancellation: {
            reason: 'STORE_REJECTED',
            note: 'OUT_OF_STOCK: Paneer finished',
            by: 'STORE',
            at: '2026-11-11T10:04:00Z',
          },
          timeline: [
            ...paidOrder().timeline,
            { status: 'CANCELLED', at: '2026-11-11T10:04:00Z', reason: 'STORE_REJECTED' },
          ],
        }),
      },
    })
    renderApp('/admin/orders')

    const drawer = await openOrder(user)
    expect(within(drawer).getAllByText('Rejected by the store').length).toBeGreaterThan(0)
    expect(within(drawer).getByText('Store’s reason: Items out of stock: Paneer finished')).toBeInTheDocument()
    expect(within(drawer).getByText(/^By the store ·/)).toBeInTheDocument()
  })

  it('explains an order that has no payment yet', async () => {
    const user = userEvent.setup()
    setup(['SUPER_ADMIN'], {
      'GET /admin/orders/:id': {
        status: 200,
        data: adminOrderView({ status: 'CREATED', payment: { ...adminOrderView().payment, status: 'NOT_STARTED' } }),
      },
      'GET /payments/orders/:orderId': problem(404, 'PAYMENT_NOT_FOUND'),
    })
    renderApp('/admin/orders')

    const drawer = await openOrder(user)
    expect(
      await within(drawer).findByText('No payment yet. It is created once the order is waiting for payment.'),
    ).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Cancel order' })).toBeInTheDocument()
  })
})
