import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { CartIssue, DeliveryQuote } from '../../../features/cart/api'
import type { Address } from '../../../features/customer/api'
import type { PlaceOrderRequest } from '../../../features/orders/api'
import { cartLine, cartServer, cartView, checkoutPreview, issue } from '../../../test/cartFixtures'
import { type MockHandler, mockApi, type MockRequest, problem } from '../../../test/mockApi'
import { orderView } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const WORK: Address = {
  id: 'a2',
  label: 'Work',
  recipientName: 'Asha Rao',
  phone: '+919876543210',
  line1: 'Work Street 1',
  line2: null,
  landmark: null,
  city: 'Bengaluru',
  state: 'Karnataka',
  postalCode: '560001',
  country: 'IN',
  latitude: 12.97,
  longitude: 77.59,
  isDefault: true,
  createdAt: '2026-11-11T10:00:00Z',
  updatedAt: '2026-11-11T10:00:00Z',
}

const LINES = [
  cartLine({ itemId: 'p1', name: 'Tomato', quantity: 2 }),
  cartLine({ itemId: 'p5', name: 'Basmati Rice', unitLabel: '1 kg', unitPrice: 150, mrp: 180, priceWhenAdded: 150 }),
] // subtotal ₹248

const DELIVERY: Omit<DeliveryQuote, 'fee'> = { distanceKm: 4.6, freeAbove: 499, maxDistanceKm: 15, minimumOrder: 99 }

/** The placed order the order page shows afterwards. */
const PLACED = orderView({ id: 'o42', orderNumber: 'GA-NEW00042', total: 285 })

/** A ProblemDetail reply with extension properties (Spring writes them at the top level). */
function problemWith(status: number, errorCode: string, properties: Record<string, unknown>) {
  const reply = problem(status, errorCode)
  return { ...reply, data: { ...reply.data, ...properties } }
}

function keyOf(call: MockRequest | undefined): string | undefined {
  const value: unknown = call?.headers.get('Idempotency-Key')
  return typeof value === 'string' ? value : undefined
}

/**
 * Checkout with one default address. `state.fee` and `state.issues` drive the live preview, so a test can
 * change what cart-service answers after the order request (a new total, a blocking issue).
 */
function setup(placeOrder: MockHandler) {
  signInAs(['CUSTOMER'])
  const state: { fee: number; issues: CartIssue[] } = { fee: 37, issues: [] }
  const server = cartServer(cartView(LINES))
  const api = mockApi({
    'GET /customers/me/addresses': { status: 200, data: [WORK] },
    'GET /cart/preview': () => ({
      status: 200,
      data: checkoutPreview({ ...server.cart, issues: state.issues }, { delivery: { ...DELIVERY, fee: state.fee } }),
    }),
    'POST /orders': placeOrder,
    'GET /orders/:id': { status: 200, data: PLACED },
    ...server.routes,
  })
  return { api, state }
}

async function readyButton(total: string) {
  const button = await screen.findByRole('button', { name: `Place order · ${total}` })
  await waitFor(() => expect(button).toBeEnabled())
  return button
}

