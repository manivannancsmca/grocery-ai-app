import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { cartLine, cartServer, cartView, checkoutPreview, issue } from '../../../test/cartFixtures'
import { mockApi } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

function address(id, label, overrides = {}) {
  return {
    id,
    label,
    recipientName: 'Asha Rao',
    phone: '+919876543210',
    line1: `${label} Street 1`,
    line2: null,
    landmark: null,
    city: 'Bengaluru',
    state: 'Karnataka',
    postalCode: '560001',
    country: 'IN',
    latitude: 12.97,
    longitude: 77.59,
    isDefault: false,
    createdAt: '2026-11-11T10:00:00Z',
    updatedAt: '2026-11-11T10:00:00Z',
    ...overrides,
  }
}

// The default address is deliberately not the first one: it must be preselected because it is the default.
const ADDRESSES = [address('a1', 'Home'), address('a2', 'Work', { isDefault: true })]

const LINES = [
  cartLine({ itemId: 'p1', name: 'Tomato', quantity: 2 }), // 2 × ₹49 (MRP ₹60)
  cartLine({ itemId: 'p5', name: 'Basmati Rice', unitLabel: '1 kg', unitPrice: 150, mrp: 180, priceWhenAdded: 150 }),
] // subtotal ₹248, MRP ₹300

const GROCERY_DELIVERY = { freeAbove: 499, maxDistanceKm: 15, minimumOrder: 99 }

/** The hint under "Place order" (its accessible description when nothing blocks the order). */
const PAY_HINT = 'You pay on the next screen. Unpaid orders are cancelled after 15 minutes.'

/** Preview as cart-service would price it: Work is 4.6 km away (₹37), Home is out of the delivery area. */
function previewRoute(currentCart) {
  return (request) => {
    const cart = currentCart()
    if (request.params.addressId === 'a1') {
      return {
        status: 200,
        data: checkoutPreview(
          { ...cart, issues: [...cart.issues, issue('OUT_OF_DELIVERY_AREA')] },
          { delivery: { ...GROCERY_DELIVERY, distanceKm: 18.2, fee: 81 } },
        ),
      }
    }
    return { status: 200, data: checkoutPreview(cart, { delivery: { ...GROCERY_DELIVERY, distanceKm: 4.6, fee: 37 } }) }
  }
}

function setup(cart = cartView(LINES)) {
  signInAs(['CUSTOMER'])
  const server = cartServer(cart)
  const api = mockApi({
    'GET /customers/me/addresses': { status: 200, data: ADDRESSES },
    'GET /cart/preview': previewRoute(() => server.cart),
    ...server.routes,
  })
  return { api, server }
}

function summaryRow(summary, label) {
  return within(summary).getByText(label).closest('div')
}

