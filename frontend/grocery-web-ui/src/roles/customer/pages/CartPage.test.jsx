import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { cartLine, cartServer, cartView, checkoutPreview, issue } from '../../../test/cartFixtures'
import { mockApi, problem } from '../../../test/mockApi'
import { couponOffer } from '../../../test/phase12Fixtures'
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

const GROCERY = { freeAbove: 499, maxDistanceKm: 15, minimumOrder: 99 }

/**
 * cart-service as the cart page sees it: the cart, the preview priced per address (Work 4.6 km → ₹37, Home 6.8 km →
 * ₹45), the store's offers and coupon writes (SAVE10: 10 % of the subtotal).
 */
function setup({ cart = cartView(LINES), addresses = ADDRESSES, previewIssues = () => [], routes = {} } = {}) {
  signInAs(['CUSTOMER'])
  const server = cartServer(cart)
  const state = { coupon: null }
  const api = mockApi({
    'GET /customers/me/addresses': { status: 200, data: addresses },
    'GET /coupons': { status: 200, data: [couponOffer()] },
    'GET /cart/preview': (request) => {
      const current = server.cart
      const chosen =
        addresses.find((entry) => entry.id === request.params.addressId) ??
        addresses.find((entry) => entry.isDefault) ??
        null
      const delivery = chosen
        ? { ...GROCERY, distanceKm: chosen.id === 'a1' ? 6.8 : 4.6, fee: chosen.id === 'a1' ? 45 : 37 }
        : null
      const discount = state.coupon?.valid ? state.coupon.discount : 0
      return {
        status: 200,
        data: checkoutPreview(
          {
            ...current,
            issues: [...current.issues, ...previewIssues(current), ...(chosen ? [] : [issue('ADDRESS_REQUIRED')])],
          },
          {
            delivery,
            coupon: state.coupon,
            discount,
            deliveryFeeDiscount: 0,
            total: Math.round((current.subtotal + (delivery?.fee ?? 0) - discount) * 100) / 100,
          },
        ),
      }
    },
    'PUT /cart/coupon': (request) => {
      const { code } = request.body
      state.coupon = {
        code,
        valid: true,
        discount: Math.round(server.cart.subtotal * 10) / 100,
        deliveryFeeDiscount: 0,
      }
      return { status: 200, data: { ...server.cart, coupon: state.coupon } }
    },
    'DELETE /cart/coupon': () => {
      state.coupon = null
      return { status: 200, data: { ...server.cart, coupon: null } }
    },
    ...server.routes,
    ...routes,
  })
  return { api, server }
}

/** The bill row (`dt` + `dd`) whose label is `label`. */
function billRow(summary, label) {
  return within(summary).getByText(label).closest('div')
}

async function summaryRegion() {
  const summary = await screen.findByRole('region', { name: 'Order summary' })
  await within(summary).findByText(/^Delivery fee/)
  return summary
}

