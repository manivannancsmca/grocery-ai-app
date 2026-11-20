import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { cartView } from '../../test/cartFixtures'
import { mockApi } from '../../test/mockApi'
import { mockEventStream } from '../../test/mockEventStream'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import { diffProducts, productChange } from './productChanges'

const tomato = {
  id: 'p1',
  name: 'Tomato',
  unitLabel: '500 g',
  foodType: 'VEG',
  primaryImageUrl: null,
  brand: { id: 'b1', name: 'Farm Fresh' },
  category: { id: 'c-veg', name: 'Fresh Vegetables' },
  vendor: { id: 'v1', name: 'Green Basket' },
  price: { mrp: 60, sellingPrice: 49, currency: 'INR', discountPercent: 18 },
  inStock: true,
}

const milk = {
  ...tomato,
  id: 'p2',
  name: 'Toned Milk',
  unitLabel: '1 L',
  brand: { id: 'b2', name: 'Nandini' },
  category: { id: 'c-dairy', name: 'Dairy & Bakery' },
  price: { mrp: 56, sellingPrice: 56, currency: 'INR', discountPercent: 0 },
}

const categories = [
  { id: 'c-fv', name: 'Fruits & Vegetables', description: null, imageUrl: null, productCount: 1, children: [] },
  { id: 'c-dairy', name: 'Dairy & Bakery', description: null, imageUrl: null, productCount: 1, children: [] },
]

function searchPage(content) {
  return {
    status: 200,
    data: {
      content,
      page: 0,
      size: 24,
      totalElements: content.length,
      totalPages: 1,
      facets: { categories: [], brands: [], foodTypes: [] },
    },
  }
}

function homeApi(server) {
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /health/identity': { status: 200, data: { status: 'UP' } },
    'GET /catalog/categories': { status: 200, data: categories },
    'GET /search/restaurants': {
      status: 200,
      data: { content: [], page: 0, size: 6, totalElements: 0, totalPages: 0, facets: { cuisines: [] } },
    },
    'GET /customers/me/addresses': { status: 200, data: [] },
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /search/products': (request) =>
      searchPage(
        server.results.filter(
          (product) => !request.params.categoryId || product.category.id === request.params.categoryId,
        ),
      ),
  })
}

function tileOf(name) {
  return screen.getByRole('link', { name }).closest('article')
}

