import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { cartView, SPICE_ROUTE } from '../../../test/cartFixtures'
import { mockApi, problem } from '../../../test/mockApi'
import { confirmedOrder, orderItem, pageOf } from '../../../test/orderFixtures'
import { daysAgo, returnView } from '../../../test/phase12Fixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

/** Delivered yesterday: 2 × Basmati Rice (₹100) and 3 × Tomato (₹147); returnable. */
function deliveredOrder(overrides = {}) {
  return confirmedOrder({
    status: 'DELIVERED',
    items: [
      orderItem(),
      orderItem({
        itemId: 'p1',
        name: 'Tomato',
        unitLabel: '500 g',
        quantity: 3,
        unitPrice: 49,
        mrp: 60,
        lineTotal: 147,
      }),
    ],
    itemCount: 5,
    subtotal: 247,
    total: 272,
    delivery: { partnerName: 'Ravi', assignedAt: daysAgo(1.1), pickedUpAt: daysAgo(1.05), deliveredAt: daysAgo(1) },
    returnable: true,
    returns: [],
    ...overrides,
  })
}

/** order-service: the order, its returns, and `POST …/returns` answering the server-computed refund. */
function setup(initial, routes = {}) {
  signInAs(['CUSTOMER'])
  const server = { order: initial, returns: [] }
  const api = mockApi({
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /orders/:id': () => ({ status: 200, data: server.order }),
    'GET /orders/:id/returns': () => ({ status: 200, data: server.returns }),
    'POST /orders/:id/returns': (request) => {
      const body = request.body
      // The refund comes from the server (here as if a coupon took 5 % off): never computed by the client.
      const created = returnView({
        items: body.items.map((line) => ({
          itemId: line.itemId,
          name: line.itemId === 'p5' ? 'Basmati Rice' : 'Tomato',
          quantity: line.quantity,
          refundAmount: 47.5,
        })),
        refundAmount: 47.5,
        reason: body.reason,
        note: body.note ?? null,
        decideBy: '2026-11-14T10:30:00Z',
      })
      server.returns = [created]
      server.order = {
        ...server.order,
        returnable: false,
        returns: [
          {
            id: created.id,
            returnNumber: created.returnNumber,
            status: 'REQUESTED',
            refundAmount: 47.5,
            requestedAt: created.requestedAt,
          },
        ],
      }
      return { status: 201, data: created }
    },
    ...routes,
  })
  return { api, server }
}

