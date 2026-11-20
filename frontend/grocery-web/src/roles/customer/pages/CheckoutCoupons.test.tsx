import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { CartCoupon, DeliveryQuote } from '../../../features/cart/api'
import type { Address } from '../../../features/customer/api'
import { cartLine, cartServer, cartView, checkoutPreview, issue } from '../../../test/cartFixtures'
import { type MockHandler, mockApi, problem } from '../../../test/mockApi'
import { confirmedOrder } from '../../../test/orderFixtures'
import { couponOffer } from '../../../test/phase12Fixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const HOME: Address = {
  id: 'a1',
  label: 'Home',
  recipientName: 'Asha Rao',
  phone: '+919876543210',
  line1: '12 MG Road',
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
  cartLine({ itemId: 'p1', name: 'Tomato', quantity: 2 }), // 2 × ₹49
  cartLine({ itemId: 'p5', name: 'Basmati Rice', unitLabel: '1 kg', unitPrice: 150, mrp: 180, priceWhenAdded: 150 }),
] // subtotal ₹248

const DELIVERY: DeliveryQuote = { distanceKm: 4.6, fee: 37, freeAbove: 499, maxDistanceKm: 15, minimumOrder: 99 }

const OFFERS = [
  couponOffer(),
  couponOffer({ code: 'FREESHIP', name: 'Free delivery weekend', description: null, discountType: 'FREE_DELIVERY', value: null, maxDiscount: null, minOrderSubtotal: 0 }),
]

function notApplicable(status: number, reason: string, extra: Record<string, unknown> = {}) {
  const reply = problem(status, 'COUPON_NOT_APPLICABLE', 'The coupon cannot be used')
  return { ...reply, data: { ...reply.data, reason, ...extra } }
}

/**
 * cart-service with a coupon: `PUT /cart/coupon` evaluates SAVE10 (10 % up to ₹50), FREESHIP (free delivery) and
 * BIGSPEND (min ₹499); the preview carries the coupon, the discounts, the total and the blocking issue.
 */
function setup(initialCoupon: CartCoupon | null = null, routes: Record<string, MockHandler> = {}) {
  signInAs(['CUSTOMER'])
  const server = cartServer(cartView(LINES))
  const state = { coupon: initialCoupon }
  const api = mockApi({
    'GET /customers/me/addresses': { status: 200, data: [HOME] },
    'GET /coupons': { status: 200, data: OFFERS },
    'GET /cart/preview': () => {
      const cart = server.cart
      const coupon = state.coupon
      const discount = coupon?.valid ? coupon.discount : 0
      const deliveryFeeDiscount = coupon?.valid ? coupon.deliveryFeeDiscount : 0
      const issues = coupon && !coupon.valid ? [...cart.issues, issue('COUPON_NOT_APPLICABLE')] : cart.issues
      return {
        status: 200,
        data: checkoutPreview(
          { ...cart, issues },
          {
            delivery: DELIVERY,
            coupon,
            discount,
            deliveryFeeDiscount,
            total: Math.round((cart.subtotal + DELIVERY.fee - discount - deliveryFeeDiscount) * 100) / 100,
          },
        ),
      }
    },
    'PUT /cart/coupon': (request) => {
      const { code } = request.body as { code: string }
      if (code === 'SAVE10') state.coupon = { code, valid: true, discount: 24.8, deliveryFeeDiscount: 0 }
      else if (code === 'FREESHIP') state.coupon = { code, valid: true, discount: 0, deliveryFeeDiscount: 37 }
      else if (code === 'BIGSPEND') return notApplicable(422, 'COUPON_MIN_ORDER', { minOrderSubtotal: 499 })
      else return notApplicable(422, 'COUPON_NOT_FOUND')
      return { status: 200, data: { ...server.cart, coupon: state.coupon } }
    },
    'DELETE /cart/coupon': () => {
      state.coupon = null
      return { status: 200, data: server.cart }
    },
    ...server.routes,
    ...routes,
  })
  return { api, state }
}