describe('Customer home', () => {
  it('sends a signed-in customer from the site root to /customer/home', async () => {
    signInAs(['CUSTOMER'])
    homeApi({ results: [tomato] })
    const { router } = renderApp('/')

    expect(await screen.findByRole('heading', { name: 'All products' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/customer/home')
    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/customer/home')
    // Phones: the bottom tab bar leads to the same home page.
    const tabs = screen.getByRole('navigation', { name: 'Quick navigation' })
    expect(within(tabs).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/customer/home')
    expect(within(tabs).getByRole('link', { name: 'Orders' })).toHaveAttribute('href', '/orders')
    expect(screen.getByRole('link', { name: 'FreshCart' })).toHaveAttribute('href', '/customer/home')
  })

  it('sends a guest who opens /customer/home to sign in, and keeps the public storefront at /', async () => {
    homeApi({ results: [tomato] })
    const { router } = renderApp('/customer/home')

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.state).toEqual({ from: '/customer/home' })
  })
})

describe('All products (live)', () => {
  it('lists every product with price and stock, and marks live stock and price changes without a reload', async () => {
    signInAs(['CUSTOMER'])
    const server = { results: [tomato, milk] }
    const api = homeApi(server)
    const sse = mockEventStream()
    renderApp('/customer/home')

    const list = await screen.findByRole('list', { name: 'All products' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByText(/2 products/)).toBeInTheDocument()
    expect(within(tileOf('Tomato')).getByText('₹49')).toBeInTheDocument()
    expect(within(tileOf('Tomato')).getByRole('button', { name: /add tomato/i })).toBeInTheDocument()
    // No text: the API's default ("newest") is not sent.
    expect(api.callsTo('GET /search/products')[0]?.params).toMatchObject({ page: 0, size: 24 })
    expect(api.callsTo('GET /search/products')[0]?.params).not.toHaveProperty('sort')

    const stream = await sse.connection()
    expect(await screen.findByText('Live')).toBeInTheDocument()
    expect(screen.getByText(/update the moment they change/)).toBeInTheDocument()

    server.results = [{ ...tomato, inStock: false }, milk]
    stream.signal({ type: 'STOCK_CHANGED', data: { productId: 'p1', vendorId: 'v1', inStock: false } })

    // The signal's `inStock` patches the cached results at once; the refetch follows (batched).
    expect(await within(tileOf('Tomato')).findByText('Just sold out')).toBeInTheDocument()
    expect(within(tileOf('Tomato')).getByText('Out of stock')).toBeInTheDocument()
    expect(within(tileOf('Toned Milk')).queryByText(/just sold out|price/i)).not.toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /search/products')).toHaveLength(2))

    server.results = [
      { ...tomato, inStock: false },
      { ...milk, price: { mrp: 56, sellingPrice: 50, currency: 'INR', discountPercent: 11 } },
    ]
    stream.signal({ type: 'PRICE_CHANGED', data: { productId: 'p2', vendorId: 'v1' } })

    expect(await within(tileOf('Toned Milk')).findByText('Price dropped')).toBeInTheDocument()
    expect(within(tileOf('Toned Milk')).getByText('₹50')).toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /search/products').length).toBeGreaterThanOrEqual(3))
  })

  it('filters by category and stock through the query string', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const api = homeApi({ results: [tomato, milk] })
    const { router } = renderApp('/customer/home')

    await screen.findByRole('list', { name: 'All products' })
    const chips = screen.getByRole('group', { name: 'Filter by category' })
    expect(within(chips).getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(within(chips).getByRole('button', { name: 'Dairy & Bakery' }))
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Tomato' })).not.toBeInTheDocument())
    expect(screen.getByRole('link', { name: 'Toned Milk' })).toBeInTheDocument()
    expect(router.state.location.search).toBe('?category=c-dairy')
    expect(api.callsTo('GET /search/products').at(-1)?.params).toMatchObject({ categoryId: 'c-dairy', page: 0 })
    expect(screen.getByRole('link', { name: 'More filters in search' })).toHaveAttribute(
      'href',
      '/search?categoryId=c-dairy',
    )

    await user.click(screen.getByRole('switch', { name: 'In stock only' }))
    await waitFor(() =>
      expect(api.callsTo('GET /search/products').at(-1)?.params).toMatchObject({
        categoryId: 'c-dairy',
        inStock: true,
      }),
    )

    await user.selectOptions(screen.getByLabelText('Sort by'), 'price_asc')
    await waitFor(() => expect(api.callsTo('GET /search/products').at(-1)?.params).toMatchObject({ sort: 'price_asc' }))
    expect(router.state.location.search).toBe('?category=c-dairy&inStock=true&sort=price_asc')
  })

  it('shows an empty state with a way back when the filters match nothing', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    homeApi({ results: [tomato] })
    renderApp('/customer/home?category=c-dairy')

    expect(await screen.findByText('No products match these filters')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(await screen.findByRole('link', { name: 'Tomato' })).toBeInTheDocument()
  })
})

describe('productChange', () => {
  it('flags stock transitions before price moves, and ignores a missing price', () => {
    expect(productChange(tomato, { ...tomato, inStock: false })).toBe('SOLD_OUT')
    expect(productChange({ ...tomato, inStock: false }, { ...tomato, inStock: null })).toBe('BACK_IN_STOCK')
    expect(productChange(tomato, { ...tomato, price: { ...tomato.price, sellingPrice: 45 } })).toBe('PRICE_DROP')
    expect(productChange(tomato, { ...tomato, price: { ...tomato.price, sellingPrice: 52 } })).toBe('PRICE_RISE')
    expect(productChange(tomato, { ...tomato, price: null })).toBeNull()
    expect(productChange(tomato, { ...tomato })).toBeNull()
  })

  it('compares only products present in both lists', () => {
    const changes = diffProducts([tomato], [{ ...tomato, inStock: false }, milk])
    expect([...changes]).toEqual([['p1', 'SOLD_OUT']])
  })
})
