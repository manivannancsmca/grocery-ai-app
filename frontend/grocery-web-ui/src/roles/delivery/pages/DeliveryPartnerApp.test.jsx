import { act, cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { driveStep } from '../../../features/deliveries/simulator'
import { presetRange } from '../../../features/deliveries/statsPeriod'
import { indiaDay, shiftDay } from '../../../shared/utils/indiaDate'
import {
  approvedPartner,
  deliveryOfferNotification,
  deliveryView,
  HOME_POINT,
  offerView,
  partnerStats,
  partnerStatus,
  pickedUpDelivery,
  STORE_POINT,
} from '../../../test/deliveryFixtures'
import { installFakeGeolocation } from '../../../test/fakeGeolocation'
import { clickMap } from '../../../test/leafletMock'
import { mockApi, problem } from '../../../test/mockApi'
import { mockEventStream } from '../../../test/mockEventStream'
import { notificationView } from '../../../test/notificationFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const iso = (offsetMs) => new Date(Date.now() + offsetMs).toISOString()
const wait = (ms) => act(() => new Promise((resolve) => setTimeout(resolve, ms)))

/** delivery-service for one approved partner: status, offers and the active delivery move like the real service. */
function setup(initial = {}, routes = {}) {
  signInAs(['DELIVERY_PARTNER'], { fullName: 'Ravi Kumar' })
  const server = { status: partnerStatus(), active: null, offers: [], ...initial }
  const finish = (change) => {
    const done = { ...server.active, ...change }
    server.active = null
    return { status: 200, data: done }
  }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /delivery/partners/me': { status: 200, data: approvedPartner() },
    'GET /delivery/partners/me/status': () => ({ status: 200, data: server.status }),
    'GET /delivery/stats': { status: 200, data: partnerStats() },
    'PUT /delivery/partners/me/availability': (request) => {
      server.status = { ...server.status, online: request.body.online }
      return { status: 200, data: server.status }
    },
    'PUT /delivery/partners/me/location': { status: 204 },
    'GET /delivery/offers': () => ({ status: 200, data: server.offers }),
    'POST /delivery/offers/:id/accept': () => {
      server.offers = []
      server.active = deliveryView()
      return { status: 200, data: server.active }
    },
    'POST /delivery/offers/:id/reject': (request) => {
      server.offers = server.offers.filter((offer) => offer.offerId !== request.pathParams.id)
      return { status: 204 }
    },
    'GET /delivery/deliveries/active': () => (server.active ? { status: 200, data: server.active } : { status: 204 }),
    'POST /delivery/deliveries/:id/pickup': () => {
      server.active = pickedUpDelivery()
      return { status: 200, data: server.active }
    },
    'POST /delivery/deliveries/:id/deliver': () => finish({ status: 'DELIVERED', deliveredAt: iso(0) }),
    'POST /delivery/deliveries/:id/fail': (request) =>
      finish({ status: 'FAILED', failedAt: iso(0), failure: request.body }),
    ...routes,
  })
  return { api, server }
}

async function findOnlineSwitch() {
  return screen.findByRole('switch', { name: 'Online' })
}

let geolocation = null

function fakeGeolocation() {
  geolocation = installFakeGeolocation()
  return geolocation
}

afterEach(() => {
  // Unmount first: the app clears its watch on the way out.
  cleanup()
  geolocation?.uninstall()
  geolocation = null
})