describe('Returns', () => {
  it('requests a return from a delivered order: validates, sends lines, reason and note, and shows the server’s refund', async () => {
    const user = userEvent.setup()
    const { api } = setup(deliveredOrder())
    renderApp('/orders/o1')

    const after = await screen.findByRole('region', { name: 'After delivery' })
    expect(after).toHaveTextContent(/You can return items until/)
    await user.click(within(after).getByRole('button', { name: 'Return items' }))
    const dialog = await screen.findByRole('dialog', { name: 'Return items' })

    // Nothing chosen yet.
    await user.click(within(dialog).getByRole('button', { name: 'Request return' }))
    expect(within(dialog).getByText('Choose at least one item and how many of it.')).toBeInTheDocument()
    expect(within(dialog).getByText('Choose a reason.')).toBeInTheDocument()
    expect(api.callsTo('POST /orders/:id/returns')).toHaveLength(0)

    const rice = within(dialog).getByRole('group', { name: 'Quantity to return of Basmati Rice' })
    const more = within(rice).getByRole('button', { name: 'Return one more Basmati Rice' })
    await user.click(more)
    await user.click(more)
    expect(more).toBeDisabled() // 2 ordered
    await user.click(within(rice).getByRole('button', { name: 'Return one less Basmati Rice' }))
    expect(within(rice).getByText('1')).toBeInTheDocument()
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Reason' }), 'DAMAGED')
    await user.type(within(dialog).getByRole('textbox', { name: /Details/ }), '  Torn packet ')
    await user.click(within(dialog).getByRole('button', { name: 'Request return' }))

    await waitFor(() => expect(api.callsTo('POST /orders/:id/returns')).toHaveLength(1))
    const call = api.callsTo('POST /orders/:id/returns')[0]
    expect(call?.path).toBe('/orders/o1/returns')
    expect(call?.body).toEqual({ items: [{ itemId: 'p5', quantity: 1 }], reason: 'DAMAGED', note: 'Torn packet' })

    const done = await screen.findByRole('dialog', { name: 'Return requested' })
    expect(within(done).getByRole('status')).toHaveTextContent('Return RT-7K3M9QX2 requested')
    expect(within(done).getByRole('status')).toHaveTextContent('If Green Basket approves it, ₹47.50 will be refunded')
    const perItem = within(done).getByLabelText('Refund per item')
    expect(perItem).toHaveTextContent('1 × Basmati Rice')
    expect(perItem).toHaveTextContent('₹47.50')
    expect(within(done).getByRole('link', { name: 'See my returns' })).toHaveAttribute('href', '/account/returns')
    await user.click(within(done).getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    // The order is re-read: no second request while this one is open; the request is listed.
    const returnsCard = await screen.findByRole('list', { name: 'Return requests' })
    expect(await within(returnsCard).findByText('RT-7K3M9QX2')).toBeInTheDocument()
    expect(within(returnsCard).getByText('Requested')).toBeInTheDocument()
    expect(within(returnsCard).getByText(/1 × Basmati Rice/)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Return items' })).not.toBeInTheDocument())
    expect(screen.getByRole('region', { name: 'After delivery' })).toHaveTextContent('Your request is being handled')
  })

  it('explains refusals (window over, too many) and keeps the dialog open', async () => {
    const user = userEvent.setup()
    let refusal = problem(422, 'RETURN_NOT_ALLOWED')
    setup(deliveredOrder(), { 'POST /orders/:id/returns': () => refusal })
    renderApp('/orders/o1')

    await user.click(await screen.findByRole('button', { name: 'Return items' }))
    const dialog = await screen.findByRole('dialog', { name: 'Return items' })
    await user.click(within(dialog).getByRole('button', { name: 'Return one more Tomato' }))
    await user.selectOptions(within(dialog).getByRole('combobox', { name: 'Reason' }), 'QUALITY')
    await user.click(within(dialog).getByRole('button', { name: 'Request return' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'A return can no longer be requested for this order: its return window has ended, or another request for it is still open.',
    )

    // The window is a platform setting: the date comes from the server's refusal, not from a number in the app.
    refusal = {
      status: 422,
      data: { ...problem(422, 'RETURN_NOT_ALLOWED').data, returnableUntil: '2026-11-12T06:00:00Z' },
    }
    await user.click(within(dialog).getByRole('button', { name: 'Request return' }))
    await waitFor(() =>
      expect(within(dialog).getByRole('alert')).toHaveTextContent(
        /The return window of this order ended on 12 Nov 2026/,
      ),
    )

    refusal = problem(422, 'RETURN_QUANTITY_EXCEEDED')
    await user.click(within(dialog).getByRole('button', { name: 'Request return' }))
    await waitFor(() =>
      expect(within(dialog).getByRole('alert')).toHaveTextContent(
        'You asked to return more of an item than is left to return.',
      ),
    )
  })

  it('only offers what is left to return, and words a restaurant order as "Report a problem"', async () => {
    const user = userEvent.setup()
    setup(
      deliveredOrder({
        type: 'RESTAURANT',
        store: SPICE_ROUTE,
        returns: [
          {
            id: 'ret0',
            returnNumber: 'RT-OLD00001',
            status: 'REFUNDED',
            refundAmount: 147,
            requestedAt: daysAgo(0.9),
            items: [
              { itemId: 'p1', quantity: 3 },
              { itemId: 'p5', quantity: 1 },
            ],
          },
        ],
      }),
    )
    renderApp('/orders/o1')

    await user.click(await screen.findByRole('button', { name: 'Report a problem' }))
    const dialog = await screen.findByRole('dialog', { name: 'Report a problem' })
    expect(within(dialog).getByText('Which dishes had a problem?')).toBeInTheDocument()
    expect(within(dialog).getByText('Already returned')).toBeInTheDocument()
    expect(within(dialog).queryByRole('group', { name: 'Quantity to return of Tomato' })).not.toBeInTheDocument()
    expect(within(dialog).getByText(/1 left to return/)).toBeInTheDocument()
    const rice = within(dialog).getByRole('group', { name: 'Quantity to return of Basmati Rice' })
    await user.click(within(rice).getByRole('button', { name: 'Return one more Basmati Rice' }))
    expect(within(rice).getByRole('button', { name: 'Return one more Basmati Rice' })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: 'Report problem' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Problems reported' })).toBeInTheDocument()
  })

  it('lists my returns with their status, items, refund and the store’s comment', async () => {
    signInAs(['CUSTOMER'])
    const api = mockApi({
      'GET /cart': { status: 200, data: cartView([]) },
      'GET /returns': pageOf(
        [
          returnView(),
          returnView({ id: 'ret2', returnNumber: 'RT-APPROVE1', status: 'APPROVED', refundAmount: 95 }),
          returnView({
            id: 'ret3',
            returnNumber: 'RT-REFUND01',
            status: 'REFUNDED',
            refundedAt: '2026-11-12T09:00:00Z',
          }),
          returnView({ id: 'ret4', returnNumber: 'RT-REJECT01', status: 'REJECTED', comment: 'The seal was intact.' }),
          returnView({ id: 'ret5', returnNumber: 'RT-ESCALAT1', status: 'ESCALATED' }),
        ],
        { size: 10 },
      ),
    })
    renderApp('/account/returns')

    const list = await screen.findByRole('list', { name: 'Your returns' })
    expect(api.callsTo('GET /returns')[0]?.params).toEqual({ page: 0, size: 10 })
    const requested = within(list).getByRole('article', { name: 'Return RT-7K3M9QX2' })
    expect(within(requested).getByText('Requested')).toBeInTheDocument()
    expect(within(requested).getByRole('link', { name: 'GA-7K3F9Q2M' })).toHaveAttribute('href', '/orders/o1')
    expect(within(requested).getByText(/Damaged or spoiled — “Torn packet”/)).toBeInTheDocument()
    expect(requested).toHaveTextContent('Waiting for Green Basket to decide')
    expect(within(list).getByRole('article', { name: 'Return RT-APPROVE1' })).toHaveTextContent(
      'Approved. ₹95 will be refunded to your original payment method.',
    )
    expect(within(list).getByRole('article', { name: 'Return RT-REFUND01' })).toHaveTextContent(
      '₹50 was refunded to your original payment method on',
    )
    expect(within(list).getByRole('article', { name: 'Return RT-REJECT01' })).toHaveTextContent(
      'Rejected: “The seal was intact.”',
    )
    expect(within(list).getByRole('article', { name: 'Return RT-ESCALAT1' })).toHaveTextContent('With support')
  })
})
