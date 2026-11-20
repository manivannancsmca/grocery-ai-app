import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Address } from '../../../features/customer/api'
import type { RestaurantSearchResponse } from '../../../features/restaurant-discovery/api'
import { clickMap } from '../../../test/leafletMock'
import { mockApi } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const EMPTY_PAGE: RestaurantSearchResponse = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, facets: { cuisines: [] } }

function address(overrides: Partial<Address>): Address {
  return {
    id: 'a1',
    label: 'Home',
    recipientName: 'Asha Rao',
    phone: '+919876543210',
    line1: '12 MG Road',
    line2: null,
    landmark: null,
    city: 'Bengaluru',
    state: 'Karnataka',
    postalCode: '560001',
    country: 'IN',
    latitude: 12.97,
    longitude: 77.64,
    isDefault: false,
    createdAt: '2026-11-11T10:00:00Z',
    updatedAt: '2026-11-11T10:00:00Z',
    ...overrides,
  }
}

/** A browser whose location permission is refused (code 1, PERMISSION_DENIED). */
function denyGeolocation() {
  const getCurrentPosition = vi.fn((_ok: PositionCallback, fail?: PositionErrorCallback | null) =>
    fail?.({ code: 1, message: 'User denied Geolocation', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as GeolocationPositionError),
  )
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value: { getCurrentPosition, watchPosition: vi.fn(), clearWatch: vi.fn() } })
  return getCurrentPosition
}

afterEach(() => {
  Reflect.deleteProperty(navigator, 'geolocation')
})

describe('Restaurants page — choosing the delivery location', { timeout: 20_000 }, () => {
  it('when location access is refused, offers the saved addresses instead and searches near the chosen one', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const getCurrentPosition = denyGeolocation()
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /customers/me/addresses': {
        status: 200,
        data: [address({ id: 'a1', label: 'Home', isDefault: true }), address({ id: 'a2', label: 'Office', latitude: 12.935, longitude: 77.624 }), address({ id: 'a3', label: 'Gym', latitude: null, longitude: null })],
      },
      'GET /search/restaurants': { status: 200, data: EMPTY_PAGE },
    })
    const { router } = renderApp('/restaurants')

    // The default address with coordinates is the starting point.
    expect(await screen.findByText('Near Home (your default address)')).toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /search/restaurants').at(-1)?.params).toMatchObject({ lat: 12.97, lng: 77.64, radiusKm: 10 }))

    // "Use my location" is refused: the message points to the other ways.
    await user.click(screen.getByRole('button', { name: 'Use my location' }))
    expect(getCurrentPosition).toHaveBeenCalled()
    expect(await screen.findByRole('alert')).toHaveTextContent('Location permission was denied. Allow it in your browser, or use "Choose location"')

    await user.click(screen.getByRole('button', { name: 'Choose location' }))
    const dialog = await screen.findByRole('dialog', { name: 'Choose your delivery location' })
    const saved = within(dialog).getByRole('region', { name: 'Your saved addresses' })
    // An address without a map location cannot be used.
    expect(within(saved).getByRole('button', { name: /Gym/ })).toBeDisabled()
    await user.click(within(saved).getByRole('button', { name: /Office/ }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(await screen.findByText('Near Office')).toBeInTheDocument()
    const params = new URLSearchParams(router.state.location.search)
    expect([params.get('lat'), params.get('lng'), params.get('near')]).toEqual(['12.935', '77.624', 'Office'])
    await waitFor(() => expect(api.callsTo('GET /search/restaurants').at(-1)?.params).toMatchObject({ lat: 12.935, lng: 77.624 }))
  })

  it('lets anyone, guests too, pick the delivery point on the map', async () => {
    const user = userEvent.setup()
    const api = mockApi({ 'GET /search/restaurants': { status: 200, data: EMPTY_PAGE } })
    renderApp('/restaurants')

    expect(await screen.findByText('Everywhere — distances are not shown')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Choose location' }))
    const dialog = await screen.findByRole('dialog', { name: 'Choose your delivery location' })
    // Guests have no saved addresses to offer.
    expect(within(dialog).queryByRole('region', { name: 'Your saved addresses' })).not.toBeInTheDocument()
    const usePoint = within(dialog).getByRole('button', { name: 'Use this point' })
    expect(usePoint).toBeDisabled()

    await within(dialog).findByTestId('leaflet-map')
    act(() => clickMap(12.9611, 77.6387))
    expect(within(dialog).getByText('Chosen: 12.96110, 77.63870')).toBeInTheDocument()
    await user.click(usePoint)

    expect(await screen.findByText('Near the point you chose on the map')).toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /search/restaurants').at(-1)?.params).toMatchObject({ lat: 12.9611, lng: 77.6387, radiusKm: 10 }))
  })
})
