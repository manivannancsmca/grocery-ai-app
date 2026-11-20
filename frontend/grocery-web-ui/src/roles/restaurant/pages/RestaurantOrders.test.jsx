import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { storeBoardPollInterval } from '../../../features/store-orders/hooks'
import { installFakeAudio } from '../../../test/fakeAudio'
import { mockApi, problem } from '../../../test/mockApi'
import { mockEventStream } from '../../../test/mockEventStream'
import { notificationView } from '../../../test/notificationFixtures'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import {
  acceptedStoreOrder,
  newOrderNotification,
  restaurantProfile,
  storeOrder,
} from '../../../test/storeOrderFixtures'

const iso = (offsetMs) => new Date(Date.now() + offsetMs).toISOString()

/** A 409 ProblemDetail; `currentStatus` lands on `status` the way Spring writes an extension of that name. */
function conflict(errorCode, currentStatus) {
  const base = problem(409, errorCode)
  return { status: 409, data: currentStatus ? { ...base.data, status: currentStatus } : base.data }
}

/**
 * restaurant-service for one restaurant: `server.active` answers `?status=ACTIVE`, `server.rejected` /
 * `server.cancelled` the closed lists. The actions move orders like the real service.
 */
function setup(initial, routes = {}) {
  signInAs(['RESTAURANT_OWNER'], { restaurantId: 'r1' })
  const server = { active: initial, rejected: [], cancelled: [] }
  const move = (orderId, change) => {
    const current = server.active.find((order) => order.orderId === orderId)
    const updated = change(current)
    server.active = server.active.filter((order) => order.orderId !== orderId)
    if (updated.status === 'REJECTED') server.rejected = [updated, ...server.rejected]
    else server.active = [...server.active, updated]
    return { status: 200, data: updated }
  }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /restaurant/orders': (request) => {
      switch (request.params.status) {
        case 'ACTIVE':
          return pageOf(server.active, { size: 100 })
        case 'REJECTED':
          return pageOf(server.rejected, { size: 50 })
        case 'CANCELLED':
          return pageOf(server.cancelled, { size: 50 })
        default:
          return problem(400, 'INVALID_PARAMETER')
      }
    },
    'POST /restaurant/orders/:id/accept': (request) =>
      move(request.pathParams.id ?? '', (order) => {
        const { readyInMinutes } = request.body
        return {
          ...order,
          status: 'ACCEPTED',
          acceptedAt: iso(0),
          readyInMinutes,
          readyBy: iso(readyInMinutes * 60_000),
        }
      }),
    'POST /restaurant/orders/:id/reject': (request) =>
      move(request.pathParams.id ?? '', (order) => {
        const { reason, note } = request.body
        return { ...order, status: 'REJECTED', rejection: { reason, note: note ?? null, auto: false, at: iso(0) } }
      }),
    'POST /restaurant/orders/:id/preparing': (request) =>
      move(request.pathParams.id ?? '', (order) => ({ ...order, status: 'PREPARING', preparingAt: iso(0) })),
    'POST /restaurant/orders/:id/ready': (request) =>
      move(request.pathParams.id ?? '', (order) => ({ ...order, status: 'READY_FOR_PICKUP', readyAt: iso(0) })),
    ...routes,
  })
  return { api, server }
}

function column(name) {
  return screen.getByRole('region', { name })
}

