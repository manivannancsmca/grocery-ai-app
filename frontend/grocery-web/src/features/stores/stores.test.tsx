import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AxiosAdapter } from 'axios'
import { describe, expect, it } from 'vitest'
import { httpClient } from '../../shared/api/httpClient'
import { cartView } from '../../test/cartFixtures'
import { type MockHandler, mockApi, problem } from '../../test/mockApi'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import type { ProductCard, ProductSearchResponse } from '../catalog/api'
import { searchProducts } from '../catalog/api'
import type { Address } from '../customer/api'
import type { RestaurantSearchResponse } from '../restaurant-discovery/api'
import type { NearbyStoresResponse, StoreCard } from './api'

const SELAIYUR = { latitude: 12.9202, longitude: 80.1445 }

function address(overrides: Partial<Address> = {}): Address {
  return {
    id: 'a1',
    label: 'Home',
    recipientName: 'Meenakshi Sundaram',
    phone: '+919840022009',
    line1: '14 Camp Road',
    line2: 'Selaiyur, East Tambaram',
    landmark: null,
    city: 'Chennai',
    state: 'Tamil Nadu',
    postalCode: '600073',
    country: 'IN',
    ...SELAIYUR,
    isDefault: true,
    createdAt: '2026-11-16T10:00:00Z',
    updatedAt: '2026-11-16T10:00:00Z',
    ...overrides,
  }
}

function store(overrides: Partial<StoreCard> = {}): StoreCard {
  return {
    id: 's1',
    name: 'Tambaram Super Bazaar',
    line1: '112 Shanmugam Road',
    area: 'West Tambaram',
    city: 'Chennai',
    postalCode: '600045',
    latitude: 12.9267,
    longitude: 80.1146,
    distanceKm: 4.3,
    deliversHere: true,
    ...overrides,
  }
}

function product(id: string, name: string, vendor: StoreCard): ProductCard {
  return {
    id,
    name,
    unitLabel: '1 L',
    foodType: 'VEG',
    primaryImageUrl: null,
    brand: { id: 'b1', name: 'Aavin' },
    category: { id: 'c1', name: 'Dairy Products' },
    vendor: { id: vendor.id, name: vendor.name },
    price: { mrp: 68, sellingPrice: 68, currency: 'INR', discountPercent: 0 },
    inStock: true,
  }
}

function page(content: ProductCard[]): ProductSearchResponse {
  return {
    content,
    page: 0,
    size: 24,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
    facets: { categories: [{ id: 'c1', name: 'Dairy Products', count: content.length }], brands: [], foodTypes: [] },
  }
}

const NO_RESTAURANTS: RestaurantSearchResponse = { content: [], page: 0, size: 6, totalElements: 0, totalPages: 0, facets: { cuisines: [] } }
const TAMBARAM = store()
const MEDAVAKKAM = store({ id: 's2', name: 'Medavakkam Fresh & Daily', area: 'Medavakkam', distanceKm: 7.8, latitude: 12.9206, longitude: 80.1845 })

function customerApi(nearby: NearbyStoresResponse, extra: Record<string, MockHandler> = {}) {
  signInAs(['CUSTOMER'])
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /customers/me/addresses': { status: 200, data: [address(), address({ id: 'a2', label: 'Office', line2: 'Guindy', latitude: 13.0108, longitude: 80.2143, isDefault: false })] },
    'GET /catalog/categories': { status: 200, data: [] },
    'GET /search/restaurants': { status: 200, data: NO_RESTAURANTS },
    'GET /stores/nearby': { status: 200, data: nearby },
    'GET /search/products': (request) => {
      const ids = request.params.vendorId
      const all = [product('p1', 'Aavin Full Cream Milk', TAMBARAM), product('p2', 'Aavin Fresh Curd', MEDAVAKKAM), product('p3', 'Ooty Carrot', store({ id: 's9', name: 'Anna Nagar Supermart' }))]
      const wanted = Array.isArray(ids) ? all.filter((item) => ids.includes(item.vendor.id)) : all
      return { status: 200, data: page(wanted) }
    },
    ...extra,
  })
}