function summaryRow(summary: HTMLElement, label: string | RegExp): HTMLElement {
  return within(summary).getByText(label).closest('div') as HTMLElement
}

describe('Coupons at checkout', () => {
  it('lists the store’s offers; applying one shows the discount line and the new total; removing it restores the total', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    renderApp('/checkout')

    const panel = await screen.findByRole('region', { name: 'Offers & coupons' })
    const offers = await within(panel).findByRole('list', { name: 'Offers for Green Basket' })
    expect(api.callsTo('GET /coupons')[0]?.params).toEqual({ storeId: 'v1' })
    expect(offers).toHaveTextContent('SAVE10')
    expect(offers).toHaveTextContent('10% off up to ₹50')
    expect(offers).toHaveTextContent('Min. order ₹199')
    expect(offers).toHaveTextContent('Free delivery')
    const summary = await screen.findByRole('region', { name: 'Order summary' })
    expect(summaryRow(summary, 'Total')).toHaveTextContent('₹285')

    await user.click(within(offers).getByRole('button', { name: 'Apply coupon SAVE10' }))
    await waitFor(() => expect(api.callsTo('PUT /cart/coupon')[0]?.body).toEqual({ code: 'SAVE10' }))
    expect(await screen.findByText('Coupon SAVE10 applied.')).toBeInTheDocument()
    expect(await within(summary).findByText('Coupon SAVE10 discount')).toBeInTheDocument()
    expect(summaryRow(summary, 'Coupon SAVE10 discount')).toHaveTextContent('−₹24.80')
    expect(summaryRow(summary, 'Total')).toHaveTextContent('₹260.20')
    expect(within(summary).getByRole('button', { name: 'Place order · ₹260.20' })).toBeEnabled()
    const applied = within(panel).getByRole('status')
    expect(applied).toHaveTextContent('SAVE10 applied')
    expect(applied).toHaveTextContent('You save ₹24.80 off your items.')
    expect(within(offers).getByText('Applied')).toBeInTheDocument()

    await user.click(within(panel).getByRole('button', { name: 'Remove coupon SAVE10' }))
    await waitFor(() => expect(api.callsTo('DELETE /cart/coupon')).toHaveLength(1))
    expect(await within(panel).findByRole('textbox', { name: 'Coupon code' })).toBeInTheDocument()
    await waitFor(() => expect(within(summary).queryByText('Coupon SAVE10 discount')).not.toBeInTheDocument())
    expect(summaryRow(summary, 'Total')).toHaveTextContent('₹285')
  })

  it('applies a typed code (upper-cased) and explains every refusal; a free-delivery coupon takes the fee off', async () => {
    const user = userEvent.setup()
    const { api } = setup()
    renderApp('/checkout')

    const panel = await screen.findByRole('region', { name: 'Offers & coupons' })
    const input = within(panel).getByRole('textbox', { name: 'Coupon code' })
    const apply = within(panel).getByRole('button', { name: 'Apply' })

    await user.click(apply)
    expect(within(panel).getByRole('alert')).toHaveTextContent('Enter a coupon code.')

    await user.type(input, 'ab')
    await user.click(apply)
    expect(within(panel).getByRole('alert')).toHaveTextContent('Coupon codes have 4–20 letters, digits or dashes.')
    expect(api.callsTo('PUT /cart/coupon')).toHaveLength(0)
    expect(input).toHaveAttribute('aria-invalid', 'true')

    await user.clear(input)
    await user.type(input, 'nope')
    await user.click(apply)
    expect(await within(panel).findByRole('alert')).toHaveTextContent('There is no coupon with the code NOPE. Check the spelling.')
    expect(api.callsTo('PUT /cart/coupon').at(-1)?.body).toEqual({ code: 'NOPE' })

    await user.clear(input)
    await user.type(input, 'bigspend')
    await user.click(apply)
    expect(await within(panel).findByRole('alert')).toHaveTextContent('This coupon needs a minimum order of ₹499. Add ₹251 more to use it.')

    await user.clear(input)
    await user.type(input, ' freeship ')
    await user.click(apply)
    await waitFor(() => expect(api.callsTo('PUT /cart/coupon').at(-1)?.body).toEqual({ code: 'FREESHIP' }))
    const summary = screen.getByRole('region', { name: 'Order summary' })
    expect(await within(summary).findByText('Coupon FREESHIP: free delivery')).toBeInTheDocument()
    expect(summaryRow(summary, 'Coupon FREESHIP: free delivery')).toHaveTextContent('−₹37')
    expect(summaryRow(summary, 'Total')).toHaveTextContent('₹248')
    expect(within(panel).getByRole('status')).toHaveTextContent('Delivery is free — you save ₹37.')
    expect(within(panel).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('blocks the order while an applied coupon no longer applies, says why, and lets the customer remove it', async () => {
    const user = userEvent.setup()
    setup({ code: 'SAVE10', valid: false, discount: 0, deliveryFeeDiscount: 0, reason: 'COUPON_EXPIRED' })
    renderApp('/checkout')

    const summary = await screen.findByRole('region', { name: 'Order summary' })
    expect(await within(summary).findByText('You can’t place this order yet')).toBeInTheDocument()
    expect(
      within(summary).getByText('Coupon SAVE10 can’t be used any more. This coupon has expired. Remove it or change your cart to continue.'),
    ).toBeInTheDocument()
    expect(within(summary).getByRole('button', { name: /^Place order/ })).toBeDisabled()
    expect(within(summary).queryByText(/Coupon SAVE10 discount/)).not.toBeInTheDocument()

    const panel = screen.getByRole('region', { name: 'Offers & coupons' })
    expect(within(panel).getByRole('status')).toHaveTextContent('SAVE10 can’t be used')
    expect(within(panel).getByRole('status')).toHaveTextContent('This coupon has expired.')
    await user.click(within(panel).getByRole('button', { name: 'Remove coupon SAVE10' }))
    await waitFor(() => expect(within(summary).getByRole('button', { name: 'Place order · ₹285' })).toBeEnabled())
    expect(within(summary).queryByText('You can’t place this order yet')).not.toBeInTheDocument()
  })

  it('explains a coupon refused when the order is placed (nothing ordered)', async () => {
    const user = userEvent.setup()
    const { api } = setup(
      { code: 'SAVE10', valid: true, discount: 24.8, deliveryFeeDiscount: 0 },
      { 'POST /orders': notApplicable(409, 'COUPON_USAGE_LIMIT') },
    )
    renderApp('/checkout')

    const summary = await screen.findByRole('region', { name: 'Order summary' })
    await user.click(await within(summary).findByRole('button', { name: 'Place order · ₹260.20' }))
    expect(await within(summary).findByRole('alert')).toHaveTextContent(
      'Coupon SAVE10 could not be used, so nothing was ordered. This coupon has been fully used up. Remove it or try another coupon.',
    )
    expect(api.callsTo('POST /orders')[0]?.body).toMatchObject({ expectedTotal: 260.2 })
  })

  it('shows the coupon discount in an order’s bill', async () => {
    signInAs(['CUSTOMER'])
    mockApi({
      'GET /cart': { status: 200, data: cartView([]) },
      'GET /orders/:id': { status: 200, data: confirmedOrder({ couponCode: 'SAVE10', discount: 10, deliveryFeeDiscount: 0, total: 115 }) },
    })
    renderApp('/orders/o1')

    const bill = await screen.findByLabelText('Order totals')
    expect(within(bill).getByText('Coupon SAVE10 discount').closest('div')).toHaveTextContent('−₹10')
    expect(within(bill).getByText('Total').closest('div')).toHaveTextContent('₹115')
  })
})