describe('Checkout page', () => {
  it('preselects the default address and shows the fee, the free-delivery hint and the total', async () => {
    const { api } = setup()
    renderApp('/checkout')

    expect(await screen.findByRole('heading', { level: 1, name: 'Checkout' })).toBeInTheDocument()
    expect(await screen.findByRole('radio', { name: /^Work/ })).toBeChecked()
    expect(screen.getByRole('radio', { name: /^Home/ })).not.toBeChecked()
    await waitFor(() => expect(api.callsTo('GET /cart/preview')[0]?.params).toEqual({ addressId: 'a2' }))

    const summary = await screen.findByRole('region', { name: 'Order summary' })
    // The MRP total is struck through before the item total; the savings show under the total.
    expect(summaryRow(summary, 'Item total (3 items)')).toHaveTextContent('MRP ₹300₹248')
    expect(within(summary).getByText('You save ₹52 on this order')).toBeInTheDocument()
    const fee = summaryRow(summary, /Delivery fee/)
    expect(fee).toHaveTextContent('4.6 km')
    expect(fee).toHaveTextContent('₹37')
    expect(fee).toHaveTextContent('Free on orders above ₹499')
    expect(within(summary).getByText('Add ₹251 more for free delivery')).toBeInTheDocument()
    expect(within(summary).getByText('₹248 of ₹499')).toBeInTheDocument()
    expect(within(summary).getByText('Minimum order ₹99.')).toBeInTheDocument()
    expect(summaryRow(summary, 'Total')).toHaveTextContent('₹285')
    expect(within(summary).getByText('Inclusive of all taxes')).toBeInTheDocument()

    const placeOrder = within(summary).getByRole('button', { name: 'Place order · ₹285' })
    expect(placeOrder).toBeEnabled()
    expect(placeOrder).toHaveAccessibleDescription(PAY_HINT)

    const items = screen.getByRole('list', { name: 'Items in your order' })
    expect(within(items).getByText('Tomato')).toBeInTheDocument()
    expect(within(items).getByText('Basmati Rice')).toBeInTheDocument()
  })

  it('re-fetches the preview for another address; a blocking issue disables "Place order" with the reason', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    renderApp('/checkout')

    await screen.findByRole('region', { name: 'Order summary' })
    await user.click(screen.getByRole('radio', { name: /^Home/ }))

    expect(screen.getByRole('radio', { name: /^Home/ })).toBeChecked()
    await waitFor(() => expect(api.callsTo('GET /cart/preview').at(-1)?.params).toEqual({ addressId: 'a1' }))
    const reason = 'This address is outside the delivery area: it is 18 km away and Green Basket delivers up to 15 km.'
    expect(await screen.findByText(reason)).toBeInTheDocument()
    expect(screen.getByText('You can’t place this order yet')).toBeInTheDocument()
    const placeOrder = screen.getByRole('button', { name: /^Place order/ })
    expect(placeOrder).toBeDisabled()
    expect(placeOrder).toHaveAccessibleDescription(
      new RegExp(`${reason.replace(/[.]/g, '\\.')}.*You pay on the next screen`),
    )

    // Back to the default address: the reason disappears again.
    await user.click(screen.getByRole('radio', { name: /^Work/ }))
    await waitFor(() => expect(screen.queryByText(reason)).not.toBeInTheDocument())
    await waitFor(() => expect(screen.getByRole('button', { name: /^Place order/ })).toBeEnabled())
    expect(screen.getByRole('button', { name: /^Place order/ })).toHaveAccessibleDescription(PAY_HINT)
  })

  it('re-fetches the preview when the cart changes', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    renderApp('/checkout')

    const summary = await screen.findByRole('region', { name: 'Order summary' })
    const previews = api.callsTo('GET /cart/preview').length
    await user.click(screen.getByRole('button', { name: 'Increase quantity of Tomato' }))

    expect(api.callsTo('PUT /cart/items/:itemId')[0]?.body).toEqual({ quantity: 3 })
    await waitFor(() =>
      expect(summaryRow(summary, /^Item total/)).toHaveTextContent('Item total (4 items)MRP ₹360₹297'),
    )
    expect(api.callsTo('GET /cart/preview').length).toBeGreaterThan(previews)
    expect(api.callsTo('GET /cart/preview').at(-1)?.params).toEqual({ addressId: 'a2' })
    expect(summaryRow(summary, 'Total')).toHaveTextContent('₹334')
  })

  it('shows "Free delivery" without a hint once the subtotal qualifies', async () => {
    signInAs(['CUSTOMER'])
    mockApi({
      'GET /customers/me/addresses': { status: 200, data: ADDRESSES },
      'GET /cart/preview': {
        status: 200,
        data: checkoutPreview(cartView([cartLine({ quantity: 11 })]), {
          delivery: { ...GROCERY_DELIVERY, distanceKm: 2.1, fee: 0 },
        }),
      },
      ...cartServer(cartView([cartLine({ quantity: 11 })])).routes,
    })
    renderApp('/checkout')

    const summary = await screen.findByRole('region', { name: 'Order summary' })
    expect(within(summaryRow(summary, /Delivery fee/)).getByText('Free')).toBeInTheDocument()
    expect(within(summary).getByText('Free delivery unlocked on this order')).toBeInTheDocument()
    expect(within(summary).queryByText(/more for free delivery/)).not.toBeInTheDocument()
    expect(summaryRow(summary, 'Total')).toHaveTextContent('₹539')
  })

  it('explains a subtotal below the minimum order', async () => {
    const cart = cartView([cartLine({ unitPrice: 60, mrp: 60, priceWhenAdded: 60 })], {
      issues: [issue('BELOW_MINIMUM_ORDER')],
    })
    signInAs(['CUSTOMER'])
    mockApi({
      'GET /customers/me/addresses': { status: 200, data: ADDRESSES },
      'GET /cart/preview': {
        status: 200,
        data: checkoutPreview(cart, { delivery: { ...GROCERY_DELIVERY, distanceKm: 1.2, fee: 25 } }),
      },
      ...cartServer(cart).routes,
    })
    renderApp('/checkout')

    const message = 'The minimum order is ₹99. Add ₹39 more to place this order.'
    expect(await screen.findByText(message)).toBeInTheDocument()
    const placeOrder = screen.getByRole('button', { name: /^Place order/ })
    expect(placeOrder).toBeDisabled()
    expect(placeOrder).toHaveAccessibleDescription(new RegExp(message.replace(/[.]/g, '\\.')))
    // The cart page's nudge, with how far the order is from the minimum.
    expect(screen.getByText('Add ₹39 more to reach the ₹99 minimum order')).toBeInTheDocument()
    expect(screen.getByText('₹60 of ₹99')).toBeInTheDocument()
  })

  it('asks for an address when there is none, with a link back from the address book', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const cart = cartView(LINES, { issues: [issue('ADDRESS_REQUIRED')] })
    const api = mockApi({
      'GET /customers/me/addresses': { status: 200, data: [] },
      'GET /cart/preview': { status: 200, data: checkoutPreview(cart) },
      ...cartServer(cart).routes,
    })
    const { router } = renderApp('/checkout')

    expect(await screen.findByText('No saved addresses yet')).toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /cart/preview')[0]?.params).toEqual({}))
    const summary = await screen.findByRole('region', { name: 'Order summary' })
    expect(summaryRow(summary, /Delivery fee/)).toHaveTextContent('—')
    const placeOrder = within(summary).getByRole('button', { name: /^Place order/ })
    expect(placeOrder).toBeDisabled()
    expect(placeOrder).toHaveAccessibleDescription(
      /Add a delivery address to see the delivery fee and place your order\./,
    )

    await user.click(screen.getByRole('link', { name: 'Add a delivery address' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/account/addresses'))
    expect(await screen.findByRole('link', { name: 'Back to checkout' })).toHaveAttribute('href', '/checkout')
  })

  it('sends guests to the login page first', async () => {
    const { router } = renderApp('/checkout')

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(router.state.location.state).toEqual({ from: '/checkout' })
  })
})