// The first render of a test file transforms the lazy pages on demand (slow on a Docker bind mount).
describe('Delivery partner app — availability and location', { timeout: 20_000 }, () => {
  it('goes online, shares the device location at most every 5 s, and stops watching when going offline', async () => {
    const geo = fakeGeolocation()
    const user = userEvent.setup()
    const { api } = setup()
    renderApp('/delivery')

    const toggle = await findOnlineSwitch()
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('You are offline')).toBeInTheDocument()
    expect(geo.geolocation.watchPosition).not.toHaveBeenCalled()

    await user.click(toggle)
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'true'))
    expect(api.callsTo('PUT /delivery/partners/me/availability')[0]?.body).toEqual({ online: true })
    await waitFor(() => expect(geo.watching).toBe(1))
    expect(screen.getByText('Finding your location…')).toBeInTheDocument()
    expect(await screen.findByText(/No offers right now/)).toBeInTheDocument()

    // The first fix goes out at once, with accuracy, heading and speed (m/s → km/h).
    geo.move(12.97, 77.59, { accuracy: 8.4, heading: 90, speed: 5 })
    await waitFor(() => expect(api.callsTo('PUT /delivery/partners/me/location')).toHaveLength(1))
    expect(api.callsTo('PUT /delivery/partners/me/location')[0]?.body).toEqual({
      latitude: 12.97,
      longitude: 77.59,
      accuracyMeters: 8,
      heading: 90,
      speedKmh: 18,
    })
    expect(await screen.findByText(/Sharing your location · last sent/)).toBeInTheDocument()

    // Another fix right away waits for the 5 s window.
    geo.move(12.971, 77.591)
    await wait(400)
    expect(api.callsTo('PUT /delivery/partners/me/location')).toHaveLength(1)

    await user.click(toggle)
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'))
    expect(api.callsTo('PUT /delivery/partners/me/availability')[1]?.body).toEqual({ online: false })
    expect(geo.watching).toBe(0)
    expect(geo.geolocation.clearWatch).toHaveBeenCalled()
    expect(screen.queryByRole('region', { name: 'Delivery offers' })).not.toBeInTheDocument()
  })

  it('stops reporting after 409 PARTNER_OFFLINE and says so', async () => {
    const geo = fakeGeolocation()
    const { api } = setup(
      { status: partnerStatus({ online: true }) },
      { 'PUT /delivery/partners/me/location': problem(409, 'PARTNER_OFFLINE', 'The partner is offline') },
    )
    renderApp('/delivery')

    await waitFor(() => expect(geo.watching).toBe(1))
    geo.move(12.97, 77.59)

    expect(await screen.findByText('Location sharing stopped')).toBeInTheDocument()
    expect(geo.watching).toBe(0)
    // The server's view of the partner is re-read.
    await waitFor(() => expect(api.callsTo('GET /delivery/partners/me/status').length).toBeGreaterThan(1))
    geo.move(12.98, 77.6)
    await wait(300)
    expect(api.callsTo('PUT /delivery/partners/me/location')).toHaveLength(1)
  })

  it('explains a blocked location permission in a compact notice, with the steps behind "How to fix"', async () => {
    const geo = fakeGeolocation()
    const user = userEvent.setup()
    setup({ status: partnerStatus({ online: true }) })
    renderApp('/delivery')

    await waitFor(() => expect(geo.watching).toBe(1))
    geo.deny()

    const alert = (await screen.findByText('Location access is blocked')).closest('[role="alert"]')
    expect(alert).toHaveTextContent(/We need your location to offer you nearby deliveries/)
    expect(alert).not.toHaveTextContent(/Allow location for this site/)
    // The availability panel points at the notice instead of "Finding your location…".
    expect(within(screen.getByRole('region', { name: 'Availability' })).getByRole('status')).toHaveTextContent(
      'Your location is not shared',
    )

    const howTo = within(alert).getByRole('button', { name: 'How to fix' })
    expect(howTo).toHaveAttribute('aria-expanded', 'false')
    await user.click(howTo)
    expect(howTo).toHaveAttribute('aria-expanded', 'true')
    expect(alert).toHaveTextContent(/Allow location for this site: tap the lock icon next to the address/)
    expect(alert).toHaveTextContent(/Go offline and online again/)
    expect(alert).toHaveTextContent(/location simulator below/)
    await user.click(howTo)
    expect(alert).not.toHaveTextContent(/Allow location for this site/)
  })

  it('goes online with the big "Go online" button and shows today’s numbers and the links in the banner', async () => {
    fakeGeolocation()
    const user = userEvent.setup()
    const stats = partnerStats()
    const { api } = setup(
      {},
      {
        'GET /delivery/stats': {
          status: 200,
          data: {
            ...stats,
            totals: { ...stats.totals, delivered: 4, earnings: 136.4, distanceKm: 6.4, onlineMinutes: 95 },
          },
        },
      },
    )
    renderApp('/delivery')

    const offline = await screen.findByRole('region', { name: 'Offline' })
    expect(screen.getByRole('switch', { name: 'Online' })).toHaveAttribute('aria-checked', 'false')

    // Today's numbers come from the stats of today (India time); the period figures live in "Your performance".
    const today = screen.getByLabelText("Today's numbers")
    await waitFor(() => expect(today).toHaveTextContent('Delivered today4'))
    expect(today).toHaveTextContent('Earned today (estimate)₹136.40')
    expect(today).toHaveTextContent('Distance today6.4 km')
    expect(today).toHaveTextContent('Online today1 h 35 min')
    expect(api.callsTo('GET /delivery/stats').map((call) => call.params)).toContainEqual(presetRange('today'))

    const account = screen.getByRole('navigation', { name: 'Your account' })
    expect(within(account).getByRole('link', { name: 'Delivery history' })).toHaveAttribute('href', '/delivery/history')
    expect(within(account).getByRole('link', { name: 'Profile' })).toHaveAttribute('href', '/delivery/profile')
    const assistant = screen.getByRole('region', { name: 'AI delivery assistant' })
    expect(within(assistant).getByRole('link', { name: 'Assistant actions →' })).toHaveAttribute(
      'href',
      '/delivery/assistant',
    )

    await user.click(within(offline).getByRole('button', { name: 'Go online' }))
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Online' })).toHaveAttribute('aria-checked', 'true'))
    expect(api.callsTo('PUT /delivery/partners/me/availability')[0]?.body).toEqual({ online: true })
    expect(await screen.findByText(/No offers right now/)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Offline' })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Availability' })).toHaveTextContent('You are online')
  })

  it.each([
    {
      code: 'ACTIVE_DELIVERY',
      status: 409,
      online: true,
      message: 'Finish your delivery before going offline',
      description: /active delivery/,
    },
    {
      code: 'PARTNER_NOT_ACTIVE',
      status: 403,
      online: false,
      message: 'Your account is waiting for approval',
      description: /admin approves/,
    },
  ])('explains $status $code when switching', async ({ code, status, online, message, description }) => {
    fakeGeolocation()
    const user = userEvent.setup()
    setup(
      { status: partnerStatus({ online }), active: online ? deliveryView() : null },
      { 'PUT /delivery/partners/me/availability': problem(status, code) },
    )
    renderApp('/delivery')

    const toggle = await findOnlineSwitch()
    await user.click(toggle)

    const toast = (await screen.findByText(message)).closest('li')
    expect(toast).toHaveTextContent(description)
    expect(toggle).toHaveAttribute('aria-checked', String(online))
  })

  it('shows "waiting for approval" when the status endpoint answers 403 PARTNER_NOT_ACTIVE', async () => {
    setup({}, { 'GET /delivery/partners/me/status': problem(403, 'PARTNER_NOT_ACTIVE') })
    renderApp('/delivery')

    expect(await screen.findByText('Your account is waiting for approval')).toBeInTheDocument()
    expect(screen.queryByRole('switch', { name: 'Online' })).not.toBeInTheDocument()
  })
})

