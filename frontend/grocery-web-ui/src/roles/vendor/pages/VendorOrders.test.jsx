import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mockApi } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import {
  acceptedStoreOrder,
  storeDelivery,
  storeOrder,
  storeOrderItems,
  vendorProfile,
} from '../../../test/storeOrderFixtures'

const iso = (offsetMs) => new Date(Date.now() + offsetMs).toISOString()

function setup(initial) {
  signInAs(['VENDOR'], { vendorId: 'v1' })
  const server = { active: initial }
  const reply = (orderId, change) => {
    server.active = server.active.map((order) => (order.orderId === orderId ? { ...order, ...change } : order))
    return { status: 200, data: server.active.find((order) => order.orderId === orderId) }
  }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /vendor/orders': (request) =>
      pageOf(request.params.status === 'ACTIVE' ? server.active : [], { size: Number(request.params.size) }),
    'POST /vendor/orders/:id/accept': (request) => {
      const { readyInMinutes } = request.body
      return reply(request.pathParams.id ?? '', {
        status: 'ACCEPTED',
        acceptedAt: iso(0),
        readyInMinutes,
        readyBy: iso(readyInMinutes * 60_000),
      })
    },
    'POST /vendor/orders/:id/preparing': (request) =>
      reply(request.pathParams.id ?? '', { status: 'PREPARING', preparingAt: iso(0) }),
    'POST /vendor/orders/:id/ready': (request) =>
      reply(request.pathParams.id ?? '', { status: 'READY_FOR_PICKUP', readyAt: iso(0) }),
  })
  return { api, server }
}

async function findCard(columnName, orderNumber) {
  const region = await screen.findByRole('region', { name: columnName })
  return within(region).findByRole('article', { name: `Order ${orderNumber}` })
}

