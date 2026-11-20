import { problem } from './mockApi'

const round = (value) => Math.round(value * 100) / 100

export const GREEN_BASKET = { id: 'v1', type: 'GROCERY', name: 'Green Basket' }
export const DAILY_MART = { id: 'v2', type: 'GROCERY', name: 'Daily Mart' }
export const SPICE_ROUTE = { id: 'r1', type: 'RESTAURANT', name: 'Spice Route' }

/** A cart line; `lineTotal` always follows `unitPrice × quantity`. */
export function cartLine(overrides = {}) {
  const line = {
    itemId: 'p1',
    type: 'PRODUCT',
    name: 'Tomato',
    unitLabel: '500 g',
    imageUrl: null,
    foodType: 'VEG',
    quantity: 1,
    unitPrice: 49,
    mrp: 60,
    lineTotal: null,
    priceWhenAdded: 49,
    availableQuantity: 10,
    issue: null,
    ...overrides,
  }
  return { ...line, lineTotal: line.unitPrice === null ? null : round(line.unitPrice * line.quantity) }
}

/** A CartView with totals computed from its lines (store defaults to Green Basket). */
export function cartView(lines, overrides = {}) {
  const priced = lines.filter((line) => line.unitPrice !== null)
  const subtotal = round(priced.reduce((sum, line) => sum + (line.lineTotal ?? 0), 0))
  const mrpTotal = round(priced.reduce((sum, line) => sum + (line.mrp ?? line.unitPrice ?? 0) * line.quantity, 0))
  return {
    store: lines.length > 0 ? GREEN_BASKET : null,
    lines,
    itemCount: lines.reduce((sum, line) => sum + line.quantity, 0),
    subtotal,
    mrpTotal,
    savings: round(mrpTotal - subtotal),
    currency: 'INR',
    issues: [],
    updatedAt: '2026-11-11T10:00:00Z',
    ...overrides,
  }
}

export function issue(code, itemId = null, overrides = {}) {
  return { code, itemId, blocking: code !== 'PRICE_CHANGED', message: `server message for ${code}`, ...overrides }
}

export function checkoutPreview(cart, overrides = {}) {
  const fee = overrides.delivery?.fee ?? 0
  return {
    ...cart,
    address: null,
    delivery: null,
    total: round(cart.subtotal + fee),
    canCheckout: !cart.issues.some((entry) => entry.blocking),
    pricedAt: '2026-11-11T10:00:00Z',
    ...overrides,
  }
}

/**
 * In-memory cart-service for `mockApi`: GET / POST / PUT / DELETE behave like the contract (one store per
 * cart → 409 CART_STORE_CONFLICT unless `replaceCart`). Spread `routes` into `mockApi` and override any route.
 */
export function cartServer(initial, catalog = {}) {
  let cart = initial
  const save = (lines, store) => {
    cart = cartView(lines, { store: lines.length > 0 ? store : null })
    return { status: 200, data: cart }
  }

  const routes = {
    'GET /cart': () => ({ status: 200, data: cart }),
    'POST /cart/items': (request) => {
      const body = request.body
      const itemId = body.type === 'PRODUCT' ? body.productId : body.menuItemId
      const entry = catalog[itemId]
      const existing = cart.lines.find((line) => line.itemId === itemId)
      if (!entry && !existing) return problem(409, 'ITEM_UNAVAILABLE', 'Item is not available')
      const store = entry?.store ?? cart.store
      if (cart.store && store && cart.store.id !== store.id && !body.replaceCart) {
        const conflict = problem(409, 'CART_STORE_CONFLICT', 'Your cart has items from another store')
        return {
          ...conflict,
          data: {
            ...conflict.data,
            currentStoreId: cart.store.id,
            currentStoreName: cart.store.name,
            currentStoreType: cart.store.type,
          },
        }
      }
      const kept = body.replaceCart ? [] : cart.lines
      const base = existing && !body.replaceCart ? existing : entry?.line
      const quantity = (existing && !body.replaceCart ? existing.quantity : 0) + body.quantity
      const lines =
        existing && !body.replaceCart
          ? kept.map((line) => (line.itemId === itemId ? cartLine({ ...base, quantity }) : line))
          : [...kept, cartLine({ ...base, quantity })]
      return save(lines, store)
    },
    'PUT /cart/items/:itemId': (request) => {
      const { quantity } = request.body
      const itemId = request.pathParams.itemId
      if (!cart.lines.some((line) => line.itemId === itemId)) return problem(404, 'CART_ITEM_NOT_FOUND')
      const lines =
        quantity === 0
          ? cart.lines.filter((line) => line.itemId !== itemId)
          : cart.lines.map((line) => (line.itemId === itemId ? cartLine({ ...line, quantity, issue: null }) : line))
      return save(lines, cart.store)
    },
    'DELETE /cart/items/:itemId': (request) =>
      save(
        cart.lines.filter((line) => line.itemId !== request.pathParams.itemId),
        cart.store,
      ),
    'DELETE /cart': () => {
      cart = cartView([])
      return { status: 204 }
    },
  }

  return {
    routes,
    get cart() {
      return cart
    },
  }
}
