import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { etaMinutesFor } from '../../../features/order-tracking/cache'
import { distanceKm } from '../../../shared/components/map/types'
import { cartView } from '../../../test/cartFixtures'
import { HOME_POINT, STORE_POINT, trackingView } from '../../../test/deliveryFixtures'
import { mockApi, problem } from '../../../test/mockApi'
import { mockEventStream } from '../../../test/mockEventStream'
import { notificationView } from '../../../test/notificationFixtures'
import { confirmedOrder, orderSummary, pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const wait = (ms) => act(() => new Promise((resolve) => setTimeout(resolve, ms)))

const TRACKING = 'GET /delivery/orders/:orderId/tracking'

/** The order's status card (the arrival estimate lives there, next to the map). */
const hero = () => screen.getByRole('region', { name: 'Order GA-7K3F9Q2M' })

function acceptedOrder(overrides = {}) {
  return confirmedOrder({
    status: 'ACCEPTED',
    cancellable: false,
    trackable: true,
    delivery: null,
    acceptedAt: '2026-11-11T10:05:00Z',
    ...overrides,
  })
}

/** order-service + delivery-service for order o1: `server.tracking = null` answers 404 TRACKING_NOT_FOUND. */
function setup(order, tracking) {
  signInAs(['CUSTOMER'])
  const server = { order, tracking }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /orders/:id': () => ({ status: 200, data: server.order }),
    [TRACKING]: () => (server.tracking ? { status: 200, data: server.tracking } : problem(404, 'TRACKING_NOT_FOUND')),
  })
  return { api, server }
}