describe('Cart page', () => {
  it('shows the store and its items beside an order summary priced for the default address, with nudges and both checkout buttons', async () => {
    const { api } = setup()
    renderApp('/cart')

    expect(await screen.findByRole('heading', { level: 1, name: 'Your cart' })).toBeInTheDocument()
    const store = await screen.findByRole('region', { name: 'Green Basket' })
    expect(within(store).getByRole('link', { name: 'Green Basket' })).toHaveAttribute('href', '/search?vendorId=v1')
    expect(within(store).getByRole('link', { name: 'Add more items' })).toHaveAttribute('href', '/search?vendorId=v1')
    expect(screen.getByText('3 items from Green Basket')).toBeInTheDocument()

    const items = within(store).getByRole('list', { name: 'Items in your cart' })
    const tomato = within(items).getByText('Tomato').closest('li')
    expect(within(tomato).getByText('₹98')).toBeInTheDocument()
    expect(within(tomato).getByText('Saved ₹22')).toBeInTheDocument()
    expect(within(tomato).getByText('₹60')).toHaveClass('line-through')
    expect(within(tomato).getByText('18% off')).toBeInTheDocument()
    expect(within(tomato).getByRole('group', { name: 'Quantity of Tomato' })).toHaveTextContent('2')

    const summary = await summaryRegion()
    await waitFor(() => expect(api.callsTo('GET /cart/preview')[0]?.params).toEqual({ addressId: 'a2' }))
    expect(within(summary).getByText('Work')).toBeInTheDocument()
    expect(within(summary).getByText('4.6 km away')).toBeInTheDocument()
    expect(billRow(summary, 'Item total (3 items)')).toHaveTextContent('MRP ₹300₹248')
    const fee = billRow(summary, /^Delivery fee/)
    expect(fee).toHaveTextContent('4.6 km')
    expect(fee).toHaveTextContent('₹37')
    expect(fee).toHaveTextContent('Free on orders above ₹499')
    expect(within(summary).getByText('Total').closest('div')).toHaveTextContent('₹285')
    expect(within(summary).getByText('Add ₹251 more for free delivery')).toBeInTheDocument()
    expect(within(summary).getByText('₹248 of ₹499')).toBeInTheDocument()
    expect(within(summary).getByRole('link', { name: 'Add items' })).toHaveAttribute('href', '/search?vendorId=v1')
    expect(within(summary).getByText('You save ₹52 on this order')).toBeInTheDocument()
    expect(within(summary).getByRole('link', { name: 'Proceed to checkout' })).toHaveAttribute('href', '/checkout')

    // Narrow screens: the bar stuck to the bottom repeats the total and the button.
    const bar = screen.getByRole('region', { name: 'Total and checkout' })
    expect(bar).toHaveTextContent('₹285')
    expect(bar).toHaveTextContent('You save ₹52')
    expect(within(bar).getByRole('link', { name: 'Proceed to checkout' })).toHaveAttribute('href', '/checkout')
    // The live region reads the outcome of the price and stock check.
    expect(screen.getByText('Prices and stock checked: everything in your cart is available.')).toBeInTheDocument()
  })

  it('changes the delivery address in a dialog, re-prices the bill and preselects that address at checkout', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    const { router } = renderApp('/cart')

    const summary = await summaryRegion()
    await user.click(within(summary).getByRole('button', { name: 'Change delivery address' }))
    const dialog = await screen.findByRole('dialog', { name: 'Deliver to' })
    expect(within(dialog).getByRole('radio', { name: /^Work/ })).toBeChecked()
    await user.click(within(dialog).getByRole('radio', { name: /^Home/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Done' }))

    await waitFor(() => expect(api.callsTo('GET /cart/preview').at(-1)?.params).toEqual({ addressId: 'a1' }))
    await waitFor(() => expect(billRow(summary, /^Delivery fee/)).toHaveTextContent('₹45'))
    expect(within(summary).getByText('Home')).toBeInTheDocument()
    expect(within(summary).getByText('Total').closest('div')).toHaveTextContent('₹293')

    await user.click(within(summary).getByRole('link', { name: 'Proceed to checkout' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/checkout'))
    expect(await screen.findByRole('radio', { name: /^Home/ })).toBeChecked()
    await waitFor(() => expect(api.callsTo('GET /cart/preview').at(-1)?.params).toEqual({ addressId: 'a1' }))
  })

  it('re-prices the summary after a quantity change', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    renderApp('/cart')

    const summary = await summaryRegion()
    const previews = api.callsTo('GET /cart/preview').length
    await user.click(screen.getByRole('button', { name: 'Increase quantity of Tomato' }))

    expect(api.callsTo('PUT /cart/items/:itemId')[0]).toMatchObject({
      pathParams: { itemId: 'p1' },
      body: { quantity: 3 },
    })
    await waitFor(() => expect(billRow(summary, /^Item total/)).toHaveTextContent('Item total (4 items)'))
    expect(billRow(summary, /^Item total/)).toHaveTextContent('₹297')
    expect(within(summary).getByText('Total').closest('div')).toHaveTextContent('₹334')
    expect(api.callsTo('GET /cart/preview').length).toBeGreaterThan(previews)
  })

  it('blocks checkout below the minimum order and says how much to add', async () => {
    const cart = cartView([cartLine({ unitPrice: 60, mrp: 60, priceWhenAdded: 60 })])
    setup({ cart, previewIssues: (current) => (current.subtotal < 99 ? [issue('BELOW_MINIMUM_ORDER')] : []) })
    renderApp('/cart')

    const summary = await summaryRegion()
    const message = 'The minimum order is ₹99. Add ₹39 more to place this order.'
    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.getByText('One thing to fix before checkout')).toBeInTheDocument()
    expect(within(summary).getByText('Add ₹39 more to reach the ₹99 minimum order')).toBeInTheDocument()
    expect(within(summary).getByText('₹60 of ₹99')).toBeInTheDocument()
    // No free-delivery nudge while the minimum is not reached.
    expect(within(summary).queryByText(/more for free delivery/)).not.toBeInTheDocument()

    const button = within(summary).getByRole('button', { name: 'Proceed to checkout' })
    expect(button).toBeDisabled()
    expect(button).toHaveAccessibleDescription(new RegExp(message.replace(/[.]/g, '\\.')))
    expect(screen.queryByRole('link', { name: 'Proceed to checkout' })).not.toBeInTheDocument()
    const bar = screen.getByRole('region', { name: 'Total and checkout' })
    expect(within(bar).getByRole('button', { name: 'Proceed to checkout' })).toBeDisabled()
    expect(bar).toHaveTextContent('1 issue to fix')
  })

  it('shows issues on their rows, lists what blocks checkout on top and fixes "only N left" in one tap', async () => {
    const user = userEvent.setup()
    const lines = [
      cartLine({ itemId: 'p1', name: 'Tomato', unitPrice: 50, mrp: 60, priceWhenAdded: 90, issue: 'PRICE_CHANGED' }),
      cartLine({ itemId: 'p2', name: 'Onion', quantity: 5, availableQuantity: 2, issue: 'INSUFFICIENT_STOCK' }),
      cartLine({ itemId: 'p3', name: 'Garlic', availableQuantity: 0, issue: 'OUT_OF_STOCK' }),
    ]
    const { api } = setup({
      cart: cartView(lines, {
        issues: [issue('PRICE_CHANGED', 'p1'), issue('INSUFFICIENT_STOCK', 'p2'), issue('OUT_OF_STOCK', 'p3')],
      }),
    })
    renderApp('/cart')

    const items = await screen.findByRole('list', { name: 'Items in your cart' })
    const tomato = within(items).getByText('Tomato').closest('li')
    expect(within(tomato).getByText('Price changed: ₹90 → ₹50')).toHaveClass('text-amber-800')
    expect(within(tomato).getByText('It costs less now.')).toBeInTheDocument()
    expect(tomato).toHaveAccessibleDescription('Price changed: ₹90 → ₹50')

    const garlic = within(items).getByText('Garlic').closest('li')
    expect(within(garlic).getByText('Sold out')).toHaveClass('text-red-700')
    expect(within(garlic).getByText('Remove it to check out.')).toBeInTheDocument()
    expect(within(garlic).getByRole('button', { name: 'Increase quantity of Garlic' })).toBeDisabled()

    // Only what blocks checkout is listed on top; the price change stays on its row.
    expect(screen.getByText('2 things to fix before checkout')).toBeInTheDocument()
    expect(screen.getByText('Garlic is sold out. Remove it to continue.')).toBeInTheDocument()
    expect(screen.getByText('Only 2 of Onion left. Reduce the quantity to 2 or less to continue.')).toBeInTheDocument()
    expect(screen.queryByText('Tomato now costs ₹50 (was ₹90).')).not.toBeInTheDocument()
    expect(
      screen.getByText(/^Prices and stock checked\. Tomato now costs ₹50 \(was ₹90\)\. Only 2 of Onion left/),
    ).toBeInTheDocument()

    const onion = within(items).getByText('Onion').closest('li')
    expect(within(onion).getByText('Only 2 left')).toHaveClass('text-red-700')
    await user.click(within(onion).getByRole('button', { name: 'Reduce Onion to 2' }))

    expect(api.callsTo('PUT /cart/items/:itemId')[0]).toMatchObject({
      pathParams: { itemId: 'p2' },
      body: { quantity: 2 },
    })
    expect(await within(onion).findByRole('group', { name: 'Quantity of Onion' })).toHaveTextContent('2')
    await waitFor(() => expect(screen.getByText('One thing to fix before checkout')).toBeInTheDocument())
  })

  it('says when the store is not taking orders, next to its name and above the items', async () => {
    setup({ cart: cartView(LINES, { issues: [issue('NOT_ACCEPTING_ORDERS')] }) })
    renderApp('/cart')

    const store = await screen.findByRole('region', { name: /Green Basket/ })
    expect(within(store).getByText('Paused new orders')).toBeInTheDocument()
    expect(
      screen.getByText('Green Basket has paused new orders for a while. Please try again later.'),
    ).toBeInTheDocument()
    const summary = await summaryRegion()
    expect(within(summary).getByRole('button', { name: 'Proceed to checkout' })).toBeDisabled()
  })

  it('applies a coupon from the offers dialog, shows it in the bill and removes it', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    renderApp('/cart')

    const summary = await summaryRegion()
    const open = await within(summary).findByRole('button', { name: /^Apply a coupon/ })
    await waitFor(() => expect(open).toHaveTextContent('10% off up to ₹50 with SAVE10'))
    await user.click(open)

    const dialog = await screen.findByRole('dialog', { name: 'Offers & coupons' })
    const offers = await within(dialog).findByRole('list', { name: 'Offers for Green Basket' })
    await user.click(within(offers).getByRole('button', { name: 'Apply coupon SAVE10' }))

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Offers & coupons' })).not.toBeInTheDocument())
    expect(api.callsTo('PUT /cart/coupon')[0]?.body).toEqual({ code: 'SAVE10' })
    expect(await screen.findByText('Coupon SAVE10 applied.')).toBeInTheDocument()
    expect(await within(summary).findByText('Coupon SAVE10 discount')).toBeInTheDocument()
    expect(billRow(summary, 'Coupon SAVE10 discount')).toHaveTextContent('−₹24.80')
    expect(within(summary).getByText('Total').closest('div')).toHaveTextContent('₹260.20')
    expect(within(summary).getByText('You save ₹24.80 off your items.')).toBeInTheDocument()
    expect(within(summary).getByText('You save ₹76.80 on this order')).toBeInTheDocument()

    await user.click(within(summary).getByRole('button', { name: 'Remove coupon SAVE10' }))
    await waitFor(() => expect(api.callsTo('DELETE /cart/coupon')).toHaveLength(1))
    expect(await within(summary).findByRole('button', { name: /^Apply a coupon/ })).toBeInTheDocument()
    await waitFor(() => expect(within(summary).queryByText('Coupon SAVE10 discount')).not.toBeInTheDocument())
  })

  it('asks for an address when there is none, with a way back to the cart', async () => {
    setup({ addresses: [] })
    renderApp('/cart')

    const summary = await summaryRegion()
    expect(within(summary).getByText('No delivery address yet')).toBeInTheDocument()
    expect(within(summary).getByRole('link', { name: 'Add address' })).toHaveAttribute(
      'href',
      '/account/addresses?returnTo=%2Fcart',
    )
    const fee = billRow(summary, /^Delivery fee/)
    expect(fee).toHaveTextContent('—')
    expect(fee).toHaveTextContent('Add a delivery address to see the fee')
    expect(
      await screen.findByText('Add a delivery address to see the delivery fee and place your order.'),
    ).toBeInTheDocument()
    expect(within(summary).getByText('Total before delivery')).toBeInTheDocument()
  })

  it('keeps the cart usable when the delivery fee cannot be worked out', async () => {
    const user = userEvent.setup()
    let failing = true
    const { api } = setup({
      routes: {
        'GET /cart/preview': () =>
          failing
            ? problem(503, 'DEPENDENCY_UNAVAILABLE')
            : {
                status: 200,
                data: checkoutPreview(cartView(LINES), { delivery: { ...GROCERY, distanceKm: 4.6, fee: 37 } }),
              },
      },
    })
    renderApp('/cart')

    const summary = await screen.findByRole('region', { name: 'Order summary' })
    expect(
      await within(summary).findByText('We could not work out the delivery fee just now.', { exact: false }),
    ).toBeInTheDocument()
    expect(within(summary).getByText('Total before delivery').closest('div')).toHaveTextContent('₹248')
    expect(within(summary).getByRole('link', { name: 'Proceed to checkout' })).toHaveAttribute('href', '/checkout')

    failing = false
    await user.click(within(summary).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(within(summary).getByText('Total').closest('div')).toHaveTextContent('₹285'))
    expect(api.callsTo('GET /cart/preview').length).toBeGreaterThan(1)
  })

  it('removes a line, then clears the cart after confirmation and shows ways to start again', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    renderApp('/cart')

    const items = await screen.findByRole('list', { name: 'Items in your cart' })
    await user.click(within(items).getByRole('button', { name: 'Remove Basmati Rice' }))
    await waitFor(() => expect(within(items).queryByText('Basmati Rice')).not.toBeInTheDocument())
    expect(api.callsTo('DELETE /cart/items/:itemId')[0]?.pathParams.itemId).toBe('p5')

    await user.click(screen.getByRole('button', { name: 'Clear cart' }))
    const confirm = await screen.findByRole('dialog', { name: 'Clear your cart?' })
    expect(confirm).toHaveTextContent('This removes all 2 items from Green Basket.')
    await user.click(within(confirm).getByRole('button', { name: 'Clear cart' }))

    expect(await screen.findByRole('heading', { name: 'Your cart is empty' })).toBeInTheDocument()
    expect(api.callsTo('DELETE /cart')).toHaveLength(1)
    expect(screen.getByRole('link', { name: /^Browse products/ })).toHaveAttribute('href', '/search')
    expect(screen.getByRole('link', { name: /^Find restaurants/ })).toHaveAttribute('href', '/restaurants')
    expect(screen.queryByRole('region', { name: 'Total and checkout' })).not.toBeInTheDocument()
  })

  it('explains a cart that could not be loaded and tries again', async () => {
    const user = userEvent.setup()
    let failing = true
    setup({
      routes: {
        'GET /cart': () => (failing ? problem(503, 'CART_UNAVAILABLE') : { status: 200, data: cartView(LINES) }),
      },
    })
    renderApp('/cart')

    expect(await screen.findByRole('heading', { name: 'Could not load your cart' })).toBeInTheDocument()
    expect(screen.getByText('Your cart is temporarily unavailable. Please try again in a moment.')).toBeInTheDocument()
    failing = false
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('list', { name: 'Items in your cart' })).toBeInTheDocument()
  })
})
