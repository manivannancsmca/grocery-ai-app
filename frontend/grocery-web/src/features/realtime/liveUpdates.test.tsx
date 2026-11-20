import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { cartLine, cartView, issue } from '../../test/cartFixtures'
import { mockApi } from '../../test/mockApi'
import { mockEventStream } from '../../test/mockEventStream'
import { notificationView } from '../../test/notificationFixtures'
import { adminOrderSummary, pageOf } from '../../test/orderFixtures'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import type { OrderStats } from '../admin-orders/api'
import type { ProductCard, ProductDetail, ProductSearchResponse } from '../catalog/api'

const tomatoCard: ProductCard = {
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

const tomato: ProductDetail = {
  ...tomatoCard,
  description: 'Farm-fresh red tomatoes',
  unitValue: 500,
  unitOfMeasure: 'G',
  sku: 'TOM-500',
  barcode: null,
  tags: [],
  attributes: [],
  imageUrls: [],
  categoryPath: [{ id: 'c-veg', name: 'Fresh Vegetables' }],
  publishedAt: '2026-11-11T10:15:30Z',
  updatedAt: '2026-11-11T10:15:30Z',
}

function searchPage(content: ProductCard[]): { status: number; data: ProductSearchResponse } {
  return {
    status: 200,
    data: { content, page: 0, size: 24, totalElements: content.length, totalPages: 1, facets: { categories: [], brands: [], foodTypes: [] } },
  }
}

const NOTIFICATION_ROUTES = { 'GET /notifications/unread-count': { status: 200, data: { count: 0 } } }

describe('Live updates on screens', () => {
  it('the product page shows "Out of stock" after STOCK_CHANGED, without a reload', async () => {
    signInAs(['CUSTOMER'])
    const server = { product: tomato }
    const api = mockApi({
      ...NOTIFICATION_ROUTES,
      'GET /catalog/products/:id': () => ({ status: 200, data: server.product }),
      'GET /cart': { status: 200, data: cartView([]) },
    })
    const sse = mockEventStream()
    renderApp('/products/p1')

    const availability = await screen.findByRole('region', { name: 'Price and availability' })
    expect(within(availability).getByText('In stock')).toBeInTheDocument()
    const stream = await sse.connection()

    server.product = { ...tomato, inStock: false }
    stream.signal({ type: 'STOCK_CHANGED', data: { productId: 'p1', vendorId: 'v1', inStock: false } })

    expect(await within(availability).findByText(/out of stock right now/)).toBeInTheDocument()
    expect(within(availability).queryByText('In stock')).not.toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /catalog/products/:id')).toHaveLength(2))
  })

  it('search results mark the product out of stock after STOCK_CHANGED, and the cart is re-read', async () => {
    signInAs(['CUSTOMER'])
    const server = { results: [tomatoCard] }
    const api = mockApi({
      ...NOTIFICATION_ROUTES,
      'GET /search/products': () => searchPage(server.results),
      'GET /catalog/categories': { status: 200, data: [] },
      'GET /cart': { status: 200, data: cartView([]) },
    })
    const sse = mockEventStream()
    renderApp('/search?q=tomato')

    const tile = (await screen.findByRole('link', { name: 'Tomato' })).closest('article') as HTMLElement
    expect(within(tile).queryByText('Out of stock')).not.toBeInTheDocument()
    const stream = await sse.connection()
    const cartReads = api.callsTo('GET /cart').length

    server.results = [{ ...tomatoCard, inStock: false }]
    stream.signal({ type: 'STOCK_CHANGED', data: { productId: 'p1', vendorId: 'v1', inStock: false } })

    expect(await within(tile).findByText('Out of stock')).toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /search/products').length).toBeGreaterThanOrEqual(2))
    await waitFor(() => expect(api.callsTo('GET /cart').length).toBeGreaterThan(cartReads))
  })

  it('the cart page shows a line selling out (STOCK_CHANGED) without a reload', async () => {
    signInAs(['CUSTOMER'])
    const line = cartLine({ itemId: 'p1', name: 'Tomato' })
    const server = { cart: cartView([line]) }
    mockApi({ ...NOTIFICATION_ROUTES, 'GET /cart': () => ({ status: 200, data: server.cart }) })
    const sse = mockEventStream()
    renderApp('/cart')

    const items = await screen.findByRole('list', { name: 'Items in your cart' })
    expect(within(items).queryByText('Sold out')).not.toBeInTheDocument()
    const stream = await sse.connection()

    server.cart = cartView([{ ...line, availableQuantity: 0, issue: 'OUT_OF_STOCK' }], { issues: [issue('OUT_OF_STOCK', 'p1')] })
    stream.signal({ type: 'STOCK_CHANGED', data: { productId: 'p1', vendorId: 'v1', inStock: false } })

    expect(await within(items).findByText('Sold out')).toBeInTheDocument()
  })

  it('the admin order monitor refreshes on ADMIN_ORDERS_CHANGED', async () => {
    signInAs(['OPS_ADMIN'])
    const server = { rows: [adminOrderSummary()] }
    mockApi({ ...NOTIFICATION_ROUTES, 'GET /admin/orders': () => pageOf(server.rows) })
    const sse = mockEventStream()
    renderApp('/admin/orders')

    const table = await screen.findByRole('table', { name: 'Orders' })
    expect(await within(table).findByRole('button', { name: 'View order GA-7K3F9Q2M' })).toBeInTheDocument()
    const stream = await sse.connection()

    server.rows = [adminOrderSummary({ id: 'o9', orderNumber: 'GA-NEW00009' }), ...server.rows]
    stream.signal({ type: 'ADMIN_ORDERS_CHANGED', data: { orderId: 'o9', event: 'OrderCreated' } })

    expect(await within(table).findByRole('button', { name: 'View order GA-NEW00009' })).toBeInTheDocument()
  })

  it('the admin dashboard stats refresh on ADMIN_ORDERS_CHANGED', async () => {
    signInAs(['FINANCE_ADMIN'])
    const stats: OrderStats = {
      date: '2026-11-11',
      timezone: 'Asia/Kolkata',
      orders: 42,
      byStatus: { CREATED: 1, AWAITING_PAYMENT: 3, CONFIRMED: 30, REJECTED: 2, CANCELLED: 6 },
      byType: { GROCERY: 25, RESTAURANT: 17 },
      confirmedRevenue: 10250,
      averageOrderValue: 341.67,
      currency: 'INR',
    }
    const server = { stats }
    mockApi({ ...NOTIFICATION_ROUTES, 'GET /admin/stats': () => ({ status: 200, data: server.stats }) })
    const sse = mockEventStream()
    renderApp('/admin')

    const section = await screen.findByRole('region', { name: /^Today’s orders/ })
    const tile = () => within(section).getByText('Orders placed').closest('div') as HTMLElement
    await waitFor(() => expect(tile()).toHaveTextContent('42'))
    const stream = await sse.connection()

    server.stats = { ...stats, orders: 43, byType: { GROCERY: 26, RESTAURANT: 17 } }
    stream.signal({ type: 'ADMIN_ORDERS_CHANGED', data: { orderId: 'o9', event: 'OrderCreated' } })

    await waitFor(() => expect(tile()).toHaveTextContent('43'))
  })

  it('the admin dashboard’s live activity shows a new notification at the top', async () => {
    signInAs(['OPS_ADMIN'])
    const older = notificationView({
      id: 'n-1',
      type: 'APPROVAL_REQUESTED',
      category: 'APPROVAL',
      severity: 'INFO',
      title: 'Green Basket is waiting for approval',
      body: 'New vendor',
      link: '/admin/approvals',
      data: {},
      read: true,
    })
    const fresh = notificationView({
      id: 'n-2',
      type: 'PRICE_APPROVAL_REQUESTED',
      category: 'PRICING',
      severity: 'INFO',
      title: 'Price change waiting: Basmati Rice',
      body: '−10% — ₹100.00 → ₹90.00',
      link: '/admin/price-approvals',
      data: {},
    })
    const server = { inbox: [older] }
    mockApi({
      'GET /notifications/unread-count': () => ({ status: 200, data: { count: server.inbox.filter((entry) => !entry.read).length } }),
      'GET /notifications': () => pageOf(server.inbox, { size: 5 }),
    })
    const sse = mockEventStream()
    renderApp('/admin')

    const activity = await screen.findByRole('list', { name: 'Live activity' })
    expect(within(activity).getAllByRole('listitem')).toHaveLength(1)
    const stream = await sse.connection()

    server.inbox = [fresh, older]
    stream.notification(fresh)

    await waitFor(() => expect(within(activity).getAllByRole('listitem')).toHaveLength(2))
    expect(within(activity).getAllByRole('listitem')[0]).toHaveTextContent('Price change waiting: Basmati Rice')
    expect(await screen.findByRole('button', { name: 'Notifications, 1 unread' })).toBeInTheDocument()
  })
})
