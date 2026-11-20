import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { DEAD_LETTER_SERVICES } from '../../../features/operations/api'
import { mockApi, problem } from '../../../test/mockApi'
import { deadLetter, deadLetterServer, healthyConnectors } from '../../../test/operationsFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

/** notification-service could not process a VendorSubmitted event (10:00). */
const vendorSubmitted = deadLetter()
/** inventory-service's replay of an OrderCreated event failed (10:05, newer). */
const orderCreated = deadLetter({
  id: 'order.events.DLT:2:40',
  dltTopic: 'order.events.DLT',
  originalTopic: 'order.events',
  originalPartition: 2,
  originalOffset: 40,
  consumerGroup: 'inventory-service.orders',
  eventId: 'evt-2',
  eventType: 'OrderCreated',
  deadLetteredAt: '2026-11-12T10:05:00Z',
  exceptionClass: 'org.springframework.dao.CannotAcquireLockException',
  exceptionMessage: 'Lock wait timeout exceeded',
  status: 'REPLAY_FAILED',
  attempts: 1,
  lastError: 'Lock wait timeout exceeded',
  lastActionBy: 'ops@example.com',
  lastActionAt: '2026-11-12T10:10:00Z',
})
/** delivery-service: already replayed (hidden by the default filter). */
const orderConfirmed = deadLetter({
  id: 'order.events.DLT:0:7',
  dltTopic: 'order.events.DLT',
  originalTopic: 'order.events',
  originalPartition: 0,
  originalOffset: 7,
  consumerGroup: 'delivery-service.orders',
  eventId: 'evt-3',
  eventType: 'OrderConfirmed',
  deadLetteredAt: '2026-11-12T09:00:00Z',
  status: 'REPLAYED',
  attempts: 1,
})

function setup(roles, routes = {}) {
  signInAs(roles)
  const dlq = deadLetterServer(
    { notification: [vendorSubmitted], inventory: [orderCreated], delivery: [orderConfirmed] },
    ['payment'],
  )
  const api = mockApi({
    ...dlq.routes,
    'GET /admin/orders/stuck': { status: 200, data: [] },
    'GET /admin/system/connectors': { status: 200, data: healthyConnectors() },
    ...routes,
  })
  return { api, dlq }
}

function dataRows(table) {
  return within(table).getAllByRole('row').slice(1)
}

async function openDeadLetter(user, id, name) {
  const table = await screen.findByRole('table', { name: 'Dead letters' })
  await user.click(await within(table).findByRole('button', { name: `View dead letter ${id}` }))
  return screen.findByRole('dialog', { name })
}

