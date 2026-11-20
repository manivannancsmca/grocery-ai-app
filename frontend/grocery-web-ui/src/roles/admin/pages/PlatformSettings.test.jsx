import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { formatDateTime } from '../../../shared/utils/format'
import { mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

/** The views of the six areas as the services answer them: a few overrides, the rest defaults. */
function views() {
  return {
    delivery: {
      area: 'delivery',
      values: { searchRadiiKm: [3, 6, 10], offersPerWave: 3, offerTimeLimitSeconds: 30, locationFreshnessSeconds: 60 },
      defaults: {
        searchRadiiKm: [3, 6, 10],
        offersPerWave: 2,
        offerTimeLimitSeconds: 30,
        locationFreshnessSeconds: 60,
      },
      overridden: ['offersPerWave'],
      updatedAt: '2026-11-17T05:12:00Z',
      updatedBy: 'Dev Ops',
      version: 2,
    },
    'delivery-fees': {
      area: 'delivery-fees',
      values: {
        groceryBaseFee: 25,
        groceryIncludedKm: 3,
        groceryPerExtraKm: 8,
        groceryFreeAbove: 499,
        groceryMinimumOrder: 99,
        restaurantBaseFee: 30,
        restaurantIncludedKm: 3,
        restaurantPerExtraKm: 10,
      },
      defaults: {
        groceryBaseFee: 25,
        groceryIncludedKm: 3,
        groceryPerExtraKm: 8,
        groceryFreeAbove: 499,
        groceryMinimumOrder: 99,
        restaurantBaseFee: 30,
        restaurantIncludedKm: 3,
        restaurantPerExtraKm: 10,
      },
      overridden: [],
      updatedAt: null,
      updatedBy: null,
      version: 0,
    },
    'grocery-stores': {
      area: 'grocery-stores',
      values: { acceptanceTimeMinutes: 0.5 },
      defaults: { acceptanceTimeMinutes: 0.5 },
      overridden: [],
      updatedAt: null,
      updatedBy: null,
      version: 0,
    },
    restaurants: {
      area: 'restaurants',
      values: { acceptanceTimeMinutes: 5 },
      defaults: { acceptanceTimeMinutes: 5 },
      overridden: [],
      updatedAt: null,
      updatedBy: null,
      version: 0,
    },
    returns: {
      area: 'returns',
      values: { returnWindowHours: 72, storeDecisionHours: 24 },
      defaults: { returnWindowHours: 48, storeDecisionHours: 24 },
      overridden: ['returnWindowHours'],
      updatedAt: '2026-11-16T09:30:00Z',
      updatedBy: 'Super Admin',
      version: 1,
    },
    reviews: {
      area: 'reviews',
      values: { reviewWindowDays: 30 },
      defaults: { reviewWindowDays: 30 },
      overridden: [],
      updatedAt: null,
      updatedBy: null,
      version: 0,
    },
  }
}

/** The server's answer to a valid PUT: the stored overrides, the defaults elsewhere, one version up. */
function saved(view, update) {
  const values = { ...view.values }
  const overridden = []
  for (const [key, value] of Object.entries(update.values)) {
    if (value === null) values[key] = view.defaults[key] ?? 0
    else {
      values[key] = value
      overridden.push(key)
    }
  }
  return {
    ...view,
    values,
    overridden,
    version: view.version + 1,
    updatedAt: '2026-11-17T06:00:00Z',
    updatedBy: 'Dev Ops (you)',
  }
}

function setup(roles, routes = {}, history = {}) {
  signInAs(roles, { id: 'admin-1', fullName: 'Dev Ops' })
  const server = views()
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    ...routes,
    // Newest first, paged like the services' PageResponse.
    'GET /admin/settings/:area/history': (request) => {
      const changes = history[request.pathParams.area ?? ''] ?? []
      const page = Number(request.params.page ?? 0)
      const size = Number(request.params.size ?? 20)
      return {
        status: 200,
        data: {
          content: changes.slice(page * size, (page + 1) * size),
          page,
          size,
          totalElements: changes.length,
          totalPages: Math.ceil(changes.length / size),
        },
      }
    },
    'GET /admin/settings/:area': (request) => {
      const view = server[request.pathParams.area ?? '']
      return view ? { status: 200, data: view } : problem(404, 'NOT_FOUND')
    },
    'PUT /admin/settings/:area': (request) => {
      const area = request.pathParams.area ?? ''
      const view = server[area]
      if (!view) return problem(404, 'NOT_FOUND')
      const update = request.body
      if (update.expectedVersion !== view.version)
        return problem(409, 'SETTINGS_CHANGED', 'The settings were changed by someone else.')
      server[area] = saved(view, update)
      return { status: 200, data: server[area], delayMs: 150 }
    },
  })
  return { api, server }
}

