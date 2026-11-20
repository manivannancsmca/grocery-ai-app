import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { OrderEventEntry } from '../../../features/admin-orders/api'
import { type MockHandler, mockApi, problem } from '../../../test/mockApi'
import { mockEventStream } from '../../../test/mockEventStream'
import { orderEvent } from '../../../test/operationsFixtures'
import { adminOrderSummary, adminOrderView, pageOf, paymentView } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const EVENTS: OrderEventEntry[] = [
  orderEvent(),
  orderEvent({
    eventId: 'e2',
    eventType: 'StockReserved',
    sourceTopic: 'inventory.events',
    direction: 'CONSUMED',
    occurredAt: '2026-11-12T10:00:01Z',
    recordedAt: '2026-11-12T10:00:01.200Z',
  }),
  orderEvent({
    eventId: 'e3',
    eventType: 'PaymentCaptured',
    sourceTopic: 'payment.events',
    direction: 'CONSUMED',
    outcome: 'DUPLICATE',
    occurredAt: '2026-11-12T10:03:00Z',
    recordedAt: '2026-11-12T10:03:02Z',
    correlationId: 'corr-pay',
  }),
  orderEvent({
    eventId: 'e4',
    eventType: 'OrderAcceptedByStore',
    sourceTopic: 'vendor.events',
    direction: 'CONSUMED',
    outcome: 'IGNORED',
    detail: 'Order is CANCELLED, not CONFIRMED',
    occurredAt: '2026-11-12T10:20:00Z',
    recordedAt: '2026-11-12T10:20:00.300Z',
    correlationId: null,
  }),
]

function setup(events: MockHandler) {
  signInAs(['FINANCE_ADMIN'])
  return mockApi({
    'GET /admin/orders': pageOf([adminOrderSummary()]),
    'GET /admin/orders/:id/events': events,
    'GET /admin/orders/:id': { status: 200, data: adminOrderView() },
    'GET /payments/orders/:orderId': { status: 200, data: paymentView() },
  })
}

async function openEvents(user: ReturnType<typeof userEvent.setup>) {
  const table = await screen.findByRole('table', { name: 'Orders' })
  await user.click(within(table).getByRole('button', { name: 'View order GA-7K3F9Q2M' }))
  const drawer = await screen.findByRole('dialog', { name: 'Order GA-7K3F9Q2M' })
  expect(await within(drawer).findByRole('tab', { name: 'Details' })).toHaveAttribute('aria-selected', 'true')
  await user.click(within(drawer).getByRole('tab', { name: 'Events' }))
  return drawer
}

describe('Admin order drawer — Events tab', () => {
  it('shows the order’s event log oldest first, with direction, outcome, the ignored reason, topic, times and correlation id', async () => {
    const user = userEvent.setup()
    const api = setup({ status: 200, data: EVENTS })
    renderApp('/admin/orders')

    const table = await screen.findByRole('table', { name: 'Orders' })
    await user.click(within(table).getByRole('button', { name: 'View order GA-7K3F9Q2M' }))
    const drawer = await screen.findByRole('dialog', { name: 'Order GA-7K3F9Q2M' })
    await within(drawer).findByRole('heading', { name: 'Status history' })
    // Loaded only when the tab is opened.
    expect(api.callsTo('GET /admin/orders/:id/events')).toHaveLength(0)
    await user.click(within(drawer).getByRole('tab', { name: 'Events' }))

    const list = await within(drawer).findByRole('list', { name: 'Order events' })
    expect(api.callsTo('GET /admin/orders/:id/events')[0]?.pathParams.id).toBe('o1')
    expect(within(drawer).queryByRole('heading', { name: 'Status history' })).not.toBeInTheDocument()
    const items = within(list).getAllByRole('listitem')
    expect(items.map((item) => within(item).getByText(/^(OrderCreated|StockReserved|PaymentCaptured|OrderAcceptedByStore)$/).textContent)).toEqual([
      'OrderCreated',
      'StockReserved',
      'PaymentCaptured',
      'OrderAcceptedByStore',
    ])
    const [created, reserved, captured, ignored] = items as [HTMLElement, HTMLElement, HTMLElement, HTMLElement]
    expect(created).toHaveTextContent('Published')
    expect(created).toHaveTextContent('Applied')
    expect(created).toHaveTextContent('order.events')
    expect(created).toHaveTextContent('corr-1')
    expect(reserved).toHaveTextContent('Consumed')
    expect(reserved).toHaveTextContent('inventory.events')
    expect(captured).toHaveTextContent('Duplicate')
    expect(captured).toHaveTextContent('corr-pay')
    expect(ignored).toHaveTextContent('Ignored')
    expect(within(ignored).getByText('Ignored: Order is CANCELLED, not CONFIRMED')).toBeInTheDocument()
    expect(ignored).not.toHaveTextContent('Correlation ID')
    expect(within(created).getByText('Occurred')).toBeInTheDocument()
    expect(within(created).getByText('Recorded')).toBeInTheDocument()

    await user.click(within(drawer).getByRole('tab', { name: 'Details' }))
    expect(await within(drawer).findByRole('heading', { name: 'Status history' })).toBeInTheDocument()
  })

  it('says when no events were recorded, and shows a load error with a retry', async () => {
    const user = userEvent.setup()
    let calls = 0
    setup(() => {
      calls++
      return calls === 1 ? problem(503, 'SERVICE_UNAVAILABLE', 'Order service is down') : { status: 200, data: [] }
    })
    renderApp('/admin/orders')

    const drawer = await openEvents(user)
    const alert = await within(drawer).findByRole('alert')
    expect(alert).toHaveTextContent('Could not load the order’s events')
    expect(alert).toHaveTextContent('Order service is down')
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await within(drawer).findByText(/No events recorded for this order yet/)).toBeInTheDocument()
  })

  it('refreshes the timeline on ADMIN_ORDERS_CHANGED', async () => {
    const user = userEvent.setup()
    const server = { events: EVENTS.slice(0, 1) }
    setup(() => ({ status: 200, data: server.events }))
    const sse = mockEventStream()
    renderApp('/admin/orders')

    const drawer = await openEvents(user)
    const list = await within(drawer).findByRole('list', { name: 'Order events' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(1)
    const stream = await sse.connection()

    server.events = EVENTS.slice(0, 2)
    stream.signal({ type: 'ADMIN_ORDERS_CHANGED', data: { orderId: 'o1', event: 'StockReserved' } })
    await waitFor(() => expect(within(list).getAllByRole('listitem')).toHaveLength(2))
  })
})