describe('Vendor order board', { timeout: 20_000 }, () => {
  it('uses the grocery wording (Packing) and accepts with 15 minutes by default', async () => {
    const user = userEvent.setup()
    const grocery = {
      items: [{ itemId: 'p5', name: 'Basmati Rice 1 kg', quantity: 3, unitPrice: 50, lineTotal: 150 }],
      itemCount: 3,
      subtotal: 150,
    }
    const { api } = setup([storeOrder({ storeId: 'v1', ...grocery })])
    renderApp('/vendor/orders')

    const card = await findCard('New', 'GA-7K3F9Q2M')
    expect(screen.getByRole('region', { name: 'Packing' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Preparing' })).not.toBeInTheDocument()
    expect(within(card).getByRole('radio', { name: '15 min' })).toBeChecked()
    expect(within(card).getByRole('group', { name: /Ready in/ })).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: /^Accept/ }))
    const accepted = await findCard('Accepted', 'GA-7K3F9Q2M')
    expect(api.callsTo('POST /vendor/orders/:id/accept')[0]?.body).toEqual({ readyInMinutes: 15 })

    expect(within(accepted).getByText('Step 2 of 4: Accepted')).toBeInTheDocument()
    await user.click(within(accepted).getByRole('button', { name: 'Start packing' }))
    expect(await findCard('Packing', 'GA-7K3F9Q2M')).toBeInTheDocument()
    expect(api.callsTo('POST /vendor/orders/:id/preparing')).toHaveLength(1)
  })

  it('can skip packing and mark a small accepted order ready at once', async () => {
    const user = userEvent.setup()
    const { api } = setup([acceptedStoreOrder({ storeId: 'v1' })])
    renderApp('/vendor/orders')

    const card = await findCard('Accepted', 'GA-ACC00002')
    // One primary action per state: before packing, ready is only the secondary "Skip to ready".
    expect(within(card).queryByRole('button', { name: 'Ready for pickup' })).not.toBeInTheDocument()
    await user.click(within(card).getByRole('button', { name: 'Skip to ready' }))
    expect(await findCard('Ready', 'GA-ACC00002')).toBeInTheDocument()
    expect(api.callsTo('POST /vendor/orders/:id/preparing')).toHaveLength(0)
    await waitFor(() => expect(api.callsTo('POST /vendor/orders/:id/ready')).toHaveLength(1))
  })

  it('moves a card the moment Accept is pressed, shows it is saving, and puts it back when the store service fails', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    let active = [
      storeOrder({ storeId: 'v1' }),
      storeOrder({ storeId: 'v1', orderId: 'so9', orderNumber: 'GA-NEW00009' }),
    ]
    let release = () => {}
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /vendor/orders': (request) =>
        pageOf(request.params.status === 'ACTIVE' ? active : [], { size: Number(request.params.size) }),
      'POST /vendor/orders/:id/accept': async (request) => {
        await new Promise((resolve) => {
          release = resolve
        })
        if (request.pathParams.id === 'so9')
          return { status: 503, data: { errorCode: 'SERVICE_UNAVAILABLE', detail: 'Try again' } }
        active = active.map((order) =>
          order.orderId === 'so1'
            ? { ...order, status: 'ACCEPTED', acceptedAt: iso(0), readyInMinutes: 15, readyBy: iso(15 * 60_000) }
            : order,
        )
        return { status: 200, data: active.find((order) => order.orderId === 'so1') }
      },
    })
    renderApp('/vendor/orders')

    // Accepted at once: the card is in "Accepted" before the server answers, marked as saving.
    await user.click(within(await findCard('New', 'GA-7K3F9Q2M')).getByRole('button', { name: /^Accept/ }))
    const moved = await findCard('Accepted', 'GA-7K3F9Q2M')
    expect(within(moved).getByRole('status')).toHaveTextContent('Saving…')
    expect(moved).toHaveAttribute('aria-busy', 'true')
    expect(moved).toHaveTextContent(/Ready by .* in 15 min/)
    release()
    expect(await screen.findByText('Order GA-7K3F9Q2M accepted — ready in 15 min.')).toBeInTheDocument()
    await waitFor(() =>
      expect(within(screen.getByRole('region', { name: 'Accepted' })).getByRole('article')).not.toHaveTextContent(
        'Saving…',
      ),
    )

    // A failure puts the card back where it was.
    await user.click(within(await findCard('New', 'GA-NEW00009')).getByRole('button', { name: /^Accept/ }))
    expect(await findCard('Accepted', 'GA-NEW00009')).toBeInTheDocument()
    release()
    expect(await findCard('New', 'GA-NEW00009')).toBeInTheDocument()
  })

  it('shows who collects each accepted order: name, vehicle, phone and distance, or that a partner is being found', async () => {
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const accepted = acceptedStoreOrder({ storeId: 'v1' })
    const ready = acceptedStoreOrder({
      storeId: 'v1',
      orderId: 'so3',
      orderNumber: 'GA-RDY00003',
      status: 'READY_FOR_PICKUP',
      readyAt: iso(-120_000),
    })
    const packing = acceptedStoreOrder({
      storeId: 'v1',
      orderId: 'so4',
      orderNumber: 'GA-PCK00004',
      status: 'PREPARING',
      preparingAt: iso(-60_000),
    })
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /vendor/orders': (request) =>
        pageOf(request.params.status === 'ACTIVE' ? [accepted, ready, packing] : [], {
          size: Number(request.params.size),
        }),
      'GET /delivery/store/deliveries': {
        status: 200,
        data: [
          storeDelivery(),
          storeDelivery({
            deliveryId: 'd3',
            orderId: 'so3',
            orderNumber: 'GA-RDY00003',
            distanceToStoreKm: 0.1,
            etaToStoreMinutes: 1,
          }),
          storeDelivery({
            deliveryId: 'd4',
            orderId: 'so4',
            orderNumber: 'GA-PCK00004',
            status: 'SEARCHING',
            assignedAt: null,
            partner: null,
            partnerLocation: null,
            distanceToStoreKm: null,
            etaToStoreMinutes: null,
          }),
        ],
      },
    })
    renderApp('/vendor/orders')

    const card = await findCard('Accepted', 'GA-ACC00002')
    const partner = await within(card).findByRole('region', { name: 'Delivery partner' })
    expect(partner).toHaveTextContent('Suresh Kannan')
    expect(partner).toHaveTextContent('Motorcycle · TN22EK4410')
    expect(partner).toHaveTextContent('0.4 km away · about 2 min')
    expect(within(partner).getByRole('link', { name: 'Call Suresh Kannan on +91 98400 55004' })).toHaveAttribute(
      'href',
      'tel:+919840055004',
    )

    // Ready: who to hand it to is the main thing on the card, with a call button that shows the number.
    const readyCard = await findCard('Ready', 'GA-RDY00003')
    const handOver = within(readyCard).getByRole('region', { name: 'Delivery partner' })
    expect(handOver).toHaveTextContent(/Hand it to.*Suresh Kannan/)
    expect(handOver).toHaveTextContent('At the store · hand it over')
    expect(within(handOver).getByRole('link', { name: 'Call Suresh Kannan on +91 98400 55004' })).toHaveTextContent(
      '+91 98400 55004',
    )
    expect(within(readyCard).queryByRole('button')).not.toBeInTheDocument()

    expect(within(await findCard('Packing', 'GA-PCK00004')).getByRole('status')).toHaveTextContent(
      'Finding a delivery partner…',
    )
  })

  it('without the delivery view (an older delivery-service) a ready order still waits for a delivery partner', async () => {
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const ready = acceptedStoreOrder({ storeId: 'v1', status: 'READY_FOR_PICKUP', readyAt: iso(-60_000) })
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /vendor/orders': (request) =>
        pageOf(request.params.status === 'ACTIVE' ? [ready] : [], { size: Number(request.params.size) }),
      'GET /delivery/store/deliveries': { status: 404, data: { errorCode: 'NOT_FOUND', detail: 'No static resource' } },
    })
    renderApp('/vendor/orders')

    const card = await findCard('Ready', 'GA-ACC00002')
    await waitFor(() => expect(api.callsTo('GET /delivery/store/deliveries')).toHaveLength(1))
    expect(card).toHaveTextContent('Waiting for a delivery partner.')
    expect(within(card).queryByRole('region', { name: 'Delivery partner' })).not.toBeInTheDocument()
  })

  it('shows an empty board and highlights Orders in the vendor navigation', async () => {
    setup([])
    renderApp('/vendor/orders')

    expect(await screen.findByText('No new orders right now.')).toBeInTheDocument()
    const nav = screen.getAllByRole('navigation', { name: 'Main navigation' })[0]
    expect(within(nav).getByRole('link', { name: 'Orders' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Reports' })).toHaveAttribute('href', '/vendor/reports')
  })

  it('names the store in the title, counts today’s pickups, and flags late orders and the next deadline in the column headers', async () => {
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const late = acceptedStoreOrder({ storeId: 'v1', readyBy: iso(-3 * 60_000 - 5_000) })
    const pickedUp = acceptedStoreOrder({
      storeId: 'v1',
      orderId: 'so7',
      orderNumber: 'GA-PIK00007',
      status: 'PICKED_UP',
      subtotal: 90,
      pickedUpAt: iso(-60_000),
    })
    const lists = { ACTIVE: [storeOrder({ storeId: 'v1', acceptBy: iso(40_000) }), late], PICKED_UP: [pickedUp] }
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /vendors/me': { status: 200, data: vendorProfile() },
      'GET /vendor/orders': (request) =>
        pageOf(lists[String(request.params.status)] ?? [], { size: Number(request.params.size) }),
    })
    renderApp('/vendor/orders')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Orders · Vijaya Nagar Fresh Mart' }),
    ).toBeInTheDocument()
    expect(await screen.findByText(/Today:/)).toHaveTextContent('Today: 1 picked up · ₹90')

    const card = await findCard('Accepted', 'GA-ACC00002')
    expect(card).toHaveTextContent(/Ready by .* 3 min late/)
    expect(screen.getByRole('heading', { level: 2, name: /^Accepted/ })).toHaveTextContent('1 late')
    expect(screen.getByRole('heading', { level: 2, name: /^New/ })).toHaveTextContent(/Next expires in 0:[34]\d/)
  })

  it('folds a long order after three items and opens it with "+N more"', async () => {
    const user = userEvent.setup()
    setup([
      storeOrder({ storeId: 'v1', items: storeOrderItems(6), itemCount: 6 }),
      storeOrder({
        storeId: 'v1',
        orderId: 'so4',
        orderNumber: 'GA-FOUR0004',
        items: storeOrderItems(4),
        itemCount: 4,
      }),
    ])
    renderApp('/vendor/orders')

    const card = await findCard('New', 'GA-7K3F9Q2M')
    const items = within(card).getByRole('list', { name: 'Items' })
    expect(within(items).getAllByRole('listitem')).toHaveLength(3)
    await user.click(within(card).getByRole('button', { name: '+3 more items' }))
    expect(within(items).getAllByRole('listitem')).toHaveLength(6)
    expect(within(card).getByRole('button', { name: 'Show fewer items' })).toHaveAttribute('aria-expanded', 'true')

    // Folding away a single line saves nothing: four items are all listed.
    const four = await findCard('New', 'GA-FOUR0004')
    expect(within(within(four).getByRole('list', { name: 'Items' })).getAllByRole('listitem')).toHaveLength(4)
    expect(within(four).queryByRole('button', { name: /more item/ })).not.toBeInTheDocument()
  })

  describe('on a phone', () => {
    const original = window.matchMedia
    afterEach(() => {
      window.matchMedia = original
    })

    it('shows one column at a time, picked with a switch that counts every column', async () => {
      const user = userEvent.setup()
      window.matchMedia = vi.fn((query) => ({ matches: query === '(max-width: 767px)', media: query }))
      setup([storeOrder({ storeId: 'v1' }), acceptedStoreOrder({ storeId: 'v1' })])
      renderApp('/vendor/orders')

      const switcher = await screen.findByRole('group', { name: 'Board column' })
      expect(within(switcher).getByRole('button', { name: /^New:\s*1$/ })).toHaveAttribute('aria-pressed', 'true')
      expect(within(switcher).getByRole('button', { name: /^Ready:\s*0$/ })).toHaveAttribute('aria-pressed', 'false')
      expect(await findCard('New', 'GA-7K3F9Q2M')).toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'Accepted' })).not.toBeInTheDocument()

      await user.click(within(switcher).getByRole('button', { name: /^Accepted:\s*1$/ }))
      expect(await findCard('Accepted', 'GA-ACC00002')).toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'New' })).not.toBeInTheDocument()
      // The tools keep their names while they show icons only.
      expect(screen.getByRole('button', { name: 'Export orders' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Refresh the board' })).toBeInTheDocument()
    })
  })
})