describe('Delivery partner app — location simulator', { timeout: 20_000 }, () => {
  it('stays folded into one line until opened', async () => {
    fakeGeolocation()
    const user = userEvent.setup()
    setup({ status: partnerStatus({ online: true }) })
    renderApp('/delivery')

    const simulator = await screen.findByRole('region', { name: 'Location simulator' })
    const unfold = within(simulator).getByRole('button', { name: /Location simulator/ })
    expect(unfold).toHaveAttribute('aria-expanded', 'false')
    expect(simulator).toHaveTextContent('Testing only')
    expect(within(simulator).queryByRole('switch', { name: 'Use simulated location' })).not.toBeInTheDocument()

    await user.click(unfold)
    expect(unfold).toHaveAttribute('aria-expanded', 'true')
    await user.click(within(simulator).getByRole('switch', { name: 'Use simulated location' }))
    expect(await within(simulator).findByText(/No position yet/)).toBeInTheDocument()
    expect(simulator).toHaveTextContent('On — your position comes from here, not from GPS.')
  })

  it('places the partner at the store, drives towards the customer in 2 s steps, and reports through the same reporter', async () => {
    const geo = fakeGeolocation()
    const user = userEvent.setup()
    const { api } = setup({ status: partnerStatus({ online: true }), active: pickedUpDelivery() })
    // Tests run as a development build, where the simulator exists; ?simulate=1 turns it on from the start.
    renderApp('/delivery?simulate=1')

    const simulator = await screen.findByRole('region', { name: 'Location simulator' })
    expect(within(simulator).getByRole('switch', { name: 'Use simulated location' })).toHaveAttribute(
      'aria-checked',
      'true',
    )
    expect(geo.geolocation.watchPosition).not.toHaveBeenCalled()

    await user.click(within(simulator).getByRole('button', { name: 'At the store' }))
    expect(within(simulator).getByText(/Simulated position:/)).toHaveTextContent('12.971600, 77.594600')
    await waitFor(() => expect(api.callsTo('PUT /delivery/partners/me/location')).toHaveLength(1))
    expect(api.callsTo('PUT /delivery/partners/me/location')[0]?.body).toEqual(STORE_POINT)
    expect(await screen.findByText(/Sharing your simulated location/)).toBeInTheDocument()

    await user.click(within(simulator).getByRole('button', { name: 'Drive to the customer' }))
    expect(await within(simulator).findByText(/step 1 of 20/, {}, { timeout: 4_000 })).toBeInTheDocument()
    const step1 = driveStep(STORE_POINT, HOME_POINT, 1)
    expect(within(simulator).getByText(/Simulated position:/)).toHaveTextContent(
      `${step1.latitude.toFixed(6)}, ${step1.longitude.toFixed(6)}`,
    )
    await user.click(within(simulator).getByRole('button', { name: 'Stop driving' }))

    // The step goes out once the 5 s window since the first report has passed.
    await waitFor(() => expect(api.callsTo('PUT /delivery/partners/me/location')).toHaveLength(2), { timeout: 6_000 })
    expect(api.callsTo('PUT /delivery/partners/me/location')[1]?.body).toEqual(step1)

    // A click on the map sets the position too.
    act(() => clickMap(12.99, 77.61))
    expect(within(simulator).getByText(/Simulated position:/)).toHaveTextContent('12.990000, 77.610000')
    // The map credits OpenStreetMap (the mocked tile layer shows the attribution text).
    expect(within(simulator).getByTestId('map-attribution')).toHaveTextContent('© OpenStreetMap contributors')
  })
})