const card = (name) => screen.findByRole('form', { name })

describe('Platform settings', { timeout: 30_000 }, () => {
  it('lists every area with its values, defaults, changed markers and last change; linked from the admin menu', async () => {
    setup(['OPS_ADMIN'])
    renderApp('/admin/settings')

    expect(await screen.findByRole('heading', { name: 'Platform settings' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Platform settings' })[0]).toHaveAttribute('href', '/admin/settings')

    const delivery = await card('Delivery partner search')
    expect(within(delivery).getByLabelText('Search radii')).toHaveValue('3, 6, 10')
    expect(within(delivery).getByLabelText('Partners offered per wave')).toHaveValue('3')
    expect(within(delivery).getByLabelText('Partners offered per wave')).toHaveAccessibleDescription(
      'Default: 2 partners · The nearest free partners get the offer at the same time.',
    )
    expect(within(delivery).getAllByText('Changed')).toHaveLength(1)
    expect(
      within(delivery).getByRole('button', { name: 'Reset Partners offered per wave to the default' }),
    ).toBeInTheDocument()
    expect(delivery).toHaveTextContent('Last changed by Dev Ops')
    expect(delivery).toHaveTextContent('Default: 30 s')

    const returns = await card('Returns')
    expect(within(returns).getByLabelText('Return window after delivery')).toHaveValue('72')
    expect(returns).toHaveTextContent('Default: 48 h')
    expect(returns).toHaveTextContent('Last changed by Super Admin')

    const fees = await card('Delivery fees')
    expect(within(fees).getByLabelText(/^Grocery minimum order$/i)).toHaveValue('99')
    expect(within(fees).getByLabelText(/^Restaurant base fee$/i)).toHaveValue('30')
    expect(fees).toHaveTextContent('Default: ₹499')
    expect(fees).toHaveTextContent('Never changed — using the defaults')
    expect(fees).toHaveTextContent(
      'The road factor and the maximum delivery distance stay in the server configuration.',
    )
    expect(within(fees).queryByText('Changed')).not.toBeInTheDocument()

    // A default that is not a whole number of minutes (a short test configuration) is shown as it is.
    const grocery = await card('Grocery stores')
    expect(within(grocery).getByLabelText('Time to accept an order')).toHaveValue('0.5')
    expect(grocery).toHaveTextContent('Default: 0.5 min')
    expect(within(grocery).getByRole('button', { name: 'Save changes' })).toBeDisabled()

    expect(await card('Restaurants')).toBeInTheDocument()
    expect(await card('Reviews')).toHaveTextContent('Default: 30 days')
  })

  it('saves one card: edits as numbers, untouched defaults as null, kept overrides as values, with the expected version', async () => {
    const user = userEvent.setup()
    const { api } = setup(['SUPER_ADMIN'])
    renderApp('/admin/settings')

    const delivery = await card('Delivery partner search')
    const save = within(delivery).getByRole('button', { name: 'Save changes' })
    expect(save).toBeDisabled()

    const ttl = within(delivery).getByLabelText('Time to accept an offer')
    await user.clear(ttl)
    await user.type(ttl, '45')
    expect(within(delivery).getAllByText('Unsaved')).toHaveLength(1)
    expect(within(delivery).getAllByText('Changed')).toHaveLength(2)
    await user.click(save)

    // Optimistic: the card already counts the new override while the request is on its way.
    expect(await within(delivery).findByRole('button', { name: 'Saving…' })).toBeDisabled()
    expect(within(delivery).getByText('2 custom values')).toBeInTheDocument()
    expect(within(delivery).queryByText('Unsaved')).not.toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('PUT /admin/settings/:area')).toHaveLength(1))
    expect(api.callsTo('PUT /admin/settings/:area')[0]?.path).toBe('/admin/settings/delivery')
    expect(api.callsTo('PUT /admin/settings/:area')[0]?.body).toEqual({
      values: { searchRadiiKm: null, offersPerWave: 3, offerTimeLimitSeconds: 45, locationFreshnessSeconds: null },
      expectedVersion: 2,
    })
    expect(await screen.findByText('Delivery partner search: settings saved')).toBeInTheDocument()
    await waitFor(() => expect(delivery).toHaveTextContent('Last changed by Dev Ops (you)'))
    expect(within(delivery).getByLabelText('Time to accept an offer')).toHaveValue('45')
    expect(within(delivery).queryByText('Unsaved')).not.toBeInTheDocument()

    // The next save starts from the new version.
    const radii = within(delivery).getByLabelText('Search radii')
    await user.clear(radii)
    await user.type(radii, '2, 4, 8')
    await user.click(within(delivery).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(api.callsTo('PUT /admin/settings/:area')).toHaveLength(2))
    expect(api.callsTo('PUT /admin/settings/:area')[1]?.body).toEqual({
      values: { searchRadiiKm: [2, 4, 8], offersPerWave: 3, offerTimeLimitSeconds: 45, locationFreshnessSeconds: null },
      expectedVersion: 3,
    })
  })

  it('resets an override to the default (sent as null); undo brings the saved value back', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'])
    renderApp('/admin/settings')

    const returns = await card('Returns')
    await user.click(within(returns).getByRole('button', { name: 'Reset Return window after delivery to the default' }))
    expect(within(returns).getByLabelText('Return window after delivery')).toHaveValue('48')
    expect(within(returns).queryByText('Changed')).not.toBeInTheDocument()

    await user.click(within(returns).getByRole('button', { name: 'Undo changes' }))
    expect(within(returns).getByLabelText('Return window after delivery')).toHaveValue('72')

    await user.click(within(returns).getByRole('button', { name: 'Reset Return window after delivery to the default' }))
    await user.click(within(returns).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(api.callsTo('PUT /admin/settings/:area')).toHaveLength(1))
    expect(api.callsTo('PUT /admin/settings/:area')[0]?.body).toEqual({
      values: { returnWindowHours: null, storeDecisionHours: null },
      expectedVersion: 1,
    })
    expect(await within(returns).findByText('All defaults')).toBeInTheDocument()
  })

  it('a stale version: says someone else changed the settings, keeps the edits until reload, then shows theirs', async () => {
    const user = userEvent.setup()
    const { api, server } = setup(['OPS_ADMIN'])
    renderApp('/admin/settings')

    const reviews = await card('Reviews')
    // Another admin saves meanwhile.
    server.reviews = {
      ...server.reviews,
      values: { reviewWindowDays: 14 },
      overridden: ['reviewWindowDays'],
      version: 1,
      updatedBy: 'Super Admin',
      updatedAt: '2026-11-17T05:59:00Z',
    }

    const reviewWindow = within(reviews).getByLabelText('Review window after delivery')
    await user.clear(reviewWindow)
    await user.type(reviewWindow, '45')
    await user.click(within(reviews).getByRole('button', { name: 'Save changes' }))

    const alert = await within(reviews).findByRole('alert')
    expect(alert).toHaveTextContent('Someone else changed these settings — reload')
    expect(api.callsTo('PUT /admin/settings/:area')[0]?.body).toEqual({
      values: { reviewWindowDays: 45 },
      expectedVersion: 0,
    })
    expect(within(reviews).getByLabelText('Review window after delivery')).toHaveValue('45')
    expect(within(reviews).getByRole('button', { name: 'Save changes' })).toBeDisabled()

    await user.click(within(alert).getByRole('button', { name: 'Reload' }))
    await waitFor(() => expect(within(reviews).getByLabelText('Review window after delivery')).toHaveValue('14'))
    expect(reviews).toHaveTextContent('Last changed by Super Admin')
    expect(within(reviews).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows the service’s field errors (400) under the fields', async () => {
    const user = userEvent.setup()
    setup(['OPS_ADMIN'], {
      'PUT /admin/settings/delivery': problem(400, 'VALIDATION_FAILED', 'Request validation failed.', [
        { field: 'values.locationFreshnessSeconds', message: 'must be at least the offer time limit' },
      ]),
    })
    renderApp('/admin/settings')

    const delivery = await card('Delivery partner search')
    const freshness = within(delivery).getByLabelText('Location freshness')
    await user.clear(freshness)
    await user.type(freshness, '40')
    await user.click(within(delivery).getByRole('button', { name: 'Save changes' }))

    expect(await within(delivery).findByText('must be at least the offer time limit')).toBeInTheDocument()
    expect(within(delivery).getByRole('alert')).toHaveTextContent('Some values were not accepted')
    expect(within(delivery).getByLabelText('Location freshness')).toHaveValue('40')
    expect(within(delivery).getByLabelText('Location freshness')).toHaveAttribute('aria-invalid', 'true')
  })

  it('checks the bounds before sending: out-of-range numbers and radii that do not grow', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'])
    renderApp('/admin/settings')

    const delivery = await card('Delivery partner search')
    const radii = within(delivery).getByLabelText('Search radii')
    await user.clear(radii)
    await user.type(radii, '6, 3')
    expect(within(delivery).getByText('Each radius must be larger than the one before')).toBeInTheDocument()
    const wave = within(delivery).getByLabelText('Partners offered per wave')
    await user.clear(wave)
    await user.type(wave, '11')
    expect(within(delivery).getByText('Must be between 1 and 10')).toBeInTheDocument()
    expect(within(delivery).getByRole('button', { name: 'Save changes' })).toBeDisabled()

    const fees = await card('Delivery fees')
    const minimum = within(fees).getByLabelText(/^Grocery minimum order$/i)
    await user.clear(minimum)
    await user.type(minimum, '99.999')
    expect(within(fees).getByText('Enter an amount in rupees, with at most two decimals')).toBeInTheDocument()
    expect(within(fees).getByRole('button', { name: 'Save changes' })).toBeDisabled()

    expect(api.callsTo('PUT /admin/settings/:area')).toHaveLength(0)
  })

  it('is read-only for admins who may not change settings', async () => {
    setup(['SUPPORT'])
    renderApp('/admin/settings')

    const delivery = await card('Delivery partner search')
    expect(
      screen.getByText('Only super admins and operations admins can change platform settings.'),
    ).toBeInTheDocument()
    expect(within(delivery).getByLabelText('Partners offered per wave')).toBeDisabled()
    expect(within(delivery).queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
    expect(within(delivery).queryByRole('button', { name: /^Reset/ })).not.toBeInTheDocument()
    expect(within(delivery).getByText('Changed')).toBeInTheDocument()
    const fees = await card('Delivery fees')
    expect(within(fees).getByLabelText(/^Grocery base fee$/i)).toBeDisabled()
  })

  it('one area failing to load leaves the others working', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'], {
      'GET /admin/settings/reviews': problem(503, 'SERVICE_UNAVAILABLE', 'review-service is not available.'),
    })
    renderApp('/admin/settings')

    expect(await screen.findByText('These settings could not be loaded')).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Reviews' })).not.toBeInTheDocument()

    const restaurants = await card('Restaurants')
    const time = within(restaurants).getByLabelText('Time to accept an order')
    await user.clear(time)
    await user.type(time, '8')
    await user.click(within(restaurants).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(api.callsTo('PUT /admin/settings/:area')).toHaveLength(1))
    expect(api.callsTo('PUT /admin/settings/:area')[0]?.body).toEqual({
      values: { acceptanceTimeMinutes: 8 },
      expectedVersion: 0,
    })
  })

  it('shows an area’s history in a drawer: who and when, each field with its unit, old → new, Default for null; page by page', async () => {
    const user = userEvent.setup()
    const by = (id, name) => ({ id, name })
    // Newest first, as the service sends it: one save's rows in the reverse order of writing.
    const deliveryHistory = [
      {
        field: 'offerTimeLimitSeconds',
        oldValue: null,
        newValue: 45,
        changedAt: '2026-11-17T05:12:00Z',
        changedBy: by('admin-1', 'Dev Ops'),
      },
      {
        field: 'offersPerWave',
        oldValue: 2,
        newValue: 3,
        changedAt: '2026-11-17T05:12:00Z',
        changedBy: by('admin-1', 'Dev Ops'),
      },
      {
        field: 'searchRadiiKm',
        oldValue: null,
        newValue: [2, 4, 8],
        changedAt: '2026-11-16T09:30:00Z',
        changedBy: by('admin-2', 'Super Admin'),
      },
      ...Array.from({ length: 17 }, (_, minute) => ({
        field: 'offerTimeLimitSeconds',
        oldValue: 30,
        newValue: 40,
        changedAt: new Date(Date.UTC(2026, 10, 12, 8, 59 - minute)).toISOString(),
        changedBy: by('admin-3', 'Ops Night Shift'),
      })),
      {
        field: 'locationFreshnessSeconds',
        oldValue: 60,
        newValue: null,
        changedAt: '2026-11-01T04:00:00Z',
        changedBy: by('admin-4', null),
      },
    ]
    const { api } = setup(['OPS_ADMIN'], {}, { delivery: deliveryHistory })
    renderApp('/admin/settings')

    const delivery = await card('Delivery partner search')
    await user.click(within(delivery).getByRole('button', { name: 'Delivery partner search history' }))
    const drawer = await screen.findByRole('dialog', { name: 'Delivery partner search: change history' })
    const devOps = await within(drawer).findByRole('list', { name: 'Changed by Dev Ops' })
    expect(api.callsTo('GET /admin/settings/:area/history')[0]).toMatchObject({
      path: '/admin/settings/delivery/history',
      params: { page: 0, size: 20 },
    })

    // One save, its fields in the card's order.
    const [wave, time] = within(devOps).getAllByRole('listitem')
    expect(wave).toHaveTextContent('Partners offered per wave')
    expect(wave).toHaveTextContent('from 2 partners to 3 partners')
    expect(time).toHaveTextContent('Time to accept an offer')
    expect(time).toHaveTextContent('from Default to 45 s')
    expect(within(drawer).getByText(formatDateTime('2026-11-17T05:12:00Z'))).toHaveAttribute(
      'dateTime',
      '2026-11-17T05:12:00Z',
    )
    const superAdmin = within(drawer).getByRole('list', { name: 'Changed by Super Admin' })
    expect(superAdmin).toHaveTextContent('Search radii')
    expect(superAdmin).toHaveTextContent('from Default to 2, 4, 8 km')
    expect(within(drawer).getAllByRole('list', { name: 'Changed by Ops Night Shift' })).toHaveLength(17)

    await user.click(within(drawer).getByRole('button', { name: 'Next' }))
    const unnamed = await within(drawer).findByRole('list', { name: 'Changed by An admin' })
    expect(unnamed).toHaveTextContent('Location freshness')
    expect(unnamed).toHaveTextContent('from 60 s to Default')
    expect(api.callsTo('GET /admin/settings/:area/history').at(-1)?.params).toEqual({ page: 1, size: 20 })
    expect(within(drawer).queryByRole('list', { name: 'Changed by Dev Ops' })).not.toBeInTheDocument()

    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('history for read-only admins too, with an empty and an error state', async () => {
    const user = userEvent.setup()
    setup(['SUPPORT'], {
      'GET /admin/settings/returns/history': problem(503, 'SERVICE_UNAVAILABLE', 'order-service is not available.'),
    })
    renderApp('/admin/settings')

    await user.click(within(await card('Reviews')).getByRole('button', { name: 'Reviews history' }))
    const reviews = await screen.findByRole('dialog', { name: 'Reviews: change history' })
    expect(await within(reviews).findByText('No changes yet')).toBeInTheDocument()
    await user.click(within(reviews).getByRole('button', { name: 'Close' }))

    await user.click(within(await card('Returns')).getByRole('button', { name: 'Returns history' }))
    const returns = await screen.findByRole('dialog', { name: 'Returns: change history' })
    expect(await within(returns).findByText('The history could not be loaded')).toBeInTheDocument()
    expect(returns).toHaveTextContent('order-service is not available.')
    expect(within(returns).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