describe('Grocery stores near the delivery location', () => {
  it('lists the stores that deliver to the default address and shows only their products, with a way to see every store', async () => {
    const user = userEvent.setup()
    const api = customerApi({ deliveryLimitKm: 15, content: [TAMBARAM, MEDAVAKKAM] })
    const { router } = renderApp('/customer/home')

    const strip = await screen.findByRole('list', { name: 'Grocery stores near you' })
    expect(within(strip).getAllByRole('listitem')).toHaveLength(2)
    expect(within(strip).getByRole('link', { name: 'Tambaram Super Bazaar' })).toHaveAttribute('href', '/stores/s1')
    expect(within(strip).getByText(/4\.3 km/)).toBeInTheDocument()
    expect(screen.getByText(/Delivering to Home · Selaiyur, East Tambaram · 2 stores/)).toBeInTheDocument()
    expect(api.callsTo('GET /stores/nearby')[0]?.params).toEqual({ lat: 12.9202, lng: 80.1445 })
    expect(api.callsTo('GET /stores/nearby')[0]?.headers.get('Authorization')).toBeFalsy()

    // "All products" waits for the stores, then asks only for theirs.
    const products = await screen.findByRole('list', { name: 'All products' })
    expect(within(products).getAllByRole('listitem')).toHaveLength(2)
    expect(within(products).queryByText('Ooty Carrot')).not.toBeInTheDocument()
    expect(api.callsTo('GET /search/products').every((call) => Array.isArray(call.params.vendorId))).toBe(true)
    expect(api.callsTo('GET /search/products')[0]?.params.vendorId).toEqual(['s1', 's2'])
    expect(screen.getByText(/that deliver to/)).toHaveTextContent('From 2 stores that deliver to Home · Selaiyur, East Tambaram (within 15 km)')
    // The product card says how far its store is.
    expect(within(products).getByText('Aavin Full Cream Milk').closest('article')).toHaveTextContent(/Sold by Tambaram Super Bazaar.*4.3 km away/)

    await user.click(screen.getByRole('button', { name: 'Show all stores' }))
    expect(new URLSearchParams(router.state.location.search).get('stores')).toBe('all')
    expect(await within(screen.getByRole('list', { name: 'All products' })).findByText('Ooty Carrot')).toBeInTheDocument()
    expect(api.callsTo('GET /search/products').at(-1)?.params.vendorId).toBeUndefined()

    await user.click(screen.getByRole('button', { name: 'Only stores that deliver here' }))
    expect(new URLSearchParams(router.state.location.search).get('stores')).toBeNull()
  })

  it('says when no store delivers to the address, without searching, and offers every store', async () => {
    const user = userEvent.setup()
    const api = customerApi({ deliveryLimitKm: 15, content: [] })
    renderApp('/search?q=milk')

    expect(await screen.findByRole('heading', { name: 'No store delivers to Home · Selaiyur, East Tambaram yet' })).toBeInTheDocument()
    expect(api.callsTo('GET /search/products')).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Search all stores' }))
    expect(await within(await screen.findByRole('list', { name: 'Products' })).findByText('Ooty Carrot')).toBeInTheDocument()
    expect(api.callsTo('GET /search/products').at(-1)?.params).toEqual({ q: 'milk', page: 0, size: 24 })
  })

  it('falls back to every store when the store list cannot be loaded, and says so', async () => {
    const api = customerApi({ deliveryLimitKm: 15, content: [] }, { 'GET /stores/nearby': problem(503, 'SERVICE_UNAVAILABLE') })
    renderApp('/search')

    expect(await screen.findByText('Stores near you could not be checked, so products from every store are shown.')).toBeInTheDocument()
    expect(await within(await screen.findByRole('list', { name: 'Products' })).findByText('Ooty Carrot')).toBeInTheDocument()
    expect(api.callsTo('GET /search/products').at(-1)?.params.vendorId).toBeUndefined()
  })

  it('changes the delivery location from the header, everywhere, and remembers it', async () => {
    const user = userEvent.setup()
    const api = customerApi({ deliveryLimitKm: 15, content: [TAMBARAM] })
    renderApp('/customer/home')

    const deliverTo = await screen.findByRole('button', { name: 'Deliver to Home · Selaiyur, East Tambaram. Change the delivery location' })
    await user.click(deliverTo)
    const dialog = await screen.findByRole('dialog', { name: 'Choose your delivery location' })
    await user.click(within(within(dialog).getByRole('region', { name: 'Your saved addresses' })).getByRole('button', { name: /Office/ }))

    expect(await screen.findByRole('button', { name: 'Deliver to Office · Guindy. Change the delivery location' })).toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /stores/nearby').at(-1)?.params).toEqual({ lat: 13.0108, lng: 80.2143 }))
    expect(await screen.findByText(/Delivering to Office · Guindy/)).toBeInTheDocument()
    expect(Object.entries(localStorage).some(([key, value]) => key.startsWith('freshcart.deliverTo.') && value.includes('Office'))).toBe(true)
  })
})

