import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { mockEventStream } from '../../../test/mockEventStream'
import { connectorsResponse, stuckOrder } from '../../../test/operationsFixtures'
import { adminOrderSummary, adminOrderView, pageOf, paymentView } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

function setup(roles, routes = {}) {
  signInAs(roles)
  const server = { stuck: [] }
  const api = mockApi({
    'GET /admin/ops/:service/dead-letters': pageOf([]),
    'GET /admin/system/connectors': { status: 200, data: connectorsResponse() },
    'GET /admin/orders/stuck': () => ({ status: 200, data: server.stuck }),
    ...routes,
  })
  return { api, server }
}

describe('Operations — connectors', () => {
  it('shows each connector and task state, the failed task’s trace, and restarts it', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'], { 'POST /admin/system/connectors/:name/restart': { status: 202 } })
    renderApp('/admin/operations?tab=connectors')

    expect(await screen.findByRole('tab', { name: 'Connectors 1' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByText('1 of 2 connectors need attention.')).toBeInTheDocument()
    const list = screen.getByRole('list', { name: 'Connectors' })
    // Sorted by name.
    expect(
      within(list)
        .getAllByRole('listitem', { name: /^Connector / })
        .map((item) => item.getAttribute('aria-label')),
    ).toEqual(['Connector identity-outbox', 'Connector order-outbox'])

    const identity = within(list).getByRole('listitem', { name: 'Connector identity-outbox' })
    const identityTasks = within(identity).getByRole('list', { name: 'Tasks of identity-outbox' })
    expect(within(identityTasks).getByText('Task 0').closest('li')).toHaveTextContent('Failed')
    const trace = within(identity).getByText('Error trace').closest('details')
    expect(trace).not.toHaveAttribute('open')
    expect(trace).toHaveTextContent('payload_missing column does not exist')
    await user.click(within(identity).getByText('Error trace'))
    expect(trace).toHaveAttribute('open')

    const order = within(list).getByRole('listitem', { name: 'Connector order-outbox' })
    expect(within(order).getAllByText('Running')).toHaveLength(2)
    expect(within(order).queryByText('Error trace')).not.toBeInTheDocument()
    expect(within(order).queryByRole('button', { name: /Restart/ })).not.toBeInTheDocument()

    const reads = api.callsTo('GET /admin/system/connectors').length
    await user.click(within(identity).getByRole('button', { name: 'Restart identity-outbox' }))
    expect(await screen.findByText('Restart requested for identity-outbox')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/system/connectors/:name/restart')[0]?.pathParams.name).toBe('identity-outbox')
    await waitFor(() => expect(api.callsTo('GET /admin/system/connectors').length).toBeGreaterThan(reads))
  })

  it('shows the restart error', async () => {
    const user = userEvent.setup()
    setup(['SUPER_ADMIN'], {
      'POST /admin/system/connectors/:name/restart': problem(
        409,
        'REBALANCE_IN_PROGRESS',
        'Cannot complete request because of a conflicting operation',
      ),
    })
    renderApp('/admin/operations?tab=connectors')

    await user.click(await screen.findByRole('button', { name: 'Restart identity-outbox' }))
    expect(await screen.findByText('Could not restart identity-outbox')).toBeInTheDocument()
    expect(screen.getByText(/conflicting operation/)).toBeInTheDocument()
  })

  it('hides Restart from admins who may only look', async () => {
    setup(['FINANCE_ADMIN'])
    renderApp('/admin/operations?tab=connectors')

    const identity = await screen.findByRole('listitem', { name: 'Connector identity-outbox' })
    expect(within(identity).getByText('Failed')).toBeInTheDocument()
    expect(within(identity).queryByRole('button', { name: /Restart/ })).not.toBeInTheDocument()
    expect(within(identity).getByText('Super and operations admins can restart it.')).toBeInTheDocument()
  })

  it('says when Kafka Connect cannot be reached', async () => {
    setup(['OPS_ADMIN'], {
      'GET /admin/system/connectors': problem(503, 'SERVICE_UNAVAILABLE', 'Kafka Connect is not reachable'),
    })
    renderApp('/admin/operations?tab=connectors')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Could not load the connector status')
    expect(alert).toHaveTextContent('Kafka Connect is not reachable')
  })

  it('the System status page shows the same connector states with the restart button', async () => {
    setup(['SUPER_ADMIN'])
    renderApp('/admin/system')

    const section = await screen.findByRole('region', { name: 'Debezium connectors' })
    expect(await within(section).findByRole('listitem', { name: 'Connector identity-outbox' })).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: 'Restart identity-outbox' })).toBeInTheDocument()
    expect(within(section).getByRole('link', { name: 'Operations' })).toHaveAttribute('href', '/admin/operations')
  })
})