describe('Delivery partner app — offers', { timeout: 20_000 }, () => {
  it('shows an offer with a countdown and accepts it; the delivery becomes active', async () => {
    fakeGeolocation()
    const user = userEvent.setup()
    const { api } = setup({ status: partnerStatus({ online: true, pendingOffers: 1 }), offers: [offerView()] })
    renderApp('/delivery')

    const card = await screen.findByRole('article', { name: 'Delivery offer from Green Basket' })
    expect(within(card).getByRole('timer', { name: 'Time left to respond' })).toHaveTextContent(/^0:2\d$/)
    expect(card).toHaveTextContent('To the store1.2 km')
    expect(card).toHaveTextContent('Trip to the customer3.4 km')
    expect(card).toHaveTextContent('Drop areaIndiranagar, Bengaluru')
    expect(card).toHaveTextContent('Items3 items')
    // ₹25 per delivery + ₹6 per km of the 3.4 km trip.
    expect(await within(card).findByText('₹45.40')).toBeInTheDocument()
    expect(card).toHaveTextContent('You earn (est.)₹45.40')
    expect(card).not.toHaveTextContent('Asha')
    expect(screen.getByRole('region', { name: 'Delivery offers' })).toHaveTextContent('1 waiting')

    await user.click(within(card).getByRole('button', { name: 'Accept' }))

    expect(await screen.findByText('Delivery accepted — head to Green Basket')).toBeInTheDocument()
    expect(api.callsTo('POST /delivery/offers/:id/accept')[0]?.pathParams.id).toBe('of1')
    const active = await screen.findByRole('region', { name: 'Active delivery' })
    expect(within(active).getByRole('heading', { name: 'Go to the store' })).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Delivery offer from Green Basket' })).not.toBeInTheDocument()
  })

  it('says "Another partner took it" on 409 OFFER_NOT_AVAILABLE and refreshes the offers', async () => {
    fakeGeolocation()
    const user = userEvent.setup()
    const { api, server } = setup(
      { status: partnerStatus({ online: true }), offers: [offerView()] },
      {
        'POST /delivery/offers/:id/accept': () => {
          server.offers = []
          return { status: 409, data: { ...problem(409, 'OFFER_NOT_AVAILABLE').data, currentStatus: 'WITHDRAWN' } }
        },
      },
    )
    renderApp('/delivery')

    const card = await screen.findByRole('article', { name: 'Delivery offer from Green Basket' })
    const reads = api.callsTo('GET /delivery/offers').length
    await user.click(within(card).getByRole('button', { name: 'Accept' }))

    const toast = (await screen.findByText('Another partner took it')).closest('li')
    expect(toast).toHaveTextContent('Your offers have been refreshed.')
    await waitFor(() => expect(api.callsTo('GET /delivery/offers').length).toBeGreaterThan(reads))
    expect(screen.queryByRole('article', { name: 'Delivery offer from Green Basket' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Active delivery' })).not.toBeInTheDocument()
  })

  it('rejects an offer', async () => {
    fakeGeolocation()
    const user = userEvent.setup()
    const { api } = setup({ status: partnerStatus({ online: true }), offers: [offerView()] })
    renderApp('/delivery')

    const card = await screen.findByRole('article', { name: 'Delivery offer from Green Basket' })
    await user.click(within(card).getByRole('button', { name: 'Reject' }))

    await waitFor(() =>
      expect(screen.queryByRole('article', { name: 'Delivery offer from Green Basket' })).not.toBeInTheDocument(),
    )
    expect(api.callsTo('POST /delivery/offers/:id/reject')[0]?.pathParams.id).toBe('of1')
    expect(screen.getByText(/No offers right now/)).toBeInTheDocument()
  })

  it('marks an offer expired when its countdown runs out and re-reads the offers', async () => {
    fakeGeolocation()
    const sse = mockEventStream()
    const { api, server } = setup({ status: partnerStatus({ online: true }) })
    renderApp('/delivery')

    // The offer arrives once the page is up, so its 1.5 s run out while the test watches.
    expect(await screen.findByText(/No offers right now/)).toBeInTheDocument()
    const stream = await sse.connection()
    server.offers = [offerView({ expiresAt: iso(1_500) })]
    stream.signal({ type: 'DELIVERY_OFFERS_CHANGED', data: { offerId: 'of1', deliveryId: 'd1' } })

    const card = await screen.findByRole('article', { name: 'Delivery offer from Green Basket' })
    const reads = api.callsTo('GET /delivery/offers').length
    expect(await within(card).findByText('Expired', {}, { timeout: 4_000 })).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Accept' })).toBeDisabled()
    await waitFor(() => expect(api.callsTo('GET /delivery/offers').length).toBeGreaterThan(reads))
  })

  it('shows a new offer from a live DELIVERY_OFFER notification', async () => {
    fakeGeolocation()
    const sse = mockEventStream()
    const { server } = setup({ status: partnerStatus({ online: true }) })
    renderApp('/delivery')

    expect(await screen.findByText(/No offers right now/)).toBeInTheDocument()
    const stream = await sse.connection()
    server.offers = [offerView({ offerId: 'of9', store: { name: 'Daily Mart', city: 'Bengaluru', ...STORE_POINT } })]
    stream.notification(deliveryOfferNotification({ title: 'New delivery from Daily Mart' }))

    expect(await screen.findByRole('article', { name: 'Delivery offer from Daily Mart' })).toBeInTheDocument()
  })
})

describe('Delivery partner app — active delivery', { timeout: 20_000 }, () => {
  it('keeps "Picked up" disabled until the store marks the order ready (PICKUP_READY), then delivers it', async () => {
    fakeGeolocation()
    const user = userEvent.setup()
    const sse = mockEventStream()
    const { api, server } = setup({ status: partnerStatus({ online: true }), active: deliveryView() })
    renderApp('/delivery')

    const active = await screen.findByRole('region', { name: 'Active delivery' })
    expect(within(active).getByRole('list', { name: 'Delivery steps' })).toHaveTextContent('Step 1: Go to the store')
    expect(within(active).getByText(/Being prepared · ready by/)).toBeInTheDocument()
    const pickUp = within(active).getByRole('button', { name: 'Picked up' })
    expect(pickUp).toBeDisabled()
    expect(pickUp).toHaveAccessibleDescription('You can confirm the pickup once the store marks the order ready.')
    expect(await within(active).findByTitle('Green Basket (store)')).toBeInTheDocument()
    expect(within(active).getByTitle('Asha Rao (customer)')).toBeInTheDocument()
    // Step 1: directions to the store, and the route store → customer on the map.
    expect(within(active).getByRole('link', { name: /Navigate to Green Basket/ })).toHaveAttribute(
      'href',
      `https://www.google.com/maps/dir/?api=1&destination=${STORE_POINT.latitude}%2C${STORE_POINT.longitude}`,
    )
    expect(within(active).getByTestId('map-route')).toHaveAttribute(
      'data-points',
      `${STORE_POINT.latitude},${STORE_POINT.longitude};${HOME_POINT.latitude},${HOME_POINT.longitude}`,
    )

    const stream = await sse.connection()
    server.active = deliveryView({ readyForPickup: true })
    stream.notification(
      notificationView({
        id: '0193a1b2-0000-7000-8000-0000000000d2',
        type: 'PICKUP_READY',
        category: 'DELIVERY',
        severity: 'INFO',
        title: 'Order GA-7K3F9Q2M is ready at Green Basket',
        link: '/delivery',
        data: { orderId: 'o1', orderNumber: 'GA-7K3F9Q2M', deliveryId: 'd1' },
      }),
    )
    expect(await within(active).findByText('Order ready')).toBeInTheDocument()
    await user.click(within(active).getByRole('button', { name: 'Picked up' }))

    expect(await within(active).findByRole('heading', { name: 'Deliver' })).toBeInTheDocument()
    expect(api.callsTo('POST /delivery/deliveries/:id/pickup')[0]?.pathParams.id).toBe('d1')
    expect(within(active).getByRole('link', { name: 'Call Asha Rao, +919876543210' })).toHaveAttribute(
      'href',
      'tel:+919876543210',
    )
    // Step 2: directions to the customer's drop point.
    expect(within(active).getByRole('link', { name: /Navigate to Asha/ })).toHaveAttribute(
      'href',
      `https://www.google.com/maps/dir/?api=1&destination=${HOME_POINT.latitude}%2C${HOME_POINT.longitude}`,
    )
    expect(active).toHaveTextContent('12 MG Road')
    expect(active).toHaveTextContent('Opposite the park')
    expect(active).toHaveTextContent('Note: Ring twice')

    await user.click(within(active).getByRole('button', { name: 'Delivered' }))
    expect(await screen.findByText('Order GA-7K3F9Q2M delivered')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Active delivery' })).not.toBeInTheDocument())
    expect(api.callsTo('POST /delivery/deliveries/:id/deliver')).toHaveLength(1)
  })

  it('explains 409 ORDER_NOT_READY and refreshes the delivery', async () => {
    fakeGeolocation()
    const user = userEvent.setup()
    const { api, server } = setup(
      { status: partnerStatus({ online: true }), active: deliveryView({ readyForPickup: true }) },
      {
        'POST /delivery/deliveries/:id/pickup': () => {
          server.active = deliveryView({ readyForPickup: false })
          return problem(409, 'ORDER_NOT_READY')
        },
      },
    )
    renderApp('/delivery')

    const active = await screen.findByRole('region', { name: 'Active delivery' })
    const reads = api.callsTo('GET /delivery/deliveries/active').length
    await user.click(within(active).getByRole('button', { name: 'Picked up' }))

    const toast = (await screen.findByText('Order GA-7K3F9Q2M is not ready yet')).closest('li')
    expect(toast).toHaveTextContent('The store has not marked it ready for pickup.')
    await waitFor(() => expect(api.callsTo('GET /delivery/deliveries/active').length).toBeGreaterThan(reads))
    expect(await within(active).findByRole('button', { name: 'Picked up' })).toBeDisabled()
  })

  it('records "Couldn’t deliver" with a reason, requiring a note for Other', async () => {
    fakeGeolocation()
    const user = userEvent.setup()
    const { api } = setup({ status: partnerStatus({ online: true }), active: pickedUpDelivery() })
    renderApp('/delivery')

    const active = await screen.findByRole('region', { name: 'Active delivery' })
    await user.click(within(active).getByRole('button', { name: 'Couldn’t deliver' }))
    const dialog = await screen.findByRole('dialog', { name: 'Couldn’t deliver order GA-7K3F9Q2M?' })

    await user.click(within(dialog).getByRole('button', { name: 'Mark as not delivered' }))
    expect(await within(dialog).findByText('Choose a reason.')).toBeInTheDocument()

    await user.selectOptions(within(dialog).getByLabelText(/What happened/), 'OTHER')
    await user.click(within(dialog).getByRole('button', { name: 'Mark as not delivered' }))
    expect(await within(dialog).findByText(/Add a note/)).toBeInTheDocument()
    expect(api.callsTo('POST /delivery/deliveries/:id/fail')).toHaveLength(0)

    await user.type(within(dialog).getByLabelText(/Note/), 'Gate locked, building closed')
    await user.click(within(dialog).getByRole('button', { name: 'Mark as not delivered' }))

    expect(await screen.findByText('Order GA-7K3F9Q2M marked as not delivered')).toBeInTheDocument()
    expect(api.callsTo('POST /delivery/deliveries/:id/fail')[0]?.body).toEqual({
      reason: 'OTHER',
      note: 'Gate locked, building closed',
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('sends a reason without a note when none is needed', async () => {
    fakeGeolocation()
    const user = userEvent.setup()
    const { api } = setup({ status: partnerStatus({ online: true }), active: pickedUpDelivery() })
    renderApp('/delivery')

    await user.click(
      within(await screen.findByRole('region', { name: 'Active delivery' })).getByRole('button', {
        name: 'Couldn’t deliver',
      }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'Couldn’t deliver order GA-7K3F9Q2M?' })
    await user.selectOptions(within(dialog).getByLabelText(/What happened/), 'CUSTOMER_UNREACHABLE')
    await user.click(within(dialog).getByRole('button', { name: 'Mark as not delivered' }))

    await waitFor(() =>
      expect(api.callsTo('POST /delivery/deliveries/:id/fail')[0]?.body).toEqual({ reason: 'CUSTOMER_UNREACHABLE' }),
    )
  })

  it('tells the partner clearly when the delivery is cancelled (DELIVERY_CANCELLED)', async () => {
    fakeGeolocation()
    const user = userEvent.setup()
    const sse = mockEventStream()
    const { server } = setup({ status: partnerStatus({ online: true }), active: deliveryView() })
    renderApp('/delivery')

    await screen.findByRole('region', { name: 'Active delivery' })
    const stream = await sse.connection()
    server.active = null
    stream.notification(
      notificationView({
        id: '0193a1b2-0000-7000-8000-0000000000d3',
        type: 'DELIVERY_CANCELLED',
        category: 'DELIVERY',
        severity: 'WARNING',
        title: 'Order GA-7K3F9Q2M was cancelled',
        body: 'Do not pick it up.',
        link: '/delivery',
        data: { orderId: 'o1', orderNumber: 'GA-7K3F9Q2M', deliveryId: 'd1' },
      }),
    )

    const alert = (await screen.findAllByText('Order GA-7K3F9Q2M was cancelled'))
      .map((element) => element.closest('[role="alert"]'))
      .find((element) => element !== null)
    expect(alert).toHaveTextContent('Do not pick it up.')
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Active delivery' })).not.toBeInTheDocument())
    await user.click(within(alert).getByRole('button', { name: 'OK' }))
    expect(alert).not.toBeInTheDocument()
  })

  it('says so when the active delivery itself is CANCELLED', async () => {
    fakeGeolocation()
    setup({ status: partnerStatus({ online: true }), active: deliveryView({ status: 'CANCELLED' }) })
    renderApp('/delivery')

    const active = await screen.findByRole('region', { name: 'Active delivery' })
    expect(within(active).getByRole('alert')).toHaveTextContent('Order GA-7K3F9Q2M was cancelled')
    expect(within(active).queryByRole('button', { name: 'Picked up' })).not.toBeInTheDocument()
  })
})

describe('Delivery history', { timeout: 20_000 }, () => {
  it('shows the summary tiles and the finished deliveries by day, page by page', async () => {
    const user = userEvent.setup()
    signInAs(['DELIVERY_PARTNER'])
    const weekStats = partnerStats()
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /delivery/history/summary': {
        status: 200,
        data: { deliveredToday: 3, deliveredThisWeek: 17, failedThisWeek: 1 },
      },
      'GET /delivery/stats': { status: 200, data: { ...weekStats, totals: { ...weekStats.totals, distanceKm: 8.6 } } },
      'GET /delivery/history': (request) => ({
        status: 200,
        data: {
          content: [
            {
              deliveryId: request.params.page === 1 ? 'd30' : 'd1',
              orderNumber: request.params.page === 1 ? 'GA-OLDER030' : 'GA-7K3F9Q2M',
              status: request.params.page === 1 ? 'FAILED' : 'DELIVERED',
              store: 'Green Basket',
              dropArea: 'Indiranagar, Bengaluru',
              tripKm: 3.4,
              pickedUpAt: '2026-11-11T10:10:00Z',
              finishedAt: '2026-11-11T10:25:00Z',
            },
          ],
          page: Number(request.params.page),
          size: 20,
          totalElements: 21,
          totalPages: 2,
        },
      }),
    })
    const { router } = renderApp('/delivery/history')

    const tiles = await screen.findByRole('region', { name: 'Delivery summary' })
    expect(await within(tiles).findByText('17')).toBeInTheDocument()
    expect(tiles).toHaveTextContent('Delivered today3')
    expect(tiles).toHaveTextContent('Not delivered this week1')
    // 17 delivered of 18 finished trips; the distance comes from the stats of the same 7 days.
    expect(tiles).toHaveTextContent('Success rate this week94%17 of 18 trips delivered')
    expect(await within(tiles).findByText('8.6 km ridden')).toBeInTheDocument()
    expect(api.callsTo('GET /delivery/stats')[0]?.params).toMatchObject({
      from: expect.any(String),
      to: expect.any(String),
    })

    const list = await screen.findByRole('list', { name: 'Your deliveries' })
    // Grouped by day: the trips of 11 Nov under their own heading.
    const day = within(list).getByRole('list', { name: /11 Nov/ })
    expect(day).toHaveTextContent('Green Basket')
    expect(day).toHaveTextContent('GA-7K3F9Q2M')
    expect(day).toHaveTextContent('Delivered')
    expect(day).toHaveTextContent('Indiranagar, Bengaluru')
    expect(day).toHaveTextContent('3.4 km · 15 min')
    expect(api.callsTo('GET /delivery/history')[0]?.params).toEqual({ page: 0, size: 20 })

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText('GA-OLDER030')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Your deliveries' })).toHaveTextContent('Not delivered')
    expect(new URLSearchParams(router.state.location.search).get('page')).toBe('2')

    const nav = screen.getAllByRole('navigation', { name: 'Main navigation' })[0]
    expect(within(nav).getByRole('link', { name: 'History' })).toHaveAttribute('aria-current', 'page')
  })

  it('welcomes a new partner with no trips yet', async () => {
    signInAs(['DELIVERY_PARTNER'])
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /delivery/history/summary': {
        status: 200,
        data: { deliveredToday: 0, deliveredThisWeek: 0, failedThisWeek: 0 },
      },
      'GET /delivery/stats': { status: 200, data: partnerStats() },
      'GET /delivery/history': {
        status: 200,
        data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
      },
    })
    renderApp('/delivery/history')

    expect(await screen.findByRole('heading', { name: 'No trips yet' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go online and take an order' })).toHaveAttribute('href', '/delivery')
    const tiles = screen.getByRole('region', { name: 'Delivery summary' })
    expect(await within(tiles).findByText('No finished trips yet')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Your deliveries' })).not.toBeInTheDocument()
  })

  it('filters the trips by outcome and period (India-time days) in the URL, for the list and the export; says when nothing matches', async () => {
    const user = userEvent.setup()
    URL.createObjectURL = vi.fn(() => 'blob:export')
    URL.revokeObjectURL = vi.fn()
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    signInAs(['DELIVERY_PARTNER'])
    const delivered = {
      deliveryId: 'd1',
      orderNumber: 'GA-7K3F9Q2M',
      status: 'DELIVERED',
      store: 'Green Basket',
      dropArea: 'Adyar, Chennai',
      tripKm: 3.4,
      pickedUpAt: '2026-11-11T10:10:00Z',
      finishedAt: '2026-11-11T10:25:00Z',
    }
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /delivery/history/summary': {
        status: 200,
        data: { deliveredToday: 3, deliveredThisWeek: 17, failedThisWeek: 1 },
      },
      'GET /delivery/stats': { status: 200, data: partnerStats() },
      'GET /delivery/history/export': {
        status: 200,
        data: '',
        headers: {
          'content-disposition': 'attachment; filename="my-deliveries-2026-11-17-1030.csv"',
          'x-export-rows': '1',
        },
      },
      // Nothing was "not delivered"; one delivered trip matches anything else.
      'GET /delivery/history': (request) => {
        const content = request.params.status === 'FAILED' ? [] : [delivered]
        return {
          status: 200,
          data: { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length },
        }
      },
    })
    const today = indiaDay()
    const { router } = renderApp('/delivery/history?status=DELIVERED&period=7d')

    const list = await screen.findByRole('list', { name: 'Your deliveries' })
    expect(list).toHaveTextContent('GA-7K3F9Q2M')
    expect(api.callsTo('GET /delivery/history')[0]?.params).toEqual({
      page: 0,
      size: 20,
      status: 'DELIVERED',
      from: shiftDay(today, -6),
      to: today,
    })
    const outcome = screen.getByRole('group', { name: 'Outcome' })
    const period = screen.getByRole('group', { name: 'Period' })
    expect(within(outcome).getByRole('button', { name: 'Delivered' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(period).getByRole('button', { name: 'Last 7 days' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText(/trip matches · newest first/)).toBeInTheDocument()
    // The tiles keep their own period whatever the filters.
    expect(screen.getByRole('region', { name: 'Delivery summary' })).toHaveTextContent(
      'Today and the last 7 days, India time',
    )

    // The export takes the list's filters.
    await user.click(screen.getByRole('button', { name: 'Export your deliveries' }))
    await user.click(screen.getByRole('menuitem', { name: 'CSV (.csv)' }))
    await waitFor(() => expect(api.callsTo('GET /delivery/history/export')).toHaveLength(1))
    expect(api.callsTo('GET /delivery/history/export')[0]?.params).toEqual({
      status: 'DELIVERED',
      from: shiftDay(today, -6),
      to: today,
      format: 'csv',
    })

    // Another outcome and period: back to the first page, in the URL.
    await user.click(within(outcome).getByRole('button', { name: 'Not delivered' }))
    await user.click(within(period).getByRole('button', { name: 'Today' }))
    expect(await screen.findByRole('heading', { name: 'No trips match these filters' })).toBeInTheDocument()
    expect(screen.getByText('No trips marked not delivered today. Try another outcome or period.')).toBeInTheDocument()
    expect(api.callsTo('GET /delivery/history').at(-1)?.params).toEqual({
      page: 0,
      size: 20,
      status: 'FAILED',
      from: today,
      to: today,
    })
    const search = new URLSearchParams(router.state.location.search)
    expect([search.get('status'), search.get('period'), search.get('page')]).toEqual(['FAILED', 'today', null])

    await user.click(screen.getByRole('button', { name: 'Show all trips' }))
    expect(await screen.findByRole('list', { name: 'Your deliveries' })).toBeInTheDocument()
    expect(router.state.location.search).toBe('')
    expect(api.callsTo('GET /delivery/history').at(-1)?.params).toEqual({ page: 0, size: 20 })
    expect(within(outcome).getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(period).getByRole('button', { name: 'All time' })).toHaveAttribute('aria-pressed', 'true')
    vi.restoreAllMocks()
  })
})
