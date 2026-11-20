import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import {
  cartLine,
  cartServer,
  cartView,
  checkoutPreview,
  DAILY_MART,
  GREEN_BASKET,
  issue,
  SPICE_ROUTE,
} from '../../../test/cartFixtures'
import { mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

function product(id, name, overrides = {}) {
  return {
    id,
    name,
    unitLabel: '500 g',
    foodType: 'VEG',
    primaryImageUrl: null,
    brand: null,
    category: { id: 'c-veg', name: 'Fresh Vegetables' },
    vendor: { id: 'v2', name: 'Daily Mart' },
    price: { mrp: 60, sellingPrice: 49, currency: 'INR', discountPercent: 18 },
    inStock: true,
    ...overrides,
  }
}

const PRODUCTS = [
  product('p1', 'Tomato'),
  product('p2', 'Onion', { inStock: false }),
  product('p3', 'Garlic', { price: null }),
]

function searchResults() {
  return {
    status: 200,
    data: {
      content: PRODUCTS,
      page: 0,
      size: 24,
      totalElements: PRODUCTS.length,
      totalPages: 1,
      facets: { categories: [], brands: [], foodTypes: [] },
    },
  }
}

const searchRoutes = {
  'GET /search/products': searchResults,
  'GET /catalog/categories': { status: 200, data: [] },
}

/** What POST /cart/items adds for Tomato (Daily Mart). */
const catalog = { p1: { line: cartLine({ itemId: 'p1', name: 'Tomato' }), store: DAILY_MART } }

function tileOf(name) {
  return screen.getByRole('link', { name }).closest('article')
}

describe('Add to cart from product tiles', () => {
  it('adds a product: the tile turns into a stepper and the header count updates', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const server = cartServer(cartView([]), catalog)
    const api = mockApi({ ...searchRoutes, ...server.routes })
    renderApp('/search?q=tomato')

    await screen.findByRole('link', { name: 'Tomato' })
    expect(await screen.findByRole('button', { name: 'Cart, 0 items' })).toBeInTheDocument()
    const tile = tileOf('Tomato')

    await user.click(within(tile).getByRole('button', { name: 'Add Tomato to cart' }))

    const stepper = await within(tile).findByRole('group', { name: 'Quantity of Tomato' })
    expect(stepper).toHaveTextContent('1')
    expect(api.callsTo('POST /cart/items')[0]?.body).toEqual({ type: 'PRODUCT', productId: 'p1', quantity: 1 })
    expect(api.callsTo('POST /cart/items')[0]?.headers.get('Authorization')).toBe('Bearer test-access-token')
    expect(await screen.findByRole('button', { name: 'Cart, 1 item' })).toBeInTheDocument()
    // Keyboard focus moves from "Add" to the stepper.
    const increase = within(tile).getByRole('button', { name: 'Increase quantity of Tomato' })
    expect(increase).toHaveFocus()

    await user.click(increase)
    expect(await screen.findByRole('button', { name: 'Cart, 2 items' })).toBeInTheDocument()
    expect(api.callsTo('PUT /cart/items/:itemId')[0]?.body).toEqual({ quantity: 2 })
    expect(within(tile).getByRole('group', { name: 'Quantity of Tomato' })).toHaveTextContent('2')
    // The live region announces the new count.
    expect(screen.getByText('2 items in your cart')).toBeInTheDocument()

    // "−" down to zero removes the line and brings back "Add".
    await user.click(within(tile).getByRole('button', { name: 'Decrease quantity of Tomato' }))
    await screen.findByRole('button', { name: 'Cart, 1 item' })
    await user.click(within(tile).getByRole('button', { name: 'Remove Tomato from cart' }))
    expect(await within(tile).findByRole('button', { name: 'Add Tomato to cart' })).toHaveFocus()
    expect(api.callsTo('DELETE /cart/items/:itemId')[0]?.pathParams.itemId).toBe('p1')
    expect(screen.getByRole('button', { name: 'Cart, 0 items' })).toBeInTheDocument()
  })

  it('asks to start a new cart when the cart holds another store’s items, then retries with replaceCart', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const server = cartServer(
      cartView([cartLine({ itemId: 'p9', name: 'Milk', quantity: 2 })], { store: GREEN_BASKET }),
      catalog,
    )
    const api = mockApi({ ...searchRoutes, ...server.routes })
    renderApp('/search?q=tomato')

    await screen.findByRole('button', { name: 'Cart, 2 items' })
    // Phase 15: the search waits for the delivery location (the default address) before it runs.
    await screen.findByRole('link', { name: 'Tomato' })
    await user.click(within(tileOf('Tomato')).getByRole('button', { name: 'Add Tomato to cart' }))

    let dialog = await screen.findByRole('dialog', { name: 'Start a new cart?' })
    expect(dialog).toHaveTextContent('Your cart has items from Green Basket. Start a new cart?')
    await user.click(within(dialog).getByRole('button', { name: 'Keep current cart' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(api.callsTo('POST /cart/items')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Cart, 2 items' })).toBeInTheDocument()

    await user.click(within(tileOf('Tomato')).getByRole('button', { name: 'Add Tomato to cart' }))
    dialog = await screen.findByRole('dialog', { name: 'Start a new cart?' })
    await user.click(within(dialog).getByRole('button', { name: 'Start new cart' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(api.callsTo('POST /cart/items').at(-1)?.body).toEqual({
      type: 'PRODUCT',
      productId: 'p1',
      quantity: 1,
      replaceCart: true,
    })
    expect(await within(tileOf('Tomato')).findByRole('group', { name: 'Quantity of Tomato' })).toHaveTextContent('1')
    expect(await screen.findByRole('button', { name: 'Cart, 1 item' })).toBeInTheDocument()
    expect(screen.getByText('Started a new cart from Daily Mart.')).toBeInTheDocument()
  })

  it('keeps "Add" disabled, with the reason, for out-of-stock and unpriced products', async () => {
    signInAs(['CUSTOMER'])
    mockApi({ ...searchRoutes, ...cartServer(cartView([])).routes })
    renderApp('/search?q=tomato')

    await screen.findByRole('link', { name: 'Onion' })
    const onion = within(tileOf('Onion')).getByRole('button', { name: 'Add Onion to cart' })
    expect(onion).toBeDisabled()
    expect(onion).toHaveAccessibleDescription(/out of stock/i)
    const garlic = within(tileOf('Garlic')).getByRole('button', { name: 'Add Garlic to cart' })
    expect(garlic).toBeDisabled()
    expect(garlic).toHaveAccessibleDescription(/no price/i)
    expect(within(tileOf('Tomato')).getByRole('button', { name: 'Add Tomato to cart' })).toBeEnabled()
  })

  it('maps refusals to friendly messages (insufficient stock)', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const insufficient = problem(409, 'INSUFFICIENT_STOCK', 'Not enough stock')
    mockApi({
      ...searchRoutes,
      ...cartServer(cartView([])).routes,
      'POST /cart/items': { ...insufficient, data: { ...insufficient.data, available: 3 } },
    })
    renderApp('/search?q=tomato')

    await user.click(await screen.findByRole('button', { name: 'Add Tomato to cart' }))
    expect(await screen.findByText('Only 3 of Tomato are left in stock.')).toBeInTheDocument()
    expect(within(tileOf('Tomato')).getByRole('button', { name: 'Add Tomato to cart' })).toBeEnabled()
  })

  it('sends guests to the login page with a way back', async () => {
    const user = userEvent.setup()
    const api = mockApi(searchRoutes)
    const { router } = renderApp('/search?q=tomato')

    await user.click(await screen.findByRole('button', { name: 'Add Tomato to cart' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(router.state.location.state).toEqual({ from: '/search?q=tomato' })
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(api.callsTo('GET /cart')).toHaveLength(0)
  })

  it('shows no Add button or cart to vendors', async () => {
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = mockApi(searchRoutes)
    renderApp('/search?q=tomato')

    await screen.findByRole('link', { name: 'Tomato' })
    expect(screen.queryByRole('button', { name: /to cart$/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Cart,/ })).not.toBeInTheDocument()
    expect(api.callsTo('GET /cart')).toHaveLength(0)
  })
})

describe('Cart drawer', () => {
  it('changes a quantity, removes a line and clears the cart after confirmation', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const server = cartServer(
      cartView([
        cartLine({ itemId: 'p1', name: 'Tomato', quantity: 2 }),
        cartLine({ itemId: 'p2', name: 'Onion', unitLabel: '1 kg', unitPrice: 40, mrp: 40, priceWhenAdded: 40 }),
      ]),
    )
    const api = mockApi({ 'GET /customers/me/addresses': { status: 200, data: [] }, ...server.routes })
    renderApp('/account/addresses')

    await user.click(await screen.findByRole('button', { name: 'Cart, 3 items' }))
    const drawer = await screen.findByRole('dialog', { name: 'Your cart' })
    const items = await within(drawer).findByRole('list', { name: 'Items in your cart' })
    expect(within(drawer).getByRole('link', { name: 'Green Basket' })).toHaveAttribute('href', '/search?vendorId=v1')
    const tomato = within(items).getByText('Tomato').closest('li')
    expect(within(tomato).getByText('₹98')).toBeInTheDocument() // line total
    expect(within(tomato).getByText('₹60')).toHaveClass('line-through')
    expect(within(drawer).getByText('Subtotal (3 items)').nextElementSibling).toHaveTextContent('₹138')
    expect(within(drawer).getByText('Your savings on MRP').nextElementSibling).toHaveTextContent('₹22')
    // Totals and "Checkout" sit in the drawer's pinned footer, outside the scrolling lines (nothing slides under them).
    const checkout = within(drawer).getByRole('link', { name: 'Checkout' })
    expect(checkout).toHaveAttribute('href', '/checkout')
    const footer = checkout.closest('footer')
    expect(footer).not.toBeNull()
    expect(within(footer).getByText('Subtotal (3 items)')).toBeInTheDocument()
    expect(footer).not.toContainElement(items)
    // No coupon yet: a ticket in the scrolling content leads to checkout, where coupons are applied.
    const ticket = within(drawer).getByRole('link', { name: /^Have a coupon\?\s*Apply it at checkout\.$/ })
    expect(ticket).toHaveAttribute('href', '/checkout')
    expect(footer).not.toContainElement(ticket)

    await user.click(within(tomato).getByRole('button', { name: 'Increase quantity of Tomato' }))
    expect(await screen.findByRole('button', { name: 'Cart, 4 items' })).toBeInTheDocument()
    expect(api.callsTo('PUT /cart/items/:itemId')[0]).toMatchObject({
      pathParams: { itemId: 'p1' },
      body: { quantity: 3 },
    })
    expect(within(tomato).getByRole('group', { name: 'Quantity of Tomato' })).toHaveTextContent('3')

    await user.click(within(items).getByRole('button', { name: 'Remove Onion' }))
    await waitFor(() => expect(within(drawer).queryByText('Onion')).not.toBeInTheDocument())
    expect(api.callsTo('DELETE /cart/items/:itemId')[0]?.pathParams.itemId).toBe('p2')
    expect(screen.getByRole('button', { name: 'Cart, 3 items' })).toBeInTheDocument()

    await user.click(within(drawer).getByRole('button', { name: 'Clear cart' }))
    const confirm = await screen.findByRole('dialog', { name: 'Clear your cart?' })
    expect(api.callsTo('DELETE /cart')).toHaveLength(0)
    await user.click(within(confirm).getByRole('button', { name: 'Clear cart' }))

    expect(await within(drawer).findByRole('heading', { name: 'Your cart is empty' })).toBeInTheDocument()
    expect(api.callsTo('DELETE /cart')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Cart, 0 items' })).toBeInTheDocument()
    expect(within(drawer).getByRole('link', { name: 'Browse products' })).toHaveAttribute('href', '/search')
    // An empty cart has no totals footer.
    expect(within(drawer).queryByRole('link', { name: 'Checkout' })).not.toBeInTheDocument()
    expect(drawer.querySelector('footer')).toBeNull()
  })

  it('shows the applied coupon as a ticket, or why it can no longer be used', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const lines = [cartLine({ itemId: 'p1', name: 'Tomato', quantity: 2 })]
    const coupon = { code: 'SAVE10', valid: true, discount: 9.8, deliveryFeeDiscount: 0 }
    const server = cartServer(cartView(lines, { coupon }))
    mockApi({ 'GET /customers/me/addresses': { status: 200, data: [] }, ...server.routes })
    const { unmount } = renderApp('/account/addresses')

    await user.click(await screen.findByRole('button', { name: 'Cart, 2 items' }))
    let drawer = await screen.findByRole('dialog', { name: 'Your cart' })
    expect(
      await within(drawer).findByRole('link', { name: /^SAVE10 applied\s*Your discount is shown at checkout\.$/ }),
    ).toHaveAttribute('href', '/checkout')
    unmount()

    signInAs(['CUSTOMER'])
    mockApi({
      'GET /customers/me/addresses': { status: 200, data: [] },
      ...cartServer(cartView(lines, { coupon: { ...coupon, valid: false, discount: 0 } })).routes,
    })
    renderApp('/account/addresses')
    await user.click(await screen.findByRole('button', { name: 'Cart, 2 items' }))
    drawer = await screen.findByRole('dialog', { name: 'Your cart' })
    expect(
      await within(drawer).findByRole('link', { name: /^SAVE10 can’t be used\s*Remove or change it at checkout\.$/ }),
    ).toBeInTheDocument()
  })

  it('highlights line issues: price changed, only N left, and store-level problems (on /cart)', async () => {
    signInAs(['CUSTOMER'])
    const lines = [
      cartLine({ itemId: 'p1', name: 'Tomato', unitPrice: 50, mrp: 60, priceWhenAdded: 90, issue: 'PRICE_CHANGED' }),
      cartLine({ itemId: 'p2', name: 'Onion', quantity: 5, availableQuantity: 2, issue: 'INSUFFICIENT_STOCK' }),
    ]
    const cart = cartView(lines, {
      issues: [
        issue('PRICE_CHANGED', 'p1', { message: 'Tomato now costs ₹50.00 (was ₹90.00)' }),
        issue('INSUFFICIENT_STOCK', 'p2'),
        issue('STORE_UNAVAILABLE'),
      ],
    })
    mockApi({
      'GET /customers/me/addresses': { status: 200, data: [] },
      'GET /cart/preview': { status: 200, data: checkoutPreview(cart) },
      'GET /coupons': { status: 200, data: [] },
      ...cartServer(cart).routes,
    })
    renderApp('/cart')

    const items = await screen.findByRole('list', { name: 'Items in your cart' })
    const tomato = within(items).getByText('Tomato').closest('li')
    expect(within(tomato).getByText('Price changed: ₹90 → ₹50')).toHaveClass('text-amber-800')
    expect(tomato).toHaveAccessibleDescription('Price changed: ₹90 → ₹50')

    const onion = within(items).getByText('Onion').closest('li')
    expect(within(onion).getByText('Only 2 left')).toHaveClass('text-red-700')
    expect(within(onion).getByRole('button', { name: 'Increase quantity of Onion' })).toBeDisabled()
    expect(within(onion).getByRole('button', { name: 'Decrease quantity of Onion' })).toBeEnabled()

    expect(
      screen.getByText('Green Basket is not available on FreshCart right now, so this cart cannot be ordered.'),
    ).toBeInTheDocument()
  })
})

describe('Add to cart on a restaurant page', () => {
  const menu = {
    id: 'r1',
    name: 'Spice Route',
    description: null,
    cuisines: ['BIRYANI'],
    openNow: true,
    acceptingOrders: true,
    pureVeg: false,
    address: null,
    openingHours: [],
    menu: [
      {
        id: 'mc1',
        name: 'Biryani',
        description: null,
        items: [
          {
            id: 'i1',
            name: 'Chicken Biryani',
            description: null,
            price: 249,
            foodType: 'NON_VEG',
            available: true,
            imageUrl: null,
          },
          {
            id: 'i2',
            name: 'Veg Biryani',
            description: null,
            price: 199,
            foodType: 'VEG',
            available: false,
            imageUrl: null,
          },
          {
            id: 'i3',
            name: 'Egg Biryani',
            description: null,
            price: 219,
            foodType: 'EGG',
            available: true,
            imageUrl: null,
          },
        ],
      },
    ],
  }
  const dishCatalog = {
    i1: {
      line: cartLine({
        itemId: 'i1',
        type: 'MENU_ITEM',
        name: 'Chicken Biryani',
        unitLabel: null,
        foodType: 'NON_VEG',
        unitPrice: 249,
        mrp: null,
        priceWhenAdded: 249,
        availableQuantity: null,
      }),
      store: SPICE_ROUTE,
    },
  }

  it('adds a dish, keeps sold-out dishes disabled and explains a closed restaurant', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const server = cartServer(cartView([]), dishCatalog)
    const addItem = server.routes['POST /cart/items']
    const api = mockApi({
      'GET /catalog/restaurants/:id': { status: 200, data: menu },
      ...server.routes,
      // The egg dish is refused: the restaurant closed a moment ago.
      'POST /cart/items': (request) =>
        request.body.menuItemId === 'i3' ? problem(409, 'STORE_CLOSED', 'Restaurant is closed') : addItem(request),
    })
    renderApp('/restaurants/r1')

    const dish = (name) => screen.getByRole('heading', { name }).closest('li')
    await screen.findByRole('heading', { name: 'Chicken Biryani' })
    expect(within(dish('Veg Biryani')).getByRole('button', { name: 'Add Veg Biryani to cart' })).toBeDisabled()
    expect(
      within(dish('Veg Biryani')).getByRole('button', { name: 'Add Veg Biryani to cart' }),
    ).toHaveAccessibleDescription(/sold out/i)

    await user.click(within(dish('Chicken Biryani')).getByRole('button', { name: 'Add Chicken Biryani to cart' }))
    expect(
      await within(dish('Chicken Biryani')).findByRole('group', { name: 'Quantity of Chicken Biryani' }),
    ).toHaveTextContent('1')
    expect(api.callsTo('POST /cart/items')[0]?.body).toEqual({
      type: 'MENU_ITEM',
      restaurantId: 'r1',
      menuItemId: 'i1',
      quantity: 1,
    })
    expect(await screen.findByRole('button', { name: 'Cart, 1 item' })).toBeInTheDocument()

    await user.click(within(dish('Egg Biryani')).getByRole('button', { name: 'Add Egg Biryani to cart' }))
    expect(
      await screen.findByText('Spice Route is closed right now. You can order when it opens again.'),
    ).toBeInTheDocument()
  })

  it('disables every dish while the restaurant is closed or paused', async () => {
    signInAs(['CUSTOMER'])
    mockApi({
      'GET /catalog/restaurants/:id': { status: 200, data: { ...menu, openNow: false } },
      ...cartServer(cartView([])).routes,
    })
    renderApp('/restaurants/r1')

    const add = await screen.findByRole('button', { name: 'Add Chicken Biryani to cart' })
    expect(add).toBeDisabled()
    expect(add).toHaveAccessibleDescription('The restaurant is closed right now.')
    expect(screen.getByText('Closed right now')).toBeInTheDocument()
  })
})
