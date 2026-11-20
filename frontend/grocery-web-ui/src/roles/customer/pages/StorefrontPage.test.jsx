import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const categories = [
  {
    id: 'c-fv',
    name: 'Fruits & Vegetables',
    description: null,
    imageUrl: null,
    productCount: 12,
    children: [
      { id: 'c-veg', name: 'Fresh Vegetables', description: null, imageUrl: null, productCount: 8, children: [] },
    ],
  },
  { id: 'c-dairy', name: 'Dairy & Bakery', description: null, imageUrl: null, productCount: 1, children: [] },
]

const suggestions = {
  products: [
    { id: 'p1', name: 'Tomato', brandName: 'Farm Fresh', unitLabel: '500 g', primaryImageUrl: null },
    { id: 'p2', name: 'Tomato Ketchup', brandName: 'Kissan', unitLabel: '1 kg', primaryImageUrl: null },
  ],
  categories: [{ id: 'c-veg', name: 'Fresh Vegetables', path: ['Fruits & Vegetables', 'Fresh Vegetables'] }],
}

const tomato = {
  id: 'p1',
  name: 'Tomato',
  unitLabel: '500 g',
  foodType: 'VEG',
  primaryImageUrl: null,
  brand: { id: 'b1', name: 'Farm Fresh' },
  category: { id: 'c-veg', name: 'Fresh Vegetables' },
  vendor: { id: 'v1', name: 'Green Basket' },
  description: 'Farm-fresh red tomatoes',
  unitValue: 500,
  unitOfMeasure: 'G',
  sku: 'TOM-500',
  barcode: null,
  tags: ['organic'],
  attributes: [{ name: 'Origin', value: 'Nashik' }],
  imageUrls: [],
  categoryPath: [
    { id: 'c-fv', name: 'Fruits & Vegetables' },
    { id: 'c-veg', name: 'Fresh Vegetables' },
  ],
  publishedAt: '2026-11-11T10:15:30Z',
  updatedAt: '2026-11-11T10:15:30Z',
}

const emptySearch = {
  content: [],
  page: 0,
  size: 24,
  totalElements: 0,
  totalPages: 0,
  facets: { categories: [], brands: [], foodTypes: [] },
}

const restaurants = {
  content: [
    {
      id: 'r1',
      name: 'Spice Route',
      description: 'Biryani and kebabs',
      cuisines: ['NORTH_INDIAN', 'BIRYANI'],
      city: 'Bengaluru',
      locality: 'Indiranagar',
      distanceKm: 2.4,
      openNow: true,
      acceptingOrders: false,
      pureVeg: false,
      matchingItems: [],
    },
  ],
  page: 0,
  size: 6,
  totalElements: 1,
  totalPages: 1,
  facets: { cuisines: [] },
}

function storefrontApi(extra = {}) {
  return mockApi({
    'GET /health/identity': { status: 200, data: { status: 'UP' } },
    'GET /catalog/categories': { status: 200, data: categories },
    'GET /search/restaurants': { status: 200, data: restaurants },
    'GET /search/suggest': (request) =>
      String(request.params.q).toLowerCase().startsWith('tom') ||
      String(request.params.q).toLowerCase().startsWith('veg')
        ? { status: 200, data: suggestions }
        : { status: 200, data: { products: [], categories: [] } },
    'GET /catalog/products/:id': { status: 200, data: tomato },
    'GET /search/products': { status: 200, data: emptySearch },
    ...extra,
  })
}