describe('Operations — dead letters', () => {
  it('merges the open dead letters of every service, newest first, and names a service that is unavailable', async () => {
    const { api } = setup(['OPS_ADMIN'])
    renderApp('/admin/operations')

    expect(await screen.findByRole('heading', { level: 1, name: 'Operations' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Dead letters' })).toHaveAttribute('aria-selected', 'true')
    const table = await screen.findByRole('table', { name: 'Dead letters' })
    await waitFor(() => expect(dataRows(table)).toHaveLength(2))
    const [first, second] = dataRows(table)
    expect(first).toHaveTextContent('OrderCreated')
    expect(first).toHaveTextContent('Inventory')
    expect(first).toHaveTextContent('inventory-service.orders')
    expect(first).toHaveTextContent('Replay failed')
    expect(first).toHaveTextContent('1 replay')
    expect(first).toHaveTextContent('CannotAcquireLockException')
    expect(second).toHaveTextContent('VendorSubmitted')
    expect(second).toHaveTextContent('Notification')
    expect(second).toHaveTextContent('vendor.events · p1 · #845')
    expect(second).toHaveTextContent('InvalidEventException')
    expect(second).toHaveTextContent('Pending')
    expect(table).not.toHaveTextContent('OrderConfirmed')

    // One failing service does not break the page.
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Payment is unavailable')
    expect(alert).toHaveTextContent('The payment service is unavailable')
    expect(
      within(screen.getByRole('list', { name: 'Dead letters by service' }))
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual(['Inventory 1', 'Notification 1'])

    // Every service once, both open statuses in one request (comma-separated), the newest 100.
    const calls = api.callsTo('GET /admin/ops/:service/dead-letters')
    expect(new Set(calls.map((call) => call.pathParams.service))).toEqual(new Set(DEAD_LETTER_SERVICES))
    expect(calls).toHaveLength(DEAD_LETTER_SERVICES.length)
    expect(calls.filter((call) => call.pathParams.service === 'order').map((call) => call.params)).toEqual([
      { status: 'PENDING,REPLAY_FAILED', page: 0, size: 100 },
    ])
  })

  it('filters by service, status, event type and consumer group (in the URL and the requests)', async () => {
    const user = userEvent.setup()
    const { api } = setup(['SUPPORT'])
    const { router } = renderApp('/admin/operations')

    const table = await screen.findByRole('table', { name: 'Dead letters' })
    await waitFor(() => expect(dataRows(table)).toHaveLength(2))

    await user.selectOptions(screen.getByLabelText('Service'), 'delivery')
    expect(await screen.findByText('Nothing matches these filters.')).toBeInTheDocument()
    expect(new URLSearchParams(router.state.location.search).get('service')).toBe('delivery')

    const lists = () => api.callsTo('GET /admin/ops/:service/dead-letters')
    const before = lists().length
    await user.click(screen.getByRole('checkbox', { name: 'Replayed' }))
    const filtered = await screen.findByRole('table', { name: 'Dead letters' })
    expect(await within(filtered).findByText('OrderConfirmed')).toBeInTheDocument()
    expect(within(filtered).getByText('Replayed')).toBeInTheDocument()
    expect(new URLSearchParams(router.state.location.search).getAll('status')).toEqual([
      'PENDING',
      'REPLAY_FAILED',
      'REPLAYED',
    ])
    const after = lists().slice(before)
    expect(new Set(after.map((call) => call.pathParams.service))).toEqual(new Set(['delivery']))
    expect(after.map((call) => call.params.status)).toEqual(['PENDING,REPLAY_FAILED,REPLAYED'])

    await user.type(screen.getByLabelText('Event type'), 'OrderConfirmed')
    await user.type(screen.getByLabelText('Consumer group'), 'delivery-service.orders')
    await waitFor(() =>
      expect(api.callsTo('GET /admin/ops/:service/dead-letters').at(-1)?.params).toMatchObject({
        eventType: 'OrderConfirmed',
        consumerGroup: 'delivery-service.orders',
      }),
    )
    expect(new URLSearchParams(router.state.location.search).get('group')).toBe('delivery-service.orders')
    expect(
      await within(await screen.findByRole('table', { name: 'Dead letters' })).findByText('OrderConfirmed'),
    ).toBeInTheDocument()

    // The last checked status cannot be unticked.
    await user.click(screen.getByRole('checkbox', { name: 'Pending' }))
    await user.click(screen.getByRole('checkbox', { name: 'Replay failed' }))
    expect(screen.getByRole('checkbox', { name: 'Replayed' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Replayed' })).toBeDisabled()
  })

  it('shows the payload, the exception, the folded stack trace and the bookkeeping in the drawer', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'])
    renderApp('/admin/operations')

    const drawer = await openDeadLetter(user, vendorSubmitted.id, 'VendorSubmitted')
    const payload = await within(drawer).findByLabelText('Payload')
    expect(payload.textContent).toContain('{\n  "vendorId": "v1",\n  "businessName": null,\n  "city": "Bengaluru"\n}')
    expect(api.callsTo('GET /admin/ops/:service/dead-letters/:id')[0]?.pathParams).toEqual({
      service: 'notification',
      id: 'vendor.events.DLT:0:12',
    })

    expect(within(drawer).getByText('com.grocery.common.kafka.InvalidEventException')).toBeInTheDocument()
    expect(within(drawer).getAllByText('Field businessName is required').length).toBeGreaterThan(0)
    const trace = within(drawer).getByText('Stack trace').closest('details')
    expect(trace).not.toHaveAttribute('open')
    expect(trace).toHaveTextContent('VendorListener.java:42')
    await user.click(within(drawer).getByText('Stack trace'))
    expect(trace).toHaveAttribute('open')

    expect(within(drawer).getByText('vendor.events · partition 1 · offset 845')).toBeInTheDocument()
    expect(within(drawer).getByText('notification-service.dispatch')).toBeInTheDocument()
    expect(within(drawer).getByText('VendorSubmitted v1')).toBeInTheDocument()
    expect(within(drawer).getByText('Replay attempts').nextElementSibling).toHaveTextContent('0')
    expect(within(drawer).getByRole('button', { name: 'Replay' })).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'Discard' })).toBeInTheDocument()

    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    // A failed replay shows its last error and who tried.
    const failed = await openDeadLetter(user, orderCreated.id, 'OrderCreated')
    expect(within(failed).getByText('The last replay failed')).toBeInTheDocument()
    expect(within(failed).getByText('Last action').nextElementSibling).toHaveTextContent('ops@example.com')
    expect(within(failed).getByText('Replay attempts').nextElementSibling).toHaveTextContent('1')
  })

  it('replays a dead letter: the outcome shows in the drawer and the list is refreshed', async () => {
    const user = userEvent.setup()
    const { api, dlq } = setup(['OPS_ADMIN'], {
      'POST /admin/ops/:service/dead-letters/:id/replay': (request) => ({
        status: 200,
        data: dlq.update(request.pathParams.service ?? '', request.pathParams.id ?? '', {
          status: 'REPLAYED',
          attempts: 1,
          lastActionBy: 'ops@example.com',
          lastActionAt: '2026-11-12T11:00:00Z',
        }),
      }),
    })
    renderApp('/admin/operations')

    const drawer = await openDeadLetter(user, vendorSubmitted.id, 'VendorSubmitted')
    await within(drawer).findByLabelText('Payload')
    await user.click(within(drawer).getByRole('button', { name: 'Replay' }))

    expect(await screen.findByText('Dead letter replayed')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/ops/:service/dead-letters/:id/replay')[0]?.pathParams).toEqual({
      service: 'notification',
      id: 'vendor.events.DLT:0:12',
    })
    expect(await within(drawer).findByText('Replayed', { selector: 'p' })).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Replay' })).not.toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Discard' })).not.toBeInTheDocument()

    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    const table = screen.getByRole('table', { name: 'Dead letters' })
    await waitFor(() => expect(dataRows(table)).toHaveLength(1))
    expect(table).not.toHaveTextContent('VendorSubmitted')
  })

  it('shows a replay that failed again with its error', async () => {
    const user = userEvent.setup()
    const { dlq } = setup(['SUPER_ADMIN'], {
      'POST /admin/ops/:service/dead-letters/:id/replay': (request) => ({
        status: 200,
        data: dlq.update(request.pathParams.service ?? '', request.pathParams.id ?? '', {
          status: 'REPLAY_FAILED',
          attempts: 1,
          lastError: 'Vendor v1 not found',
        }),
      }),
    })
    renderApp('/admin/operations')

    const drawer = await openDeadLetter(user, vendorSubmitted.id, 'VendorSubmitted')
    await user.click(within(drawer).getByRole('button', { name: 'Replay' }))

    const toasts = screen.getByRole('list', { name: 'Notifications' })
    expect(await within(toasts).findByText('Replay failed')).toBeInTheDocument()
    expect(within(toasts).getByText('Vendor v1 not found')).toBeInTheDocument()
    expect(await within(drawer).findByText('The last replay failed')).toBeInTheDocument()
    expect(within(drawer).getAllByText('Vendor v1 not found').length).toBeGreaterThan(0)
    // Still open: it can be retried or discarded.
    expect(within(drawer).getByRole('button', { name: 'Replay' })).toBeEnabled()
  })

  it('explains a 409 DEAD_LETTER_CLOSED with its current status and refreshes', async () => {
    const user = userEvent.setup()
    const { api, dlq } = setup(['OPS_ADMIN'], {
      'POST /admin/ops/:service/dead-letters/:id/replay': (request) => {
        // Someone else discarded it a moment ago.
        dlq.update(request.pathParams.service ?? '', request.pathParams.id ?? '', {
          status: 'DISCARDED',
          lastActionBy: 'super@example.com',
        })
        const body = problem(409, 'DEAD_LETTER_CLOSED', 'Dead letter vendor.events.DLT:0:12 is DISCARDED')
        return { status: 409, data: { ...body.data, currentStatus: 'DISCARDED' } }
      },
    })
    renderApp('/admin/operations')

    const drawer = await openDeadLetter(user, vendorSubmitted.id, 'VendorSubmitted')
    await within(drawer).findByLabelText('Payload')
    const reads = api.callsTo('GET /admin/ops/:service/dead-letters/:id').length
    await user.click(within(drawer).getByRole('button', { name: 'Replay' }))

    expect(await within(drawer).findByRole('alert')).toHaveTextContent(
      'This dead letter is already discarded (someone else got there first), so nothing was done. It has been refreshed.',
    )
    await waitFor(() => expect(api.callsTo('GET /admin/ops/:service/dead-letters/:id').length).toBeGreaterThan(reads))
    expect(await within(drawer).findByText('Discarded', { selector: 'p' })).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Replay' })).not.toBeInTheDocument()
  })

  it('discards with a required reason of at most 300 characters', async () => {
    const user = userEvent.setup()
    const { api, dlq } = setup(['OPS_ADMIN'], {
      'POST /admin/ops/:service/dead-letters/:id/discard': (request) => ({
        status: 200,
        data: dlq.update(request.pathParams.service ?? '', request.pathParams.id ?? '', {
          status: 'DISCARDED',
          lastError: request.body.reason,
          lastActionBy: 'ops@example.com',
        }),
      }),
    })
    renderApp('/admin/operations')

    const drawer = await openDeadLetter(user, vendorSubmitted.id, 'VendorSubmitted')
    await user.click(within(drawer).getByRole('button', { name: 'Discard' }))
    const dialog = await screen.findByRole('dialog', { name: 'Discard this dead letter?' })
    const reason = within(dialog).getByLabelText(/Reason/)
    expect(reason).toHaveAttribute('maxLength', '300')

    await user.click(within(dialog).getByRole('button', { name: 'Discard' }))
    expect(await within(dialog).findByText('Enter a reason: why is this event not needed?')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/ops/:service/dead-letters/:id/discard')).toHaveLength(0)

    await user.type(reason, 'Malformed test message')
    await user.click(within(dialog).getByRole('button', { name: 'Discard' }))

    expect(await screen.findByText('Dead letter discarded')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/ops/:service/dead-letters/:id/discard')[0]?.body).toEqual({
      reason: 'Malformed test message',
    })
    expect(screen.queryByRole('dialog', { name: 'Discard this dead letter?' })).not.toBeInTheDocument()
    expect(await within(drawer).findByText('Discarded', { selector: 'p' })).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Discard' })).not.toBeInTheDocument()
  })

  it('replays all open dead letters of one service, narrowed to the filters, and shows the result', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'], {
      'POST /admin/ops/:service/dead-letters/replay-all': {
        status: 200,
        data: { attempted: 3, replayed: 2, failed: 1 },
      },
    })
    renderApp('/admin/operations?eventType=VendorSubmitted')

    const table = await screen.findByRole('table', { name: 'Dead letters' })
    await waitFor(() => expect(dataRows(table)).toHaveLength(1))
    const listCalls = api.callsTo('GET /admin/ops/:service/dead-letters').length

    await user.click(screen.getByRole('button', { name: 'Replay all…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Replay all open dead letters' })
    // The service with open dead letters in view is preselected.
    expect(within(dialog).getByLabelText('Service')).toHaveValue('notification')
    expect(dialog).toHaveTextContent('(event type VendorSubmitted only)')
    await user.selectOptions(within(dialog).getByLabelText('Service'), 'inventory')
    await user.selectOptions(within(dialog).getByLabelText('Service'), 'notification')
    await user.click(within(dialog).getByRole('button', { name: 'Replay all' }))

    const result = await within(dialog).findByRole('status')
    expect(result).toHaveTextContent('Notification: replay finished')
    expect(result).toHaveTextContent('Attempted 3 · replayed 2 · failed 1')
    const call = api.callsTo('POST /admin/ops/:service/dead-letters/replay-all')[0]
    expect(call?.pathParams.service).toBe('notification')
    expect(call?.body).toEqual({ eventType: 'VendorSubmitted' })
    await waitFor(() => expect(api.callsTo('GET /admin/ops/:service/dead-letters').length).toBeGreaterThan(listCalls))

    await user.click(within(dialog).getByRole('button', { name: 'Done' }))
    expect(screen.queryByRole('dialog', { name: 'Replay all open dead letters' })).not.toBeInTheDocument()
  })

  it('hides replay, discard and replay all from admins who may only look', async () => {
    const user = userEvent.setup()
    setup(['SUPPORT'])
    renderApp('/admin/operations')

    const table = await screen.findByRole('table', { name: 'Dead letters' })
    await waitFor(() => expect(dataRows(table)).toHaveLength(2))
    expect(screen.queryByRole('button', { name: 'Replay all…' })).not.toBeInTheDocument()

    const drawer = await openDeadLetter(user, vendorSubmitted.id, 'VendorSubmitted')
    await within(drawer).findByLabelText('Payload')
    expect(within(drawer).queryByRole('button', { name: 'Replay' })).not.toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Discard' })).not.toBeInTheDocument()
    expect(within(drawer).getByText(/can inspect dead letters but not replay or discard them/)).toBeInTheDocument()
  })
})