async function findCard(columnName, orderNumber) {
  const region = await screen.findByRole('region', { name: columnName })
  return within(region).findByRole('article', { name: `Order ${orderNumber}` })
}

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// The first render of a test file transforms the lazy pages on demand (slow on a Docker bind mount).
describe('Restaurant order board', { timeout: 20_000 }, () => {
  it('shows New · Accepted · Preparing · Ready with countdowns, items, customer and note, plus the orders closed today', async () => {
    const { api, server } = setup([
      storeOrder(),
      storeOrder({ orderId: 'so5', orderNumber: 'GA-URG00005', acceptBy: iso(40_000), note: null }),
      acceptedStoreOrder(),
      acceptedStoreOrder({
        orderId: 'so3',
        orderNumber: 'GA-PRE00003',
        status: 'PREPARING',
        preparingAt: iso(-30_000),
      }),
      acceptedStoreOrder({
        orderId: 'so4',
        orderNumber: 'GA-RDY00004',
        status: 'READY_FOR_PICKUP',
        readyAt: iso(-120_000),
      }),
    ])
    server.rejected = [
      storeOrder({
        orderId: 'so6',
        orderNumber: 'GA-AUT00006',
        status: 'REJECTED',
        rejection: { reason: 'NO_RESPONSE', note: null, auto: true, at: iso(-5 * 60_000) },
      }),
      storeOrder({
        orderId: 'so7',
        orderNumber: 'GA-OLD00007',
        status: 'REJECTED',
        rejection: { reason: 'TOO_BUSY', note: null, auto: false, at: iso(-3 * 86_400_000) },
      }),
    ]
    server.cancelled = [
      storeOrder({
        orderId: 'so8',
        orderNumber: 'GA-CAN00008',
        status: 'CANCELLED',
        cancellation: { reason: 'CUSTOMER_CANCELLED', by: 'CUSTOMER', at: iso(-2 * 60_000) },
      }),
    ]
    renderApp('/restaurant/orders')

    expect(await screen.findByRole('heading', { level: 1, name: 'Orders' })).toBeInTheDocument()
    const fresh = await findCard('New', 'GA-7K3F9Q2M')
    expect(within(fresh).getByRole('timer', { name: 'Time left to accept' })).toHaveTextContent(/^[34]:\d\d$/)
    expect(within(fresh).getByRole('timer', { name: 'Time left to accept' })).not.toHaveClass('text-red-700')
    expect(within(within(fresh).getByRole('list', { name: 'Items' })).getByRole('listitem')).toHaveTextContent(
      '2×Ghee Roast Dosa',
    )
    expect(fresh).toHaveTextContent('Ravi Kumar · Indiranagar, Bengaluru')
    expect(fresh).toHaveTextContent('Note: No onions')
    expect(fresh).toHaveTextContent('₹240')
    expect(within(fresh).getByRole('radio', { name: '20 min' })).toBeChecked()
    expect(within(fresh).getByRole('button', { name: 'Accept · 20 min' })).toBeEnabled()
    expect(within(fresh).getByRole('button', { name: 'Reject' })).toBeEnabled()
    expect(fresh).toHaveTextContent(/Auto-rejects at /)

    // Under a minute left: the countdown turns red.
    const urgent = await findCard('New', 'GA-URG00005')
    expect(within(urgent).getByRole('timer', { name: 'Time left to accept' })).toHaveClass('text-red-700')
    // Soonest deadline first.
    expect(within(column('New')).getAllByRole('article')[0]).toBe(urgent)

    // One obvious next step per state.
    const accepted = within(column('Accepted')).getByRole('article', { name: 'Order GA-ACC00002' })
    expect(accepted).toHaveTextContent(/Ready by .* in 19 min/)
    expect(within(accepted).getByRole('button', { name: 'Start preparing' })).toBeInTheDocument()
    expect(within(accepted).getByRole('button', { name: 'Skip to ready' })).toBeInTheDocument()
    const preparing = within(column('Preparing')).getByRole('article', { name: 'Order GA-PRE00003' })
    expect(
      within(preparing)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(['Ready for pickup'])
    const ready = within(column('Ready')).getByRole('article', { name: 'Order GA-RDY00004' })
    expect(ready).toHaveTextContent('Waiting for a delivery partner')
    expect(within(ready).queryByRole('button')).not.toBeInTheDocument()

    const closed = screen.getByRole('region', { name: 'Closed today' })
    const autoRejected = (await within(closed).findByText('GA-AUT00006')).closest('li')
    expect(within(autoRejected).getByText('Auto-rejected')).toBeInTheDocument()
    expect(autoRejected).toHaveTextContent('not accepted in time')
    const cancelled = within(closed).getByText('GA-CAN00008').closest('li')
    expect(within(cancelled).getByText('Cancelled')).toBeInTheDocument()
    expect(cancelled).toHaveTextContent('by the customer')
    expect(within(closed).queryByText('GA-OLD00007')).not.toBeInTheDocument()

    expect(api.callsTo('GET /restaurant/orders').find((call) => call.params.status === 'ACTIVE')?.params).toEqual({
      status: 'ACTIVE',
      page: 0,
      size: 100,
    })
  })

  it('accepts a new order with the chosen preparation time', async () => {
    const user = userEvent.setup()
    const { api } = setup([storeOrder()])
    renderApp('/restaurant/orders')

    const card = await findCard('New', 'GA-7K3F9Q2M')
    await user.click(within(card).getByRole('radio', { name: '30 min' }))
    expect(within(card).getByRole('radio', { name: '30 min' })).toBeChecked()
    await user.click(within(card).getByRole('button', { name: 'Accept · 30 min' }))

    expect(await screen.findByText('Order GA-7K3F9Q2M accepted — ready in 30 min.')).toBeInTheDocument()
    expect(api.callsTo('POST /restaurant/orders/:id/accept')[0]?.body).toEqual({ readyInMinutes: 30 })
    expect(api.callsTo('POST /restaurant/orders/:id/accept')[0]?.pathParams.id).toBe('so1')
    const accepted = await findCard('Accepted', 'GA-7K3F9Q2M')
    expect(accepted).toHaveTextContent(/Ready by .* in 30 min/)
    expect(within(column('New')).queryByRole('article')).not.toBeInTheDocument()
  })

  it('rejects with a reason, and requires a note for “Other”', async () => {
    const user = userEvent.setup()
    const { api } = setup([storeOrder()])
    renderApp('/restaurant/orders')

    await user.click(within(await findCard('New', 'GA-7K3F9Q2M')).getByRole('button', { name: 'Reject' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject order GA-7K3F9Q2M?' })

    await user.click(within(dialog).getByRole('button', { name: 'Reject order' }))
    expect(await within(dialog).findByText('Choose a reason.')).toBeInTheDocument()

    await user.selectOptions(within(dialog).getByLabelText(/Reason/), 'OTHER')
    await user.click(within(dialog).getByRole('button', { name: 'Reject order' }))
    expect(await within(dialog).findByText(/Add a note/)).toBeInTheDocument()
    expect(api.callsTo('POST /restaurant/orders/:id/reject')).toHaveLength(0)

    await user.type(within(dialog).getByLabelText(/Note for the customer/), 'Gas supply is down')
    await user.click(within(dialog).getByRole('button', { name: 'Reject order' }))

    expect(await screen.findByText('Order GA-7K3F9Q2M rejected. The customer is refunded in full.')).toBeInTheDocument()
    expect(api.callsTo('POST /restaurant/orders/:id/reject')[0]?.body).toEqual({
      reason: 'OTHER',
      note: 'Gas supply is down',
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(within(column('New')).queryByRole('article')).not.toBeInTheDocument())
    expect(
      await within(screen.getByRole('region', { name: 'Closed today' })).findByText('GA-7K3F9Q2M'),
    ).toBeInTheDocument()
  })

  it('sends a reject without a note when none is needed', async () => {
    const user = userEvent.setup()
    const { api } = setup([storeOrder()])
    renderApp('/restaurant/orders')

    await user.click(within(await findCard('New', 'GA-7K3F9Q2M')).getByRole('button', { name: 'Reject' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject order GA-7K3F9Q2M?' })
    await user.selectOptions(within(dialog).getByLabelText(/Reason/), 'TOO_BUSY')
    await user.click(within(dialog).getByRole('button', { name: 'Reject order' }))

    await waitFor(() =>
      expect(api.callsTo('POST /restaurant/orders/:id/reject')[0]?.body).toEqual({ reason: 'TOO_BUSY' }),
    )
  })

  it('moves an accepted order to preparing, then ready for pickup', async () => {
    const user = userEvent.setup()
    const { api } = setup([acceptedStoreOrder()])
    renderApp('/restaurant/orders')

    await user.click(within(await findCard('Accepted', 'GA-ACC00002')).getByRole('button', { name: 'Start preparing' }))
    const preparing = await findCard('Preparing', 'GA-ACC00002')
    expect(api.callsTo('POST /restaurant/orders/:id/preparing')[0]?.pathParams.id).toBe('so2')

    await user.click(within(preparing).getByRole('button', { name: 'Ready for pickup' }))
    expect(await findCard('Ready', 'GA-ACC00002')).toHaveTextContent('Waiting for a delivery partner')
    expect(api.callsTo('POST /restaurant/orders/:id/ready')).toHaveLength(1)
    expect(await screen.findByText('Order GA-ACC00002 is ready for pickup.')).toBeInTheDocument()
  })

  it.each([
    {
      code: 'STORE_ORDER_NOT_PENDING',
      currentStatus: 'CANCELLED',
      message: 'Order GA-7K3F9Q2M is no longer waiting for you',
      description: 'It is now cancelled. The board has been refreshed.',
    },
    {
      code: 'ACCEPTANCE_EXPIRED',
      currentStatus: undefined,
      message: 'Too late to accept order GA-7K3F9Q2M',
      description: /time to accept ran out/,
    },
  ])('explains 409 $code on accept and refreshes the board', async ({ code, currentStatus, message, description }) => {
    const user = userEvent.setup()
    const { api } = setup([storeOrder()], { 'POST /restaurant/orders/:id/accept': conflict(code, currentStatus) })
    renderApp('/restaurant/orders')

    const card = await findCard('New', 'GA-7K3F9Q2M')
    const reads = api.callsTo('GET /restaurant/orders').filter((call) => call.params.status === 'ACTIVE').length
    await user.click(within(card).getByRole('button', { name: /^Accept/ }))

    const toast = (await screen.findByText(message)).closest('li')
    expect(toast).toHaveTextContent(description)
    await waitFor(() =>
      expect(
        api.callsTo('GET /restaurant/orders').filter((call) => call.params.status === 'ACTIVE').length,
      ).toBeGreaterThan(reads),
    )
  })

  it('explains 409 STORE_ORDER_NOT_PENDING on reject inside the board, not the dialog', async () => {
    const user = userEvent.setup()
    setup([storeOrder()], { 'POST /restaurant/orders/:id/reject': conflict('STORE_ORDER_NOT_PENDING', 'ACCEPTED') })
    renderApp('/restaurant/orders')

    await user.click(within(await findCard('New', 'GA-7K3F9Q2M')).getByRole('button', { name: 'Reject' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject order GA-7K3F9Q2M?' })
    await user.selectOptions(within(dialog).getByLabelText(/Reason/), 'STORE_CLOSED')
    await user.click(within(dialog).getByRole('button', { name: 'Reject order' }))

    const toast = (await screen.findByText('Order GA-7K3F9Q2M is no longer waiting for you')).closest('li')
    expect(toast).toHaveTextContent('It is now accepted. The board has been refreshed.')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('explains 409 INVALID_STORE_ORDER_TRANSITION and refreshes the board', async () => {
    const user = userEvent.setup()
    const { api } = setup([acceptedStoreOrder()], {
      'POST /restaurant/orders/:id/preparing': conflict('INVALID_STORE_ORDER_TRANSITION', 'CANCELLED'),
    })
    renderApp('/restaurant/orders')

    const card = await findCard('Accepted', 'GA-ACC00002')
    const reads = api.callsTo('GET /restaurant/orders').length
    await user.click(within(card).getByRole('button', { name: 'Start preparing' }))

    const toast = (await screen.findByText('Order GA-ACC00002 has already moved on')).closest('li')
    expect(toast).toHaveTextContent('It is now cancelled. The board has been refreshed.')
    await waitFor(() => expect(api.callsTo('GET /restaurant/orders').length).toBeGreaterThan(reads))
  })

  it('refreshes on a live STORE_ORDERS_CHANGED signal and polls only while the stream is down', async () => {
    const sse = mockEventStream()
    const { server } = setup([storeOrder()])
    renderApp('/restaurant/orders')

    await findCard('New', 'GA-7K3F9Q2M')
    const stream = await sse.connection()
    expect(await screen.findByText(/new orders appear here instantly/)).toBeInTheDocument()

    server.active = [...server.active, storeOrder({ orderId: 'so9', orderNumber: 'GA-NEW00009' })]
    stream.signal({ type: 'STORE_ORDERS_CHANGED', data: { orderId: 'so9', status: 'NEW' } })

    expect(await findCard('New', 'GA-NEW00009')).toBeInTheDocument()
    expect(storeBoardPollInterval(true)).toBe(false)
    expect(storeBoardPollInterval(false)).toBe(30_000)
  })

  it('beeps for NEW_ORDER only once sound alerts are enabled, flashes the tab title, and stays quiet for other types', async () => {
    const user = userEvent.setup()
    const audio = installFakeAudio()
    const sse = mockEventStream()
    const { server } = setup([storeOrder()])
    renderApp('/restaurant/orders')

    await findCard('New', 'GA-7K3F9Q2M')
    expect(screen.getByRole('button', { name: 'Click to enable sound alerts' })).toHaveTextContent('Enable sound')
    expect(screen.queryByRole('switch', { name: 'Sound alerts' })).not.toBeInTheDocument()
    const stream = await sse.connection()

    // Before the user interacted, the browser would block audio: no beep, but the title flashes and the board refreshes.
    server.active = [...server.active, storeOrder({ orderId: 'so9', orderNumber: 'GA-NEW00009' })]
    stream.notification(newOrderNotification())
    expect(await findCard('New', 'GA-NEW00009')).toBeInTheDocument()
    await waitFor(() => expect(document.title).toBe('(1) New order'))
    expect(audio.tonesStarted).toBe(0)

    await user.click(screen.getByRole('button', { name: 'Click to enable sound alerts' }))
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Click to enable sound alerts' })).not.toBeInTheDocument(),
    )
    const soundSwitch = screen.getByRole('switch', { name: 'Sound alerts' })
    expect(soundSwitch).toHaveAttribute('aria-checked', 'true')
    await waitFor(() => expect(document.title).not.toContain('New order'))

    stream.notification(newOrderNotification({ id: '0193a1b2-0000-7000-8000-0000000000a2' }))
    await waitFor(() => expect(audio.tonesStarted).toBeGreaterThan(0))
    await waitFor(() => expect(document.title).toBe('(1) New order'))
    const tones = audio.tonesStarted

    // Other notification types make no sound.
    stream.notification(
      notificationView({
        id: '0193a1b2-0000-7000-8000-0000000000a3',
        type: 'ORDER_CANCELLED',
        severity: 'WARNING',
        title: 'Order GA-OLD00007 cancelled',
        link: '/restaurant',
      }),
    )
    expect(await screen.findByText('Order GA-OLD00007 cancelled')).toBeInTheDocument()
    expect(audio.tonesStarted).toBe(tones)

    // Sound off (remembered): a NEW_ORDER still flashes the title but stays silent.
    await user.click(soundSwitch)
    expect(soundSwitch).toHaveAttribute('aria-checked', 'false')
    expect(localStorage.getItem('grocery.storeOrders.sound')).toBe('off')
    await waitFor(() => expect(document.title).not.toContain('New order'))
    stream.notification(
      newOrderNotification({ id: '0193a1b2-0000-7000-8000-0000000000a4', title: 'New order GA-NEW00010' }),
    )
    await waitFor(() => expect(document.title).toBe('(1) New order'))
    expect(audio.tonesStarted).toBe(tones)
  })

  it('keeps the header in place until the first click lands, and that click still accepts the order', async () => {
    const user = userEvent.setup()
    installFakeAudio()
    const { api } = setup([storeOrder()])
    renderApp('/restaurant/orders')

    const card = await findCard('New', 'GA-7K3F9Q2M')
    const enable = screen.getByRole('button', { name: 'Click to enable sound alerts' })
    const accept = within(card).getByRole('button', { name: /^Accept/ })

    // A press alone must not unlock audio: removing the button mid-click shifted the board and lost the click.
    await user.pointer({ keys: '[MouseLeft>]', target: accept })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(enable).toBeInTheDocument()
    await user.pointer({ keys: '[/MouseLeft]', target: accept })

    // The completed first click both accepts the order and unlocks audio.
    expect(await screen.findByText('Order GA-7K3F9Q2M accepted — ready in 20 min.')).toBeInTheDocument()
    expect(api.callsTo('POST /restaurant/orders/:id/accept')).toHaveLength(1)
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Click to enable sound alerts' })).not.toBeInTheDocument(),
    )
  })

  it('names the restaurant in the title and says when it is not accepting new orders', async () => {
    setup([], { 'GET /restaurants/me': { status: 200, data: restaurantProfile({ acceptingOrders: false }) } })
    renderApp('/restaurant/orders')

    expect(await screen.findByRole('heading', { level: 1, name: 'Orders · Kaveri Meals & Tiffin' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Not accepting new orders' })).toHaveAttribute('href', '/restaurant')
    expect(await screen.findByText('Nothing picked up yet today')).toBeInTheDocument()
  })

  it('turns the countdown and the New column’s deadline red, then says when the time is up', async () => {
    setup([
      storeOrder({ acceptBy: iso(30_000) }),
      storeOrder({ orderId: 'so9', orderNumber: 'GA-EXP00009', acceptBy: iso(-1_000) }),
    ])
    renderApp('/restaurant/orders')

    const urgent = await findCard('New', 'GA-7K3F9Q2M')
    expect(urgent).toHaveClass('border-red-300')
    expect(within(urgent).getByRole('timer', { name: 'Time left to accept' })).toHaveClass('text-red-700')
    expect(screen.getByRole('heading', { level: 2, name: /^New/ })).toHaveTextContent(/Next expires in 0:00/)
    expect(await findCard('New', 'GA-EXP00009')).toHaveTextContent(
      'Time to accept is up — the order is being rejected automatically.',
    )
  })
})