describe('Storefront', () => {
  it('suggests products while typing and opens the highlighted one with the keyboard', async () => {
    const user = userEvent.setup()
    const api = storefrontApi()
    const { router } = renderApp('/')

    const box = await screen.findByRole('combobox', { name: 'Search groceries' })
    await user.type(box, 'tom')

    const listbox = await screen.findByRole('listbox', { name: 'Search suggestions' })
    expect(await within(listbox).findByRole('option', { name: /tomato ketchup/i })).toBeInTheDocument()
    expect(within(listbox).getByRole('option', { name: /fresh vegetables/i })).toBeInTheDocument()
    expect(box).toHaveAttribute('aria-expanded', 'true')
    // Debounced: one request for the final text, never for a single character.
    expect(api.callsTo('GET /search/suggest').map((call) => call.params.q)).toEqual(['tom'])
    expect(api.callsTo('GET /search/suggest')[0]?.headers.get('Authorization')).toBeFalsy()

    await user.keyboard('{ArrowDown}')
    const first = within(listbox).getByRole('option', { name: /^tomato farm fresh/i })
    expect(first).toHaveAttribute('aria-selected', 'true')
    expect(box).toHaveAttribute('aria-activedescendant', first.id)

    await user.keyboard('{Enter}')
    expect(await screen.findByRole('heading', { level: 1, name: 'Tomato' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/products/p1')
    expect(screen.getByText('Green Basket')).toBeInTheDocument()
    expect(screen.getByRole('rowheader', { name: 'Origin' })).toBeInTheDocument()
    expect(screen.getByText('Nashik')).toBeInTheDocument()
  })

  it('searches for the typed text on Enter, and opens a category suggestion on click', async () => {
    const user = userEvent.setup()
    storefrontApi()
    const { router } = renderApp('/')

    const box = await screen.findByRole('combobox', { name: 'Search groceries' })
    await user.type(box, 'tomatoes{Enter}')
    expect(await screen.findByRole('heading', { name: 'Results for “tomatoes”' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/search')
    expect(router.state.location.search).toBe('?q=tomatoes')

    await user.click(screen.getByRole('link', { name: 'FreshCart' }))
    const again = await screen.findByRole('combobox', { name: 'Search groceries' })
    await user.type(again, 'veg')
    await user.click(await screen.findByRole('option', { name: /fresh vegetables/i }))
    expect(await screen.findByRole('heading', { name: 'Fresh Vegetables' })).toBeInTheDocument()
    expect(router.state.location.search).toBe('?categoryId=c-veg')
  })

  it('closes the suggestions with Escape', async () => {
    const user = userEvent.setup()
    storefrontApi()
    renderApp('/')

    const box = await screen.findByRole('combobox', { name: 'Search groceries' })
    await user.type(box, 'tom')
    await screen.findByRole('listbox', { name: 'Search suggestions' })
    await user.keyboard('{Escape}')
    expect(box).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('shows the category grid and restaurants near the customer’s default address', async () => {
    signInAs(['CUSTOMER'])
    const home = {
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
      isDefault: true,
      createdAt: '2026-11-11T10:00:00Z',
      updatedAt: '2026-11-11T10:00:00Z',
    }
    const api = storefrontApi({ 'GET /customers/me/addresses': { status: 200, data: [home] } })
    renderApp('/')

    const grid = await screen.findByRole('region', { name: 'Shop by category' })
    expect(await within(grid).findByRole('link', { name: /fruits & vegetables 12 products/i })).toHaveAttribute(
      'href',
      '/search?categoryId=c-fv',
    )
    // Only top-level categories.
    expect(within(grid).queryByText('Fresh Vegetables')).not.toBeInTheDocument()

    const nearby = screen.getByRole('region', { name: 'Restaurants near you' })
    expect(await within(nearby).findByRole('link', { name: 'Spice Route' })).toHaveAttribute('href', '/restaurants/r1')
    expect(within(nearby).getByText(/near home \(your default address\)/i)).toBeInTheDocument()
    expect(within(nearby).getByText('Not accepting orders')).toBeInTheDocument()
    expect(within(nearby).getByText(/2\.4 km away/)).toBeInTheDocument()

    const searches = api.callsTo('GET /search/restaurants')
    expect(searches).toHaveLength(1)
    expect(searches[0]?.params).toMatchObject({ lat: 12.97, lng: 77.64, size: 6 })
  })
})