describe('Placing an order from the checkout', () => {
  it('posts the previewed total, address and note with an Idempotency-Key, then opens the order page', async () => {
    const user = userEvent.setup()
    const { api } = setup({ status: 201, data: PLACED })
    const { router } = renderApp('/checkout')

    const button = await readyButton('₹285')
    await user.type(screen.getByLabelText(/Delivery instructions/), '  Ring the bell twice ')
    await user.click(button)

    await waitFor(() => expect(router.state.location.pathname).toBe('/orders/o42'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Order GA-NEW00042' })).toBeInTheDocument()
    // cart-service empties the cart from OrderCreated; the header shows it empty right away.
    expect(screen.getByRole('button', { name: 'Cart, 0 items' })).toBeInTheDocument()

    const calls = api.callsTo('POST /orders')
    expect(calls).toHaveLength(1)
    expect(calls[0]?.body).toEqual({ addressId: 'a2', expectedTotal: 285, note: 'Ring the bell twice' } satisfies PlaceOrderRequest)
    // 8–100 of A-Z a-z 0-9 . _ : - (contract §2)
    expect(keyOf(calls[0])).toMatch(/^[A-Za-z0-9._:-]{8,100}$/)
    // The attempt is over: its key is forgotten.
    expect(sessionStorage.getItem('grocery.checkout.attempt')).toBeNull()
  })

  it('409 TOTAL_CHANGED: refreshes the preview, says what changed and orders the new total with a new key', async () => {
    const user = userEvent.setup()
    const placeOrder: MockHandler = (request) => {
      const { expectedTotal } = request.body as PlaceOrderRequest
      if (expectedTotal !== 299) {
        state.fee = 51 // the delivery fee went up meanwhile: ₹248 + ₹51
        return problemWith(409, 'TOTAL_CHANGED', { expectedTotal: 285, currentTotal: 299 })
      }
      return { status: 201, data: PLACED }
    }
    const { api, state } = setup(placeOrder)
    const { router } = renderApp('/checkout')

    await user.click(await readyButton('₹285'))

    const alert = (await screen.findByText('Your total has changed')).closest('[role="alert"]') as HTMLElement
    expect(alert).toHaveTextContent('The total changed from ₹285 to ₹299')
    expect(router.state.location.pathname).toBe('/checkout')

    // The customer confirms again, now with the new total.
    await user.click(await readyButton('₹299'))
    await waitFor(() => expect(router.state.location.pathname).toBe('/orders/o42'))

    const calls = api.callsTo('POST /orders')
    expect(calls.map((call) => (call.body as PlaceOrderRequest).expectedTotal)).toEqual([285, 299])
    expect(keyOf(calls[1])).not.toBe(keyOf(calls[0]))
  })

  it('409 CHECKOUT_BLOCKED: lists the issues and refreshes the preview, which now blocks the order', async () => {
    const user = userEvent.setup()
    const soldOut = issue('OUT_OF_STOCK', 'p1')
    const { api, state } = setup(() => {
      state.issues = [soldOut]
      return problemWith(409, 'CHECKOUT_BLOCKED', { issues: [soldOut] })
    })
    renderApp('/checkout')

    const button = await readyButton('₹285')
    const previews = api.callsTo('GET /cart/preview').length
    await user.click(button)

    const alert = (await screen.findByText('Your order can’t be placed yet')).closest('[role="alert"]') as HTMLElement
    expect(within(alert).getByText('Tomato is sold out. Remove it to continue.')).toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /cart/preview').length).toBeGreaterThan(previews))
    await waitFor(() => expect(screen.getByRole('button', { name: /^Place order/ })).toBeDisabled())
    expect(screen.getByText('You can’t place this order yet')).toBeInTheDocument()
  })

  it('keeps the Idempotency-Key for a retry after 503 DEPENDENCY_UNAVAILABLE, so the order is created once', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const { api } = setup(() => {
      attempts++
      return attempts === 1 ? problemWith(503, 'DEPENDENCY_UNAVAILABLE', { dependency: 'cart-service' }) : { status: 201, data: PLACED }
    })
    const { router } = renderApp('/checkout')

    await user.click(await readyButton('₹285'))
    expect(await screen.findByText(/We could not check prices and stock just now/)).toBeInTheDocument()
    const firstKey = keyOf(api.callsTo('POST /orders')[0])
    expect(JSON.parse(sessionStorage.getItem('grocery.checkout.attempt') ?? '{}')).toMatchObject({ key: firstKey })

    await user.click(await readyButton('₹285'))
    await waitFor(() => expect(router.state.location.pathname).toBe('/orders/o42'))

    const calls = api.callsTo('POST /orders')
    expect(calls).toHaveLength(2)
    expect(keyOf(calls[1])).toBe(firstKey)
  })

  it('retries once with a new key after 422 IDEMPOTENCY_KEY_REUSED', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const { api } = setup(() => {
      attempts++
      return attempts === 1 ? problem(422, 'IDEMPOTENCY_KEY_REUSED') : { status: 201, data: PLACED }
    })
    const { router } = renderApp('/checkout')

    await user.click(await readyButton('₹285'))
    await waitFor(() => expect(router.state.location.pathname).toBe('/orders/o42'))

    const calls = api.callsTo('POST /orders')
    expect(calls).toHaveLength(2)
    expect(keyOf(calls[1])).toMatch(/^[A-Za-z0-9._:-]{8,100}$/)
    expect(keyOf(calls[1])).not.toBe(keyOf(calls[0]))
  })
})
