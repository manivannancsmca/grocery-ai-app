import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { CategoryNode, ProductCard, ProductSearchResponse } from '../../../features/catalog/api'
import { mockApi, type MockRequest } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'

const tree: CategoryNode[] = [
  {
    id: 'c-fv',
    name: 'Fruits & Vegetables',
    description: null,
    imageUrl: null,
    productCount: 3,
    children: [{ id: 'c-veg', name: 'Fresh Vegetables', description: null, imageUrl: null, productCount: 3, children: [] }],
  },
]

function product(id: string, name: string, overrides: Partial<ProductCard> = {}): ProductCard {
  return {
    id,
    name,
    unitLabel: '500 g',
    foodType: 'VEG',
    primaryImageUrl: null,
    brand: { id: 'b1', name: 'Farm Fresh' },
    category: { id: 'c-veg', name: 'Fresh Vegetables' },
    vendor: { id: 'v1', name: 'Green Basket' },
    ...overrides,
  }
}

function results(request: MockRequest): { status: number; data: ProductSearchResponse } {
  const all = [
    product('p1', 'Tomato'),
    product('p2', 'Cherry Tomato', { brand: { id: 'b2', name: 'Village Farms' }, price: { mrp: 60, sellingPrice: 49, currency: 'INR', discountPercent: 18 } }),
    product('p3', 'Tomato Puree', { brand: { id: 'b1', name: 'Farm Fresh' } }),
  ]
  const content = request.params.brandId ? all.filter((item) => item.brand?.id === request.params.brandId) : all
  return {
    status: 200,
    data: {
      content,
      page: 0,
      size: 24,
      totalElements: content.length,
      totalPages: 1,
      facets: {
        categories: [{ id: 'c-veg', name: 'Fresh Vegetables', count: content.length }],
        brands: [
          { id: 'b1', name: 'Farm Fresh', count: 2 },
          { id: 'b2', name: 'Village Farms', count: 1 },
        ],
        foodTypes: [{ value: 'VEG', count: content.length }],
      },
    },
  }
}