describe('Order page — live tracking', { timeout: 20_000 }, () => {
  it('looks for a partner (404), shows the partner on PARTNER_ASSIGNED, and moves the marker on DELIVERY_LOCATION without refetching', async () => {
    const sse = mockEventStream()
    const { api, server } = setup(acceptedOrder(), null)
    renderApp('/orders/o1')

    const panel = await screen.findByRole('region', { name: 'Live tracking' })
    expect(await within(panel).findByText('Looking for a delivery partner…')).toBeInTheDocument()
    expect(api.callsTo(TRACKING)[0]?.pathParams.orderId).toBe('o1')
    const stream = await sse.connection()

    server.tracking = trackingView()
    server.order = acceptedOrder({
      delivery: { partnerName: 'Ravi', assignedAt: '2026-11-11T10:06:00Z', pickedUpAt: null, deliveredAt: null },
    })
    stream.notification(
      notificationView({
        id: '0193a1b2-0000-7000-8000-0000000000e1',
        type: 'PARTNER_ASSIGNED',
        severity: 'INFO',
        title: 'Ravi will deliver order GA-7K3F9Q2M',
        body: '',
        link: '/orders/o1',
      }),
    )

    expect(await within(panel).findByText('Ravi is heading to Green Basket')).toBeInTheDocument()
    const partnerCard = within(panel).getByRole('region', { name: 'Delivery partner' })
    expect(partnerCard).toHaveTextContent('Your delivery partner · Scooter')
    // The number plate to spot them, and a call button with the number written the way people read it.
    expect(within(partnerCard).getByTitle('Number plate')).toHaveTextContent('TN09AB4521')
    const call = within(partnerCard).getByRole('link', { name: 'Call Ravi on +91 98400 66004' })
    expect(call).toHaveAttribute('href', 'tel:+919840066004')
    expect(call).toHaveTextContent('Call +91 98400 66004')
    // The estimate is said once, in the order's status (not in the tracking panel as well).
    await waitFor(() => expect(hero()).toHaveTextContent('Arriving in about 12 min'))
    expect(panel).not.toHaveTextContent('Arriving in about')
    const partner = await within(panel).findByTitle('Ravi (delivery partner)')
    expect(partner).toHaveAttribute('data-latitude', '12.965')
    expect(within(panel).getByTitle('Green Basket (store)')).toBeInTheDocument()
    expect(within(panel).getByTitle('Your address')).toBeInTheDocument()
    expect(within(panel).getByRole('list', { name: 'Map legend' })).toHaveTextContent('Ravi (delivery partner)')

    // The partner reaches the store: the marker moves and the ETA follows, with no request.
    const reads = api.callsTo(TRACKING).length
    stream.signal({
      type: 'DELIVERY_LOCATION',
      data: { orderId: 'o1', ...STORE_POINT, heading: 80, at: new Date().toISOString() },
    })

    await waitFor(() =>
      expect(within(panel).getByTitle('Ravi (delivery partner)')).toHaveAttribute(
        'data-latitude',
        String(STORE_POINT.latitude),
      ),
    )
    expect(within(panel).getByTitle('Ravi (delivery partner)')).toHaveAttribute(
      'data-longitude',
      String(STORE_POINT.longitude),
    )
    // Moved in place (`setLatLng`): the same marker, not a new map or marker per ping.
    expect(within(panel).getByTitle('Ravi (delivery partner)')).toBe(partner)
    expect(hero()).toHaveTextContent(`Arriving in about ${etaMinutesFor(distanceKm(STORE_POINT, HOME_POINT))} min`)
    // A signal for another order changes nothing.
    stream.signal({
      type: 'DELIVERY_LOCATION',
      data: { orderId: 'o2', latitude: 13.1, longitude: 77.7, at: new Date().toISOString() },
    })
    await wait(400)
    expect(api.callsTo(TRACKING)).toHaveLength(reads)
    expect(within(panel).getByTitle('Ravi (delivery partner)')).toHaveAttribute(
      'data-latitude',
      String(STORE_POINT.latitude),
    )
  })

  it('shows "On the way" after pickup, without a cancel action, and the delivered state afterwards', async () => {
    const user = userEvent.setup()
    const sse = mockEventStream()
    const outForDelivery = acceptedOrder({
      status: 'OUT_FOR_DELIVERY',
      delivery: {
        partnerName: 'Ravi',
        assignedAt: '2026-11-11T10:06:00Z',
        pickedUpAt: '2026-11-11T10:20:00Z',
        deliveredAt: null,
      },
      timeline: [
        ...confirmedOrder().timeline,
        { status: 'OUT_FOR_DELIVERY', at: '2026-11-11T10:20:00Z', reason: null },
      ],
    })
    const { server } = setup(outForDelivery, trackingView({ status: 'PICKED_UP', etaMinutes: 7 }))
    renderApp('/orders/o1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Order GA-7K3F9Q2M' })).toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'On the way' })).toBeInTheDocument()
    expect(
      within(screen.getByRole('list', { name: 'Order progress' })).getByRole('listitem', { current: 'step' }),
    ).toHaveTextContent('On the way')
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
    expect(screen.getByText(/picked up and is on its way, so it can no longer be cancelled/)).toBeInTheDocument()
    expect(screen.queryByText(/Contact support to cancel/)).not.toBeInTheDocument()
    expect(screen.getByText(/Ravi picked up your order/)).toBeInTheDocument()

    const panel = await screen.findByRole('region', { name: 'Live tracking' })
    expect(await within(panel).findByText('Ravi is on the way to you')).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: 'Call Ravi on +91 98400 66004' })).toBeInTheDocument()
    await waitFor(() => expect(hero()).toHaveTextContent('Arriving in about 7 min'))
    await user.click(screen.getByRole('button', { name: /View all updates/ }))
    expect(
      within(screen.getByRole('list', { name: 'Status history' }))
        .getAllByRole('listitem')
        .at(-1),
    ).toHaveTextContent('On the way')

    const stream = await sse.connection()
    server.order = {
      ...outForDelivery,
      status: 'DELIVERED',
      trackable: false,
      delivery: { ...outForDelivery.delivery, deliveredAt: '2026-11-11T10:31:00Z' },
    }
    stream.notification(
      notificationView({
        id: '0193a1b2-0000-7000-8000-0000000000e2',
        type: 'ORDER_DELIVERED',
        title: 'Order GA-7K3F9Q2M delivered',
        body: 'Enjoy!',
        link: '/orders/o1',
      }),
    )

    expect(await screen.findByText(/^Delivered at .* by Ravi\. Enjoy!$/)).toBeInTheDocument()
    expect(screen.getByRole('status', { name: 'Delivered' })).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Live tracking' })).not.toBeInTheDocument())
    // Delivered: no arrival estimate any more.
    expect(hero()).not.toHaveTextContent('Arriving in about')
  })

  it('explains a failed delivery and the refund that support decides on', async () => {
    const user = userEvent.setup()
    const failed = acceptedOrder({
      status: 'DELIVERY_FAILED',
      trackable: false,
      timeline: [
        ...confirmedOrder().timeline,
        { status: 'OUT_FOR_DELIVERY', at: '2026-11-11T10:20:00Z', reason: null },
        { status: 'DELIVERY_FAILED', at: '2026-11-11T10:40:00Z', reason: 'CUSTOMER_UNREACHABLE' },
      ],
    })
    const { api } = setup(failed, null)
    renderApp('/orders/o1')

    expect(await screen.findByText('We could not deliver your order')).toBeInTheDocument()
    expect(screen.getByText('Our support team will contact you about a refund.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /View all updates/ }))
    const history = screen.getByRole('list', { name: 'Status history' })
    expect(within(history).getAllByRole('listitem').at(-1)).toHaveTextContent('Delivery failed')
    expect(history).toHaveTextContent('The customer could not be reached')
    expect(screen.queryByRole('region', { name: 'Live tracking' })).not.toBeInTheDocument()
    expect(api.callsTo(TRACKING)).toHaveLength(0)
  })

  it('lists orders on the way under their own tab', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /cart': { status: 200, data: cartView([]) },
      'GET /orders': (request) =>
        pageOf(
          request.params.status === 'OUT_FOR_DELIVERY' || !request.params.status
            ? [orderSummary({ status: 'OUT_FOR_DELIVERY', payment: { status: 'PAID' } })]
            : [],
        ),
    })
    renderApp('/orders')

    const list = await screen.findByRole('list', { name: 'Orders in progress' })
    expect(within(list).getByRole('link')).toHaveAccessibleDescription(/On the way/)
    expect(list).toHaveTextContent('Arriving soon')
    expect(list).toHaveTextContent('Track order')
    await user.click(screen.getByRole('tab', { name: 'On the way' }))
    await waitFor(() => expect(api.callsTo('GET /orders').at(-1)?.params).toMatchObject({ status: 'OUT_FOR_DELIVERY' }))
    await user.click(screen.getByRole('tab', { name: 'Delivered' }))
    expect(await screen.findByText('No delivered orders yet')).toBeInTheDocument()
  })

  it('tracks a restaurant order: restaurant marker, partner → home route after pickup, distance, a stale-position warning and the live badge', async () => {
    const sse = mockEventStream()
    const order = acceptedOrder({
      status: 'OUT_FOR_DELIVERY',
      store: { id: 'r1', type: 'RESTAURANT', name: 'Spice Route' },
      delivery: {
        partnerName: 'Ravi',
        assignedAt: '2026-11-11T10:06:00Z',
        pickedUpAt: '2026-11-11T10:20:00Z',
        deliveredAt: null,
      },
    })
    const lastFix = new Date(Date.now() - 5 * 60_000).toISOString()
    setup(
      order,
      trackingView({
        status: 'PICKED_UP',
        pickup: { name: 'Spice Route', ...STORE_POINT },
        partnerLocation: { latitude: 12.975, longitude: 77.62, heading: 90, at: lastFix },
        etaMinutes: 6,
      }),
    )
    renderApp('/orders/o1')

    const panel = await screen.findByRole('region', { name: 'Live tracking' })
    expect(await within(panel).findByText('Ravi is on the way to you')).toBeInTheDocument()
    expect(within(panel).getByTitle('Spice Route (restaurant)')).toBeInTheDocument()
    // After the pickup the route runs from the partner straight to the customer.
    expect(within(panel).getByTestId('map-route')).toHaveAttribute(
      'data-points',
      `12.975,77.62;${HOME_POINT.latitude},${HOME_POINT.longitude}`,
    )
    expect(panel).toHaveTextContent(
      `${distanceKm({ latitude: 12.975, longitude: 77.62 }, HOME_POINT).toFixed(1)} km away`,
    )
    // Five minutes without a new position: said plainly, not shown as live.
    expect(panel).toHaveTextContent(/Last location 5 min ago .*may have lost its connection/)

    // The badge follows the push connection: live once the stream is open.
    const stream = await sse.connection()
    expect(await within(panel).findByText('Live')).toBeInTheDocument()

    // A new position clears the warning, without reloading.
    stream.signal({
      type: 'DELIVERY_LOCATION',
      data: { orderId: 'o1', latitude: 12.977, longitude: 77.63, heading: 90, at: new Date().toISOString() },
    })
    await waitFor(() => expect(panel).toHaveTextContent(/Ravi's location updated at/))
    expect(panel).not.toHaveTextContent('Last location')
  })

  it.each([
    [
      'from an older server',
      trackingView({ status: 'PICKED_UP', partner: { name: 'Suresh', vehicleType: 'MOTORCYCLE' } }),
      'Suresh is on the way to you',
    ],
    [
      'once the delivery is over',
      trackingView({
        status: 'DELIVERED',
        partner: { name: 'Suresh', vehicleType: 'MOTORCYCLE', phone: null, vehicleNumber: null },
        partnerLocation: null,
      }),
      'Suresh delivered your order',
    ],
  ])('offers no call button without the partner’s phone (%s)', async (_case, tracking, doing) => {
    const order = acceptedOrder({
      status: 'OUT_FOR_DELIVERY',
      delivery: {
        partnerName: 'Suresh',
        assignedAt: '2026-11-11T10:06:00Z',
        pickedUpAt: '2026-11-11T10:20:00Z',
        deliveredAt: null,
      },
    })
    setup(order, tracking)
    renderApp('/orders/o1')

    const panel = await screen.findByRole('region', { name: 'Live tracking' })
    expect(await within(panel).findByText(doing)).toBeInTheDocument()
    const partnerCard = within(panel).getByRole('region', { name: 'Delivery partner' })
    expect(partnerCard).toHaveTextContent('Your delivery partner · Motorcycle')
    expect(within(partnerCard).queryByRole('link')).not.toBeInTheDocument()
    expect(within(partnerCard).queryByTitle('Number plate')).not.toBeInTheDocument()
    expect(panel).not.toHaveTextContent('+91')
  })

  it('shows the pickup, the home and the route while a partner is being found', async () => {
    setup(
      acceptedOrder(),
      trackingView({ status: 'SEARCHING', partner: null, partnerLocation: null, etaMinutes: null }),
    )
    renderApp('/orders/o1')

    const panel = await screen.findByRole('region', { name: 'Live tracking' })
    expect(await within(panel).findByText('Looking for a delivery partner…')).toBeInTheDocument()
    expect(await within(panel).findByTitle('Green Basket (store)')).toBeInTheDocument()
    expect(within(panel).getByTitle('Your address')).toBeInTheDocument()
    expect(within(panel).getByTestId('map-route')).toHaveAttribute(
      'data-points',
      `${STORE_POINT.latitude},${STORE_POINT.longitude};${HOME_POINT.latitude},${HOME_POINT.longitude}`,
    )
  })
})
