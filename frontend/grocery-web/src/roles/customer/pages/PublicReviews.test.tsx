import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { ProductCard, ProductDetail, ProductSearchResponse } from '../../../features/catalog/api'
import type { RestaurantDetail, RestaurantSearchResponse } from '../../../features/restaurant-discovery/api'
import { mockApi, problem } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { publicReview, ratingSummary } from '../../../test/phase12Fixtures'
import { renderApp } from '../../../test/renderApp'

function productCard(id: string, name: string, overrides: Partial<ProductCard> = {}): ProductCard {
  return {
    id,
    name,
    unitLabel: '1 kg',
    foodType: 'VEG',
    primaryImageUrl: null,
    brand: null,
    category: { id: 'c-rice', name: 'Rice' },
    vendor: { id: 'v1', name: 'Green Basket' },
    price: { mrp: 60, sellingPrice: 50, currency: 'INR', discountPercent: 17 },
    inStock: true,
    ...overrides,
  }
}

const rice: ProductDetail = {
  ...productCard('p5', 'Basmati Rice', { rating: 4.33, ratingCount: 12 }),
  description: 'Long grain.',
  unitValue: 1,
  unitOfMeasure: 'KG',
  sku: null,
  barcode: null,
  tags: [],
  attributes: [],
  imageUrls: [],
  categoryPath: [{ id: 'c-rice', name: 'Rice' }],
  publishedAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-01T10:00:00Z',
}

const spiceRoute: RestaurantDetail = {
  id: 'r1',
  name: 'Spice Route',
  description: null,
  cuisines: ['BIRYANI'],
  openNow: true,
  acceptingOrders: true,
  pureVeg: false,
  address: null,
  openingHours: [],
  menu: [],
  rating: 3.5,
  ratingCount: 2,
}

describe('Ratings and reviews on product pages', () => {
  it('shows the rating under the name, the summary with its distribution, and the reviews with the store’s reply', async () => {
    const user = userEvent.setup()
    const replied = publicReview({
      id: 'pr2',
      rating: 2,
      title: 'Broken grains',
      comment: 'Not as fresh as last time.',
      authorName: 'Ravi K.',
      reply: { text: 'Sorry — we have changed our supplier.', repliedAt: '2026-11-11T08:00:00Z' },
    })
    const api = mockApi({
      'GET /catalog/products/:id': { status: 200, data: rice },
      'GET /reviews/summary': { status: 200, data: ratingSummary() },
      'GET /reviews': (request) =>
        request.params.page === 1
          ? pageOf([publicReview({ id: 'pr6', authorName: 'Meera P.', title: 'Page two' })], { page: 1, size: 5, totalElements: 6, totalPages: 2 })
          : pageOf([publicReview(), replied], { size: 5, totalElements: 6, totalPages: 2 }),
    })
    renderApp('/products/p5')

    expect(await screen.findByRole('heading', { level: 1, name: 'Basmati Rice' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /4\.3 out of 5 stars.*12 ratings/ })).toHaveAttribute('href', '#reviews')

    const section = screen.getByRole('region', { name: 'Ratings & reviews' })
    expect(section).toHaveAttribute('id', 'reviews')
    expect(await within(section).findByText('4.3')).toBeInTheDocument()
    expect(within(section).getByText('12 ratings')).toBeInTheDocument()
    const distribution = within(section).getByRole('list', { name: 'Rating distribution' })
    expect(within(distribution).getByText('5 stars: 7 ratings')).toBeInTheDocument()
    expect(within(distribution).getByText('2 stars: 0 ratings')).toBeInTheDocument()

    const reviews = await within(section).findByRole('list', { name: 'Reviews' })
    expect(within(reviews).getAllByRole('article')).toHaveLength(2)
    const ravi = within(reviews).getByRole('article', { name: 'Review by Ravi K.' })
    expect(within(ravi).getByRole('img', { name: '2 out of 5 stars' })).toBeInTheDocument()
    expect(within(ravi).getByText('Broken grains')).toBeInTheDocument()
    expect(within(ravi).getByText('Sorry — we have changed our supplier.')).toBeInTheDocument()
    expect(within(ravi).getByText(/Reply from Green Basket/)).toBeInTheDocument()

    // Public reads: no token; newest first by default, paged by five.
    expect(api.callsTo('GET /reviews')[0]?.params).toEqual({ targetType: 'PRODUCT', targetId: 'p5', sort: 'newest', page: 0, size: 5 })
    expect(api.callsTo('GET /reviews/summary')[0]?.params).toEqual({ targetType: 'PRODUCT', targetId: 'p5' })
    expect(api.callsTo('GET /reviews')[0]?.headers.get('Authorization')).toBeFalsy()

    await user.selectOptions(within(section).getByRole('combobox', { name: 'Sort reviews' }), 'lowest')
    await waitFor(() => expect(api.callsTo('GET /reviews').at(-1)?.params).toMatchObject({ sort: 'lowest', page: 0 }))

    await user.click(within(section).getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(api.callsTo('GET /reviews').at(-1)?.params).toMatchObject({ sort: 'lowest', page: 1 }))
    expect(await within(section).findByText('Page two')).toBeInTheDocument()
  })

  it('says when a product has no ratings yet, and keeps a reviews failure inside the section', async () => {
    const user = userEvent.setup()
    let fail = true
    mockApi({
      'GET /catalog/products/:id': { status: 200, data: { ...rice, rating: null, ratingCount: 0 } },
      'GET /reviews/summary': { status: 200, data: ratingSummary({ averageRating: null, ratingCount: 0, distribution: [0, 0, 0, 0, 0] }) },
      'GET /reviews': () => (fail ? problem(503, 'SERVICE_UNAVAILABLE') : pageOf([])),
    })
    renderApp('/products/p5')

    expect(await screen.findByRole('heading', { level: 1, name: 'Basmati Rice' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /ratings/ })).not.toBeInTheDocument()
    const section = screen.getByRole('region', { name: 'Ratings & reviews' })
    expect(await within(section).findByText('No ratings yet. Customers can rate it after their order is delivered.')).toBeInTheDocument()
    expect(await within(section).findByText('Reviews could not be loaded right now.')).toBeInTheDocument()
    // The rest of the page is unaffected.
    expect(screen.getByText('Long grain.')).toBeInTheDocument()

    fail = false
    await user.click(within(section).getByRole('button', { name: 'Reload reviews' }))
    await waitFor(() => expect(within(section).queryByText('Reviews could not be loaded right now.')).not.toBeInTheDocument())
  })
})

