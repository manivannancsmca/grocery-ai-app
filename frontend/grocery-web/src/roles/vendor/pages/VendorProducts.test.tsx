import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { CategoryNode } from '../../../features/catalog/api'
import type { ProductRequest, ProductView } from '../../../features/vendor-products/api'
import { mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const tree: CategoryNode[] = [
  {
    id: 'c-fv',
    name: 'Fruits & Vegetables',
    description: null,
    imageUrl: null,
    productCount: 0,
    children: [{ id: 'c-veg', name: 'Fresh Vegetables', description: null, imageUrl: null, productCount: 0, children: [] }],
  },
]

function viewFrom(body: ProductRequest, overrides: Partial<ProductView> = {}): ProductView {
  return {
    id: 'p-new',
    vendorId: 'v1',
    name: body.name,
    description: body.description,
    category: { id: body.categoryId, name: 'Fresh Vegetables', path: ['Fruits & Vegetables', 'Fresh Vegetables'], active: true },
    brand: body.brandName ? { id: 'b1', name: body.brandName } : null,
    unitValue: body.unitValue,
    unitOfMeasure: body.unitOfMeasure,
    unitLabel: `${body.unitValue} g`,
    sku: body.sku,
    barcode: body.barcode,
    foodType: body.foodType,
    tags: body.tags,
    attributes: body.attributes,
    imageUrls: body.imageUrls,
    status: 'DRAFT',
    publishedAt: null,
    createdAt: '2026-11-11T10:00:00Z',
    updatedAt: '2026-11-11T10:00:00Z',
    ...overrides,
  }
}

const listed: ProductView = viewFrom(
  {
    name: 'Tomato',
    description: null,
    categoryId: 'c-veg',
    brandName: 'Farm Fresh',
    unitValue: 500,
    unitOfMeasure: 'G',
    sku: 'TOM-500',
    barcode: null,
    foodType: 'VEG',
    tags: [],
    attributes: [],
    imageUrls: [],
  },
  { id: 'p1' },
)

describe('Vendor product form', () => {
  it('validates the fields and sends the contract payload', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    let created: ProductView | null = null
    const api = mockApi({
      'GET /catalog/categories': { status: 200, data: tree },
      'GET /vendor/brands': { status: 200, data: [{ id: 'b1', name: 'Farm Fresh' }] },
      'POST /vendor/products': (request) => {
        created = viewFrom(request.body as ProductRequest)
        return { status: 201, data: created }
      },
      'GET /vendor/products/:id': () => ({ status: 200, data: created }),
    })
    const { router } = renderApp('/vendor/products/new')

    await user.click(await screen.findByRole('button', { name: 'Create product' }))
    expect(await screen.findByText('Enter the product name.')).toBeInTheDocument()
    expect(screen.getByText('Choose a category.')).toBeInTheDocument()
    expect(screen.getByText('Enter the pack size, e.g. 500.')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Product name/)).toHaveAttribute('aria-invalid', 'true')
    expect(api.callsTo('POST /vendor/products')).toHaveLength(0)

    await user.type(screen.getByLabelText(/^Product name/), 'Tomato')
    const category = screen.getByLabelText(/^Category/)
    expect(within(category).getByRole('option', { name: /fresh vegetables/i })).toBeInTheDocument()
    await user.selectOptions(category, 'c-veg')
    await user.type(screen.getByLabelText(/^Brand/), 'Farm Fresh')
    await user.type(screen.getByLabelText(/^Pack size/), '0.0005')
    await user.type(screen.getByLabelText(/^Barcode/), '12345')
    await user.click(screen.getByRole('radio', { name: /^vegetarian/i }))
    await user.type(screen.getByLabelText('Tags'), 'Organic{Enter}')
    await user.click(screen.getByRole('button', { name: 'Add attribute' }))
    await user.type(screen.getByLabelText('Attribute 1 name'), 'Origin')
    await user.type(screen.getByLabelText('Attribute 1 value'), 'Nashik')
    await user.click(screen.getByRole('button', { name: 'Add image' }))
    await user.type(screen.getByLabelText('Image URL 1'), 'ftp://example.com/x.jpg')
    await user.click(screen.getByRole('button', { name: 'Add image' })) // left blank: dropped from the request

    await user.click(screen.getByRole('button', { name: 'Create product' }))
    expect(await screen.findByText('Use at most 3 decimal places.')).toBeInTheDocument()
    expect(screen.getByText('Barcodes have 8 to 14 digits.')).toBeInTheDocument()
    expect(screen.getByText(/starting with http:\/\/ or https:\/\//)).toBeInTheDocument()
    expect(api.callsTo('POST /vendor/products')).toHaveLength(0)

    await user.clear(screen.getByLabelText(/^Pack size/))
    await user.type(screen.getByLabelText(/^Pack size/), '500')
    await user.clear(screen.getByLabelText(/^Barcode/))
    await user.clear(screen.getByLabelText('Image URL 1'))
    await user.type(screen.getByLabelText('Image URL 1'), 'https://img.example.com/tomato.jpg')
    expect(screen.getByRole('img', { name: 'Preview of image 1' })).toHaveAttribute('src', 'https://img.example.com/tomato.jpg')

    await user.click(screen.getByRole('button', { name: 'Create product' }))

    expect(await screen.findByText(/tomato was created as a draft/i)).toBeInTheDocument()
    expect(api.callsTo('POST /vendor/products')).toHaveLength(1)
    expect(api.callsTo('POST /vendor/products')[0]?.body).toEqual({
      name: 'Tomato',
      description: null,
      categoryId: 'c-veg',
      brandName: 'Farm Fresh',
      unitValue: 500,
      unitOfMeasure: 'G',
      sku: null,
      barcode: null,
      foodType: 'VEG',
      tags: ['organic'],
      attributes: [{ name: 'Origin', value: 'Nashik' }],
      imageUrls: ['https://img.example.com/tomato.jpg'],
    })
    expect(router.state.location.pathname).toBe('/vendor/products/p-new')
    expect(await screen.findByRole('heading', { level: 1, name: 'Tomato' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publish' })).toBeInTheDocument()
  })

  it('puts a duplicate SKU and server field errors on the matching fields', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    let attempt = 0
    mockApi({
      'GET /catalog/categories': { status: 200, data: tree },
      'GET /vendor/brands': { status: 200, data: [] },
      'GET /vendor/products/:id': { status: 200, data: listed },
      'PUT /vendor/products/:id': () => {
        attempt += 1
        return attempt === 1
          ? problem(409, 'SKU_ALREADY_EXISTS', 'SKU already exists')
          : problem(400, 'VALIDATION_FAILED', 'Validation failed', [{ field: 'imageUrls[0]', message: 'image host is not allowed' }])
      },
    })
    renderApp('/vendor/products/p1')

    const sku = await screen.findByLabelText(/^SKU/)
    expect(sku).toHaveValue('TOM-500')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('Another of your products already uses this SKU.')).toBeInTheDocument()
    expect(sku).toHaveAttribute('aria-invalid', 'true')

    // A blank first row is dropped from the request, so the server's imageUrls[0] is the form's second row.
    await user.click(screen.getByRole('button', { name: 'Add image' }))
    await user.click(screen.getByRole('button', { name: 'Add image' }))
    await user.type(screen.getByLabelText('Image URL 2'), 'https://cdn.example.com/a.jpg')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('image host is not allowed')).toBeInTheDocument()
    expect(screen.getByLabelText('Image URL 2')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Image URL 1')).not.toHaveAttribute('aria-invalid')
  })
})

describe('Vendor products list', () => {
  it('lists products with status tabs in the URL and publishes a draft', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = mockApi({
      'GET /vendor/products': { status: 200, data: { content: [listed], page: 0, size: 20, totalElements: 1, totalPages: 1 } },
      'POST /vendor/products/:id/publish': { status: 200, data: { ...listed, status: 'PUBLISHED' } },
    })
    const { router } = renderApp('/vendor/products')

    const table = await screen.findByRole('table', { name: 'Your products' })
    expect(within(table).getByRole('link', { name: 'Tomato' })).toHaveAttribute('href', '/vendor/products/p1')
    expect(within(table).getByText('Fruits & Vegetables › Fresh Vegetables')).toBeInTheDocument()
    expect(within(table).getByText('Draft')).toBeInTheDocument()

    await user.click(within(table).getByRole('button', { name: 'Publish Tomato' }))
    expect(await screen.findByText(/tomato is published/i)).toBeInTheDocument()
    expect(api.callsTo('POST /vendor/products/:id/publish')[0]?.pathParams.id).toBe('p1')

    await user.click(screen.getByRole('tab', { name: 'Archived' }))
    expect(router.state.location.search).toBe('?status=ARCHIVED')
    await waitFor(() => expect(api.callsTo('GET /vendor/products').at(-1)?.params).toMatchObject({ status: 'ARCHIVED' }))
  })

  it('shows the catalog overview, flags a live product without a price, and switches to the grid', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const live = { ...listed, status: 'PUBLISHED' as const }
    const counts: Record<string, number> = { DRAFT: 2, PUBLISHED: 1, UNPUBLISHED: 0, ARCHIVED: 3 }
    mockApi({
      'GET /vendor/products': (request) => {
        const status = request.params.status as string | undefined
        const total = status ? (counts[status] ?? 0) : 3
        return { status: 200, data: { content: request.params.size === 1 ? [] : [live], page: 0, size: 20, totalElements: total, totalPages: 1 } }
      },
      'GET /vendor/prices': { status: 200, data: { content: [{ productId: 'p1', productStatus: 'PUBLISHED' }], page: 0, size: 100, totalElements: 1, totalPages: 1 } },
      'GET /vendor/inventory': { status: 200, data: { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 } },
    })
    const { router } = renderApp('/vendor/products')

    const overview = await screen.findByLabelText('Catalog overview')
    await waitFor(() => expect(within(overview).getByText('In your catalog').nextElementSibling).toHaveTextContent('3'))
    expect(within(overview).getByText('Plus 3 archived')).toBeInTheDocument()
    expect(within(overview).getByRole('link', { name: /Finish 2 drafts/ })).toHaveAttribute('href', '/vendor/products?status=DRAFT')
    expect(await within(overview).findByRole('link', { name: '1 without a price' })).toHaveAttribute('href', '/vendor/pricing?filter=UNPRICED')

    // The status tabs keep their plain names; the counts are decoration.
    expect(screen.getByRole('tab', { name: 'Draft' })).toHaveTextContent('Draft2')
    const table = await screen.findByRole('table', { name: 'Your products' })
    expect(within(table).getByRole('link', { name: 'Set a price for Tomato' })).toHaveAttribute('href', '/vendor/pricing?q=TOM-500')
    expect(within(table).getByRole('button', { name: 'Archive Tomato' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Grid view' }))
    expect(router.state.location.search).toBe('?view=grid')
    const grid = await screen.findByRole('list', { name: 'Your products' })
    const card = within(grid).getByRole('article', { name: 'Tomato' })
    expect(within(card).getByRole('link', { name: 'Edit Tomato' })).toHaveAttribute('href', '/vendor/products/p1')
    expect(within(card).getByRole('button', { name: 'Unpublish Tomato' })).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Your products' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Grid view' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('explains that products open up once the business is approved (403 VENDOR_NOT_ACTIVE)', async () => {
    signInAs(['VENDOR'])
    mockApi({ 'GET /vendor/products': problem(403, 'VENDOR_NOT_ACTIVE', 'Vendor is not active') })
    renderApp('/vendor/products')

    expect(await screen.findByText('Products open up once your business is approved')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View business profile' })).toHaveAttribute('href', '/vendor/profile')
    expect(screen.queryByRole('link', { name: 'Add product' })).not.toBeInTheDocument()
  })
})