describe('Operations — stuck orders', () => {
  it('lists stuck orders with their reason and how long they are overdue, linking into the order drawer', async () => {
    const user = userEvent.setup()
    const { server } = setup(['SUPPORT'], {
      'GET /admin/orders': pageOf([adminOrderSummary({ status: 'CONFIRMED' })]),
      'GET /admin/orders/:id': { status: 200, data: adminOrderView({ status: 'CONFIRMED' }) },
      'GET /payments/orders/:orderId': { status: 200, data: paymentView() },
    })
    server.stuck = [
      stuckOrder(),
      stuckOrder({
        orderId: 'o7',
        orderNumber: 'GA-PAY00007',
        type: 'RESTAURANT',
        status: 'AWAITING_PAYMENT',
        reason: 'PAYMENT_EXPIRY_OVERDUE',
        minutesOverdue: 4,
        store: { id: 'r1', name: 'Spice Route' },
      }),
    ]
    const { router } = renderApp('/admin/operations?tab=stuck-orders')

    expect(await screen.findByRole('tab', { name: 'Stuck orders 2' })).toHaveAttribute('aria-selected', 'true')
    const table = await screen.findByRole('table', { name: 'Stuck orders' })
    const [first, second] = within(table).getAllByRole('row').slice(1)
    expect(first).toHaveTextContent('GA-7K3F9Q2M')
    expect(first).toHaveTextContent('Grocery')
    expect(first).toHaveTextContent('Confirmed')
    expect(first).toHaveTextContent('Store response overdue')
    expect(first).toHaveTextContent('1 h 25 min')
    expect(first).toHaveTextContent('Green Basket')
    expect(second).toHaveTextContent('GA-PAY00007')
    expect(second).toHaveTextContent('Restaurant')
    expect(second).toHaveTextContent('Awaiting payment')
    expect(second).toHaveTextContent('Payment expiry overdue')
    expect(second).toHaveTextContent('4 min')

    const link = within(table).getByRole('link', { name: 'Open order GA-7K3F9Q2M' })
    expect(link).toHaveAttribute('href', '/admin/orders?q=GA-7K3F9Q2M&order=o1')
    await user.click(link)

    const drawer = await screen.findByRole('dialog', { name: 'Order GA-7K3F9Q2M' })
    expect(router.state.location.pathname).toBe('/admin/orders')
    expect(screen.getByLabelText('Order number')).toHaveValue('GA-7K3F9Q2M')
    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('order')).toBeNull())
    expect(screen.queryByRole('dialog', { name: 'Order GA-7K3F9Q2M' })).not.toBeInTheDocument()
  })

  it('refreshes on ADMIN_ORDERS_CHANGED: a resolved order leaves the list', async () => {
    const { server } = setup(['OPS_ADMIN'])
    server.stuck = [stuckOrder()]
    const sse = mockEventStream()
    renderApp('/admin/operations?tab=stuck-orders')

    const table = await screen.findByRole('table', { name: 'Stuck orders' })
    expect(within(table).getByText('GA-7K3F9Q2M')).toBeInTheDocument()
    const stream = await sse.connection()

    server.stuck = []
    stream.signal({ type: 'ADMIN_ORDERS_CHANGED', data: { orderId: 'o1', event: 'OrderAccepted' } })

    expect(await screen.findByText('No stuck orders')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Stuck orders' })).toBeInTheDocument()
  })
})