describe('Ratings on restaurant pages and in search', () => {
  it('shows the restaurant’s rating and reviews', async () => {
    const api = mockApi({
      'GET /catalog/restaurants/:id': { status: 200, data: spiceRoute },
      'GET /reviews/summary': { status: 200, data: ratingSummary({ targetType: 'RESTAURANT', targetId: 'r1', averageRating: 3.5, ratingCount: 2, distribution: [0, 0, 1, 1, 0] }) },
      'GET /reviews': pageOf([publicReview({ authorName: 'Kiran M.', rating: 4, title: 'Great biryani' })], { size: 5 }),
    })
    renderApp('/restaurants/r1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Spice Route' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /3\.5 out of 5 stars.*2 ratings/ })).toBeInTheDocument()
    const section = screen.getByRole('region', { name: 'Ratings & reviews' })
    expect(await within(section).findByText('Great biryani')).toBeInTheDocument()
    expect(api.callsTo('GET /reviews')[0]?.params).toMatchObject({ targetType: 'RESTAURANT', targetId: 'r1' })
  })

  it('shows ratings on product tiles and sorts the search by customer rating', async () => {
    const user = userEvent.setup()
    const response: ProductSearchResponse = {
      content: [productCard('p5', 'Basmati Rice', { rating: 4.33, ratingCount: 1204 }), productCard('p6', 'Brown Rice')],
      page: 0,
      size: 24,
      totalElements: 2,
      totalPages: 1,
      facets: { categories: [], brands: [], foodTypes: [] },
    }
    const api = mockApi({ 'GET /search/products': { status: 200, data: response }, 'GET /catalog/categories': { status: 200, data: [] } })
    const { router } = renderApp('/search?q=rice')

    const list = await screen.findByRole('list', { name: 'Products' })
    const riceTile = within(list).getByText('Basmati Rice').closest('article') as HTMLElement
    expect(within(riceTile).getByText('Rated 4.3 out of 5 from 1,204 ratings')).toBeInTheDocument()
    const brown = within(list).getByText('Brown Rice').closest('article') as HTMLElement
    expect(within(brown).queryByText(/Rated/)).not.toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: 'Sort by' }), 'rating')
    expect(new URLSearchParams(router.state.location.search).get('sort')).toBe('rating')
    await waitFor(() => expect(api.callsTo('GET /search/products').at(-1)?.params).toMatchObject({ q: 'rice', sort: 'rating' }))
    expect(screen.getByRole('option', { name: 'Customer rating' })).toBeInTheDocument()
  })

  it('sorts restaurants by rating and filters to 4.0+', async () => {
    const user = userEvent.setup()
    const page: RestaurantSearchResponse = {
      content: [
        {
          id: 'r1',
          name: 'Spice Route',
          description: null,
          cuisines: ['BIRYANI'],
          city: 'Bengaluru',
          locality: null,
          distanceKm: null,
          openNow: true,
          acceptingOrders: true,
          pureVeg: false,
          matchingItems: [],
          rating: 4.6,
          ratingCount: 87,
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      facets: { cuisines: [] },
    }
    const api = mockApi({ 'GET /search/restaurants': { status: 200, data: page } })
    const { router } = renderApp('/restaurants?location=none')

    const list = await screen.findByRole('list', { name: 'Restaurants' })
    expect(within(list).getByText('Rated 4.6 out of 5 from 87 ratings')).toBeInTheDocument()
    expect(api.callsTo('GET /search/restaurants')[0]?.params).toEqual({ page: 0, size: 20 })

    await user.selectOptions(screen.getByRole('combobox', { name: 'Sort by' }), 'rating')
    await waitFor(() => expect(api.callsTo('GET /search/restaurants').at(-1)?.params).toEqual({ sort: 'rating', page: 0, size: 20 }))
    await user.click(screen.getByRole('switch', { name: 'Rated 4.0+' }))
    await waitFor(() =>
      expect(api.callsTo('GET /search/restaurants').at(-1)?.params).toEqual({ sort: 'rating', minRating: 4, page: 0, size: 20 }),
    )
    const params = new URLSearchParams(router.state.location.search)
    expect(params.get('sort')).toBe('rating')
    expect(params.get('minRating')).toBe('4')
  })
})