describe('Search page', () => {
  it('reads query and filters from the URL, shows results with facets, and puts facet clicks in the URL', async () => {
    const user = userEvent.setup()
    const api = mockApi({
      'GET /search/products': results,
      'GET /catalog/categories': { status: 200, data: tree },
    })
    const { router } = renderApp('/search?q=tomato&categoryId=c-veg')

    expect(await screen.findByRole('heading', { level: 1, name: 'Results for “tomato”' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Search products' })).toHaveValue('tomato')
    const list = await screen.findByRole('list', { name: 'Products' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(3)
    expect(within(list).getByRole('link', { name: 'Cherry Tomato' })).toHaveAttribute('href', '/products/p2')
    // Phase 4 prices render only when present.
    expect(within(list).getByText('₹49')).toBeInTheDocument()
    expect(within(list).getAllByText(/₹/)).toHaveLength(2) // selling price + struck-through MRP of one product
    expect(screen.getByText('3 products')).toBeInTheDocument()

    expect(api.callsTo('GET /search/products')[0]?.params).toEqual({ q: 'tomato', categoryId: 'c-veg', page: 0, size: 24 })
    expect(api.callsTo('GET /search/products')[0]?.headers.get('Authorization')).toBeFalsy()

    const filters = screen.getByRole('complementary', { name: 'Filters' })
    expect(within(filters).getByRole('button', { name: /fresh vegetables/i })).toHaveAttribute('aria-pressed', 'true')
    const brand = within(filters).getByRole('button', { name: /farm fresh/i })
    expect(brand).toHaveAttribute('aria-pressed', 'false')

    await user.click(brand)

    expect(new URLSearchParams(router.state.location.search).get('brandId')).toBe('b1')
    expect(new URLSearchParams(router.state.location.search).get('q')).toBe('tomato')
    expect(await within(screen.getByRole('list', { name: 'Products' })).findByText('Tomato Puree')).toBeInTheDocument()
    await screen.findByText('2 products')
    expect(screen.queryByRole('link', { name: 'Cherry Tomato' })).not.toBeInTheDocument()
    expect(api.callsTo('GET /search/products').at(-1)?.params).toMatchObject({ q: 'tomato', categoryId: 'c-veg', brandId: 'b1' })
    expect(within(screen.getByRole('list', { name: 'Active filters' })).getByRole('button', { name: /brand: farm fresh/i })).toBeInTheDocument()

    // Sorting is URL state too; relevance is the default with a query, so only other values are written.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Sort by' }), 'name_asc')
    expect(new URLSearchParams(router.state.location.search).get('sort')).toBe('name_asc')

    // Clicking a selected facet clears it.
    await user.click(within(screen.getByRole('complementary', { name: 'Filters' })).getByRole('button', { name: /farm fresh/i }))
    expect(new URLSearchParams(router.state.location.search).get('brandId')).toBeNull()
  })

  it('shows an empty state that clears the filters', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /search/products': (request) =>
        request.params.foodType
          ? {
              status: 200,
              data: { content: [], page: 0, size: 24, totalElements: 0, totalPages: 0, facets: { categories: [], brands: [], foodTypes: [] } },
            }
          : results(request),
      'GET /catalog/categories': { status: 200, data: tree },
    })
    const { router } = renderApp('/search?q=tomato&foodType=EGG')

    expect(await screen.findByRole('heading', { name: 'No products found' })).toBeInTheDocument()
    expect(screen.getByText(/nothing matches “tomato” with these filters/i)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(router.state.location.search).toBe('?q=tomato')
    expect(await screen.findByRole('link', { name: 'Tomato Puree' })).toBeInTheDocument()
  })

  it('puts the in-stock toggle, a validated price range and price sorts in the URL and the search request', async () => {
    const user = userEvent.setup()
    const api = mockApi({
      'GET /search/products': results,
      'GET /catalog/categories': { status: 200, data: tree },
    })
    const { router } = renderApp('/search?q=tomato')
    const urlParam = (key: string) => new URLSearchParams(router.state.location.search).get(key)

    const filters = await screen.findByRole('complementary', { name: 'Filters' })
    const inStock = within(filters).getByRole('switch', { name: 'In stock only' })
    expect(inStock).toHaveAttribute('aria-checked', 'false')
    await user.click(inStock)
    expect(urlParam('inStock')).toBe('true')
    expect(within(filters).getByRole('switch', { name: 'In stock only' })).toHaveAttribute('aria-checked', 'true')
    await waitFor(() => expect(api.callsTo('GET /search/products').at(-1)?.params).toMatchObject({ q: 'tomato', inStock: true }))

    // Invalid ranges never reach the URL.
    await user.type(within(filters).getByLabelText('Min price (₹)'), '100')
    await user.type(within(filters).getByLabelText('Max price (₹)'), '50')
    await user.click(within(filters).getByRole('button', { name: 'Apply price' }))
    expect(await within(filters).findByText(/the maximum must be at least the minimum/i)).toBeInTheDocument()
    expect(within(filters).getByLabelText('Max price (₹)')).toHaveAttribute('aria-invalid', 'true')
    expect(urlParam('maxPrice')).toBeNull()

    await user.clear(within(filters).getByLabelText('Min price (₹)'))
    await user.type(within(filters).getByLabelText('Min price (₹)'), '10.5')
    await user.click(within(filters).getByRole('button', { name: 'Apply price' }))
    expect(urlParam('minPrice')).toBe('10.5')
    expect(urlParam('maxPrice')).toBe('50')
    await waitFor(() =>
      expect(api.callsTo('GET /search/products').at(-1)?.params).toMatchObject({ inStock: true, minPrice: 10.5, maxPrice: 50 }),
    )
    const active = screen.getByRole('list', { name: 'Active filters' })
    expect(within(active).getByRole('button', { name: /in stock only/i })).toBeInTheDocument()
    expect(within(active).getByRole('button', { name: 'Remove filter Price: ₹10.50 – ₹50' })).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: 'Sort by' }), 'price_asc')
    expect(urlParam('sort')).toBe('price_asc')
    await waitFor(() => expect(api.callsTo('GET /search/products').at(-1)?.params).toMatchObject({ sort: 'price_asc' }))
    expect(screen.getByRole('option', { name: 'Price: high to low' })).toBeInTheDocument()

    // Removing the price chip clears both bounds.
    await user.click(within(screen.getByRole('list', { name: 'Active filters' })).getByRole('button', { name: /remove filter price/i }))
    expect(urlParam('minPrice')).toBeNull()
    expect(urlParam('maxPrice')).toBeNull()
    expect(urlParam('inStock')).toBe('true')
  })

  it('ignores a malformed price range in the URL instead of sending it', async () => {
    const api = mockApi({
      'GET /search/products': results,
      'GET /catalog/categories': { status: 200, data: tree },
    })
    renderApp('/search?minPrice=80&maxPrice=20&inStock=yes')

    expect(await screen.findByRole('list', { name: 'Products' })).toBeInTheDocument()
    expect(api.callsTo('GET /search/products')[0]?.params).toEqual({ page: 0, size: 24 })
  })
})