describe('Store page', () => {
  it('shows the store, its distance from the delivery location and only its products', async () => {
    const user = userEvent.setup()
    const api = customerApi({ deliveryLimitKm: 15, content: [TAMBARAM] }, { 'GET /stores/:id': { status: 200, data: TAMBARAM } })
    const { router } = renderApp('/stores/s1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tambaram Super Bazaar' })).toBeInTheDocument()
    expect(screen.getByText('112 Shanmugam Road, West Tambaram, Chennai, 600045')).toBeInTheDocument()
    expect(screen.getAllByText(/4\.3 km/).length).toBeGreaterThan(0)
    await waitFor(() => expect(api.callsTo('GET /stores/:id')[0]?.params).toEqual({ lat: 12.9202, lng: 80.1445 }))

    const products = await screen.findByRole('list', { name: 'Products of Tambaram Super Bazaar' })
    expect(within(products).getByText('Aavin Full Cream Milk')).toBeInTheDocument()
    expect(api.callsTo('GET /search/products').every((call) => call.params.vendorId === 's1')).toBe(true)

    await user.type(screen.getByRole('searchbox', { name: 'Search in Tambaram Super Bazaar' }), 'curd')
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('q')).toBe('curd'))
    await waitFor(() => expect(api.callsTo('GET /search/products').at(-1)?.params).toMatchObject({ vendorId: 's1', q: 'curd' }))
  })

  it('warns when the store is too far to deliver, and answers a closed store with a not-found page', async () => {
    customerApi(
      { deliveryLimitKm: 15, content: [] },
      {
        'GET /stores/:id': (request) =>
          request.pathParams.id === 'far'
            ? { status: 200, data: store({ id: 'far', name: 'Anna Nagar Supermart', area: 'Anna Nagar', distanceKm: 24.6, deliversHere: false }) }
            : problem(404, 'STORE_NOT_FOUND'),
      },
    )
    const { router } = renderApp('/stores/far')

    expect(await screen.findByText('Too far to deliver to Home · Selaiyur, East Tambaram')).toBeInTheDocument()
    expect(screen.getByText(/This store is 24\.6 km away and delivers up to 15 km/)).toBeInTheDocument()

    await router.navigate('/stores/closed')
    expect(await screen.findByRole('heading', { name: 'This store is not available' })).toBeInTheDocument()
  })
})

describe('searchProducts', () => {
  it('sends several stores as repeated vendorId keys and drops an empty list', async () => {
    const urls: string[] = []
    const adapter: AxiosAdapter = async (config) => {
      urls.push(httpClient.getUri(config))
      return { data: page([]), status: 200, statusText: 'OK', headers: {}, config }
    }
    httpClient.defaults.adapter = adapter

    await searchProducts({ q: 'milk', vendorId: ['s1', 's2'], size: 24 })
    await searchProducts({ vendorId: [] })

    const first = new URL(urls[0] ?? '', 'http://localhost')
    expect(first.pathname).toBe('/api/search/products')
    expect(first.searchParams.getAll('vendorId')).toEqual(['s1', 's2'])
    expect(first.search).not.toContain('%5B') // no vendorId[]=…
    expect(Object.fromEntries(first.searchParams)).toMatchObject({ q: 'milk', size: '24' })
    expect(urls[1]).toBe('/api/search/products')
  })
})
