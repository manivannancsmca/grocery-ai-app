import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { OrderView } from '../../../features/orders/api'
import { orderPollInterval } from '../../../features/orders/hooks'
import type { RazorpayConstructor, RazorpayFailureResponse, RazorpayOptions } from '../../../features/payments/razorpay'
import { cartView } from '../../../test/cartFixtures'
import { type MockHandler, mockApi, problem } from '../../../test/mockApi'
import { mockEventStream } from '../../../test/mockEventStream'
import { checkoutSession, confirmedOrder, orderView, paymentView } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

/** Stand-in for checkout.js's `window.Razorpay`: records what the app opens, so tests can play the customer. */
class FakeRazorpay {
  static instances: FakeRazorpay[] = []
  readonly options: RazorpayOptions
  opened = false
  private failed: ((response: RazorpayFailureResponse) => void) | null = null

  constructor(options: RazorpayOptions) {
    this.options = options
    FakeRazorpay.instances.push(this)
  }

  on(event: 'payment.failed', callback: (response: RazorpayFailureResponse) => void) {
    if (event === 'payment.failed') this.failed = callback
  }

  open() {
    this.opened = true
  }

  fail(description: string) {
    this.failed?.({ error: { code: 'BAD_REQUEST_ERROR', description } })
  }
}

const SIGNED_IDS = { razorpayOrderId: 'order_SIM123', razorpayPaymentId: 'pay_SIM456', razorpaySignature: 'sig_abc' }

/** The order's status line (its title names it) and the hero around it. */
const statusNamed = (name: string) => screen.getByRole('status', { name })
const hero = () => screen.getByRole('region', { name: 'Order GA-7K3F9Q2M' })

/** Opens "View all updates" and returns the full history. */
async function openUpdates(user: ReturnType<typeof userEvent.setup>) {
  const toggle = screen.getByRole('button', { name: /View all updates/ })
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await user.click(toggle)
  expect(screen.getByRole('button', { name: /Hide updates/ })).toHaveAttribute('aria-expanded', 'true')
  return screen.getByRole('list', { name: 'Status history' })
}

/**
 * order-service + payment-service for one order. `server.order` is what `GET /orders/o1` answers; verify
 * "captures" the payment, and the next read of the order is CONFIRMED.
 */
function setup(initial: OrderView, routes: Record<string, MockHandler> = {}) {
  signInAs(['CUSTOMER'])
  const server = { order: initial }
  const api = mockApi({
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /orders/:id': (request) =>
      request.pathParams.id === server.order.id ? { status: 200, data: server.order } : problem(404, 'ORDER_NOT_FOUND', 'Order not found'),
    'GET /payments/orders/:orderId/checkout': { status: 200, data: checkoutSession() },
    'POST /payments/simulator/orders/:orderId/pay': (request) =>
      (request.body as { outcome: string }).outcome === 'SUCCESS' ? { status: 200, data: SIGNED_IDS } : { status: 200 },
    'POST /payments/razorpay/verify': () => {
      server.order = confirmedOrder()
      return { status: 200, data: paymentView() }
    },
    ...routes,
  })
  return { api, server }
}

beforeEach(() => {
  FakeRazorpay.instances = []
})

afterEach(() => {
  delete window.Razorpay
})

describe('Order page', () => {
  it('moves a new order from "Checking stock…" to awaiting payment on a live ORDER_UPDATED (no polling), with a countdown and the bill', async () => {
    const user = userEvent.setup()
    const sse = mockEventStream()
    const placed = orderView({
      status: 'CREATED',
      payment: { ...orderView().payment, status: 'NOT_STARTED' },
      timeline: [{ status: 'CREATED', at: '2026-11-11T10:00:00Z', reason: null }],
    })
    const { api, server } = setup(placed)
    renderApp('/orders/o1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Order GA-7K3F9Q2M' })).toBeInTheDocument()
    expect(statusNamed('Checking stock…')).toHaveTextContent('Green Basket is reserving your items.')
    expect(screen.queryByRole('button', { name: /Pay now/ })).not.toBeInTheDocument()
    const stream = await sse.connection()
    expect(await screen.findByRole('img', { name: 'Live updates on' })).toBeInTheDocument()

    // While the stream is live the order is not polled (Phase 6 re-read it every 2 s).
    const reads = api.callsTo('GET /orders/:id').length
    server.order = orderView()
    await act(() => new Promise((resolve) => setTimeout(resolve, 2_200)))
    expect(api.callsTo('GET /orders/:id')).toHaveLength(reads)
    expect(screen.getByText('Checking stock…')).toBeInTheDocument()

    // Stock is reserved; the stream says the order moved on, and it is read once.
    stream.signal({ type: 'ORDER_UPDATED', data: { orderId: 'o1', status: 'AWAITING_PAYMENT', event: 'OrderAwaitingPayment' } })
    expect(await screen.findByRole('button', { name: 'Pay now · ₹125' })).toBeEnabled()
    expect(api.callsTo('GET /orders/:id')).toHaveLength(reads + 1)
    expect(screen.queryByText('Checking stock…')).not.toBeInTheDocument()
    expect(screen.getByRole('timer', { name: 'Time left to pay' })).toHaveTextContent(/^1[34]:\d\d$/)

    const totals = screen.getByText(/^Item total/).closest('dl') as HTMLElement
    expect(totals).toHaveTextContent('Item total (2 items)₹100')
    expect(totals).toHaveTextContent('Delivery fee · 4.6 km₹25')
    expect(totals).toHaveTextContent('Total₹125')
    expect(within(screen.getByRole('list', { name: 'Ordered items' })).getByText('Basmati Rice')).toBeInTheDocument()
    expect(screen.getByText(/Ring the bell twice/)).toBeInTheDocument()
    // The history is behind "View all updates" (the progress row shows the steps).
    expect(screen.queryByRole('list', { name: 'Status history' })).not.toBeInTheDocument()
    expect(within(await openUpdates(user)).getAllByRole('listitem')).toHaveLength(2)
  }, 15_000)

  it('polls only as a slow fallback while live updates are down, and stops on a terminal state', () => {
    expect(orderPollInterval(orderView({ status: 'CREATED' }), false)).toBe(30_000)
    expect(orderPollInterval(orderView(), false)).toBe(30_000)
    expect(orderPollInterval(confirmedOrder(), false)).toBe(30_000)
    // Live: ORDER_UPDATED signals refetch the order instead.
    expect(orderPollInterval(orderView(), true)).toBe(false)
    expect(orderPollInterval(confirmedOrder(), true)).toBe(false)
    expect(orderPollInterval(orderView({ status: 'REJECTED' }), false)).toBe(false)
    expect(orderPollInterval(orderView({ status: 'CANCELLED', payment: { ...orderView().payment, status: 'EXPIRED' } }), false)).toBe(false)
    // A paid order that was cancelled is followed until its refund shows.
    expect(orderPollInterval(orderView({ status: 'CANCELLED', payment: { ...orderView().payment, status: 'REFUND_PENDING' } }), false)).toBe(30_000)
  })

  it('pays in simulator mode: waits while the payment is not ready, then posts the signed ids to verify', async () => {
    const user = userEvent.setup()
    let sessions = 0
    const { api } = setup(orderView(), {
      'GET /payments/orders/:orderId/checkout': () => {
        sessions++
        return sessions === 1 ? problem(409, 'PAYMENT_NOT_READY') : { status: 200, data: checkoutSession() }
      },
    })
    renderApp('/orders/o1')

    await user.click(await screen.findByRole('button', { name: 'Pay now · ₹125' }))
    expect(screen.getByRole('button', { name: 'Preparing payment…' })).toBeDisabled()

    const dialog = await screen.findByRole('dialog', { name: /Test payment/ }, { timeout: 4_000 })
    expect(within(dialog).getByText('Local test')).toBeInTheDocument()
    expect(dialog).toHaveTextContent('no real money moves')
    expect(dialog).toHaveTextContent('₹125.00')
    expect(sessions).toBe(2)

    await user.click(within(dialog).getByRole('button', { name: 'Pay successfully' }))

    expect(await screen.findByText('Order confirmed')).toBeInTheDocument()
    expect(api.callsTo('POST /payments/simulator/orders/:orderId/pay')[0]?.body).toEqual({ outcome: 'SUCCESS' })
    expect(api.callsTo('POST /payments/simulator/orders/:orderId/pay')[0]?.pathParams.orderId).toBe('o1')
    expect(api.callsTo('POST /payments/razorpay/verify')[0]?.body).toEqual(SIGNED_IDS)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Pay now/ })).not.toBeInTheDocument()
    expect(document.querySelector('script[src*="checkout.razorpay.com"]')).toBeNull()
  })

  it('does not offer "Pay now" again while order-service is still confirming a verified payment', async () => {
    const user = userEvent.setup()
    // verify succeeds, but the order still reads AWAITING_PAYMENT / PENDING (PaymentCaptured is on its way).
    const { api } = setup(orderView(), { 'POST /payments/razorpay/verify': { status: 200, data: paymentView() } })
    renderApp('/orders/o1')

    await user.click(await screen.findByRole('button', { name: 'Pay now · ₹125' }))
    const dialog = await screen.findByRole('dialog', { name: /Test payment/ })
    await user.click(within(dialog).getByRole('button', { name: 'Pay successfully' }))

    const panel = screen.getByRole('region', { name: 'Pay ₹125 to confirm your order' })
    expect(await within(panel).findByText(/Payment received\. Confirming your order…/)).toBeInTheDocument()
    expect(api.callsTo('POST /payments/razorpay/verify')).toHaveLength(1)
    expect(within(panel).queryByRole('button', { name: /Pay now/ })).not.toBeInTheDocument()
    expect(within(panel).queryByRole('timer')).not.toBeInTheDocument()
  })

  it('shows the failure when the test payment is declined and lets the customer try again', async () => {
    const user = userEvent.setup()
    const { api, server } = setup(orderView())
    renderApp('/orders/o1')

    await user.click(await screen.findByRole('button', { name: 'Pay now · ₹125' }))
    const dialog = await screen.findByRole('dialog', { name: /Test payment/ })
    server.order = orderView({
      payment: { ...orderView().payment, lastFailure: { code: 'BAD_REQUEST_ERROR', description: 'Payment declined', at: '2026-11-11T10:02:00Z' } },
    })
    await user.click(within(dialog).getByRole('button', { name: 'Decline' }))

    expect(await screen.findByText('The test payment was declined. You can try again.')).toBeInTheDocument()
    expect(api.callsTo('POST /payments/simulator/orders/:orderId/pay')[0]?.body).toEqual({ outcome: 'FAIL' })
    expect(api.callsTo('POST /payments/razorpay/verify')).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Pay now · ₹125' })).toBeEnabled()
  })

  it('shows the last declined attempt of a payable order', async () => {
    setup(
      orderView({
        payment: { ...orderView().payment, lastFailure: { code: 'BAD_REQUEST_ERROR', description: 'Card declined by the bank.', at: '2026-11-11T10:02:00Z' } },
      }),
    )
    renderApp('/orders/o1')

    expect(await screen.findByText('Your last payment attempt failed')).toBeInTheDocument()
    expect(screen.getByText(/Card declined by the bank\./)).toBeInTheDocument()
  })

  it('opens Razorpay Checkout with the session, shows a decline and verifies the successful payment', async () => {
    const user = userEvent.setup()
    window.Razorpay = FakeRazorpay as unknown as RazorpayConstructor
    const session = checkoutSession({ provider: 'RAZORPAY', keyId: 'rzp_test_abc', razorpayOrderId: 'order_ABC' })
    const { api } = setup(orderView(), { 'GET /payments/orders/:orderId/checkout': { status: 200, data: session } })
    renderApp('/orders/o1')

    await user.click(await screen.findByRole('button', { name: 'Pay now · ₹125' }))
    await waitFor(() => expect(FakeRazorpay.instances[0]?.opened).toBe(true))
    const checkout = FakeRazorpay.instances[0] as FakeRazorpay
    expect(checkout.options).toMatchObject({
      key: 'rzp_test_abc',
      order_id: 'order_ABC',
      amount: 12500,
      currency: 'INR',
      name: 'Grocery AI',
      description: 'Order GA-7K3F9Q2M',
      prefill: { name: 'Asha Rao', email: 'asha@example.com', contact: '+919876543210' },
    })
    expect(screen.getByRole('button', { name: 'Waiting for your payment…' })).toBeDisabled()

    act(() => checkout.fail('Your card was declined.'))
    expect(await screen.findByText('Your card was declined.')).toBeInTheDocument()

    // Razorpay keeps its window open; the second try succeeds.
    act(() => checkout.options.handler({ razorpay_order_id: 'order_ABC', razorpay_payment_id: 'pay_OK', razorpay_signature: 'sig_ok' }))

    expect(await screen.findByText('Order confirmed')).toBeInTheDocument()
    expect(api.callsTo('POST /payments/razorpay/verify')[0]?.body).toEqual({
      razorpayOrderId: 'order_ABC',
      razorpayPaymentId: 'pay_OK',
      razorpaySignature: 'sig_ok',
    })
    expect(document.querySelector('script[src*="checkout.razorpay.com"]')).toBeNull()
  })

  it('refreshes the order when the payment window is already over (409 PAYMENT_EXPIRED)', async () => {
    const user = userEvent.setup()
    const { api, server } = setup(orderView(), {
      'GET /payments/orders/:orderId/checkout': () => {
        server.order = orderView({
          status: 'CANCELLED',
          cancellable: false,
          payment: { ...orderView().payment, status: 'EXPIRED' },
          cancellation: { reason: 'PAYMENT_TIMEOUT', note: null, by: 'SYSTEM', at: '2026-11-11T10:15:00Z' },
        })
        return problem(409, 'PAYMENT_EXPIRED')
      },
    })
    renderApp('/orders/o1')

    await user.click(await screen.findByRole('button', { name: 'Pay now · ₹125' }))

    // Only a fresh read of the order shows the cancellation.
    expect(await screen.findByText('The order was cancelled because it was not paid within 15 minutes.')).toBeInTheDocument()
    expect(api.callsTo('GET /payments/orders/:orderId/checkout')).toHaveLength(1)
    expect(screen.getByText('You were not charged.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
  })

  it('cancels with an optional reason after confirmation', async () => {
    const user = userEvent.setup()
    const { api, server } = setup(orderView(), {
      'POST /orders/:id/cancel': (request) => {
        const { reason } = request.body as { reason?: string }
        server.order = orderView({
          status: 'CANCELLED',
          cancellable: false,
          payment: { ...orderView().payment, status: 'NOT_REQUIRED' },
          cancellation: { reason: 'CUSTOMER_CANCELLED', note: reason ?? null, by: 'CUSTOMER', at: '2026-11-11T10:05:00Z' },
        })
        return { status: 200, data: server.order }
      },
    })
    renderApp('/orders/o1')

    await user.click(await screen.findByRole('button', { name: 'Cancel order' }))
    const dialog = await screen.findByRole('dialog', { name: 'Cancel order GA-7K3F9Q2M?' })
    expect(dialog).toHaveTextContent('You have not been charged for this order.')
    await user.type(within(dialog).getByLabelText(/Reason/), 'Ordered by mistake')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }))

    expect(await screen.findByText('Order cancelled.')).toBeInTheDocument()
    expect(api.callsTo('POST /orders/:id/cancel')[0]?.body).toEqual({ reason: 'Ordered by mistake' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('You cancelled this order.')).toBeInTheDocument()
    expect(screen.getByText('“Ordered by mistake”')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
  })

  it('refreshes the order and explains 409 ORDER_NOT_CANCELLABLE', async () => {
    const user = userEvent.setup()
    const { api } = setup(orderView(), { 'POST /orders/:id/cancel': problem(409, 'ORDER_NOT_CANCELLABLE') })
    renderApp('/orders/o1')

    await user.click(await screen.findByRole('button', { name: 'Cancel order' }))
    const dialog = await screen.findByRole('dialog', { name: 'Cancel order GA-7K3F9Q2M?' })
    const reads = api.callsTo('GET /orders/:id').length
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }))

    expect(await screen.findByText('This order can no longer be cancelled. It has been refreshed.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /orders/:id').length).toBeGreaterThan(reads))
  })

  it('lists the out-of-stock items of a rejected order', async () => {
    setup(
      orderView({
        status: 'REJECTED',
        cancellable: false,
        payment: { ...orderView().payment, status: 'NOT_REQUIRED', dueAt: null },
        rejection: { reason: 'OUT_OF_STOCK', unavailableItems: [{ itemId: 'p5', name: 'Basmati Rice', requested: 2, available: 1 }] },
      }),
    )
    renderApp('/orders/o1')

    expect(await screen.findByText('Some items are out of stock')).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Unavailable items' })).getByText('Basmati Rice: you asked for 2, only 1 is left.')).toBeInTheDocument()
    expect(screen.getByText('Nothing was reserved and you were not charged.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Pay now/ })).not.toBeInTheDocument()
  })

  it('shows the refund of a cancelled paid order', async () => {
    setup(
      confirmedOrder({
        status: 'CANCELLED',
        cancellable: false,
        payment: { ...confirmedOrder().payment, status: 'REFUNDED', refundedAmount: 125 },
        cancellation: { reason: 'ADMIN_CANCELLED', note: 'Store closed early', by: 'ADMIN', at: '2026-11-11T10:20:00Z' },
      }),
    )
    renderApp('/orders/o1')

    expect(await screen.findByText('Our support team cancelled this order.')).toBeInTheDocument()
    expect(screen.getByText('₹125 has been refunded to your original payment method.')).toBeInTheDocument()
    // Said once: the status line, not a badge as well.
    expect(statusNamed('Order cancelled')).toHaveTextContent('“Store closed early”')
    const payment = screen.getByRole('group', { name: 'Total and payment' })
    expect(payment).toHaveTextContent('Total ₹125')
    expect(payment).toHaveTextContent('Refunded')
    expect(screen.getByRole('link', { name: 'Shop at Green Basket again' })).toBeInTheDocument()
    expect(screen.getByText(/^Item total/).closest('dl')).toHaveTextContent('Refunded₹125')
  })

  it('shows an accepted order with its preparation time; it can no longer be cancelled here', async () => {
    const user = userEvent.setup()
    const sse = mockEventStream()
    const accepted = confirmedOrder({
      type: 'RESTAURANT',
      status: 'ACCEPTED',
      cancellable: false,
      acceptedAt: '2026-11-11T10:05:00Z',
      readyInMinutes: 25,
      readyBy: '2026-11-11T10:30:00Z',
      timeline: [...confirmedOrder().timeline, { status: 'ACCEPTED', at: '2026-11-11T10:05:00Z', reason: null }],
    })
    const { server } = setup(accepted)
    renderApp('/orders/o1')

    expect(await screen.findByRole('heading', { level: 2, name: 'Accepted — ready in about 25 min' })).toBeInTheDocument()
    expect(statusNamed('Accepted — ready in about 25 min')).toHaveTextContent(/Green Basket accepted your order at .*. It should be ready by /)
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
    expect(screen.getByText(/has already accepted this order, so it can no longer be cancelled here/)).toHaveTextContent('Contact support to cancel.')
    expect(within(await openUpdates(user)).getAllByRole('listitem')).toHaveLength(4)
    const stream = await sse.connection()

    // The kitchen starts, then finishes (live ORDER_UPDATED).
    server.order = { ...accepted, status: 'PREPARING' }
    stream.signal({ type: 'ORDER_UPDATED', data: { orderId: 'o1', status: 'PREPARING', event: 'OrderPreparing' } })
    expect(await screen.findByText('Preparing your food')).toBeInTheDocument()

    server.order = { ...accepted, status: 'READY_FOR_PICKUP' }
    stream.signal({ type: 'ORDER_UPDATED', data: { orderId: 'o1', status: 'READY_FOR_PICKUP', event: 'OrderReadyForPickup' } })
    expect(await screen.findByText('Your food is ready and waiting for a delivery partner.')).toBeInTheDocument()
    expect(statusNamed('Ready for pickup')).toBeInTheDocument()
    // The open history follows the order.
    expect(within(screen.getByRole('list', { name: 'Status history' })).getAllByRole('listitem')).toHaveLength(4)
    expect(screen.getByText(/Contact support to cancel\./)).toBeInTheDocument()
  })

  it('uses the grocery wording while a store packs the order', async () => {
    setup(confirmedOrder({ status: 'PREPARING', cancellable: false, readyBy: null }))
    renderApp('/orders/o1')

    expect(await screen.findByText('Packing your order')).toBeInTheDocument()
    expect(screen.getByText('Green Basket is packing your order.')).toBeInTheDocument()
  })

  it('still offers "Cancel order" while the store has not accepted yet', async () => {
    setup(confirmedOrder())
    renderApp('/orders/o1')

    expect(await screen.findByText('Order confirmed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel order' })).toBeInTheDocument()
    expect(screen.queryByText(/Contact support to cancel/)).not.toBeInTheDocument()
  })

  it('explains a store rejection in plain words, with the refund', async () => {
    setup(
      confirmedOrder({
        status: 'CANCELLED',
        cancellable: false,
        cancellation: { reason: 'STORE_REJECTED', note: 'TOO_BUSY: Kitchen is full', by: 'STORE', at: '2026-11-11T10:04:00Z' },
      }),
    )
    renderApp('/orders/o1')

    expect(await screen.findByText('The store could not accept your order, so it was cancelled.')).toBeInTheDocument()
    expect(screen.getByText('Reason: Too busy right now: Kitchen is full')).toBeInTheDocument()
    expect(screen.getByText(/A full refund of ₹125 is on its way/)).toBeInTheDocument()
    expect(screen.queryByText(/Contact support to cancel/)).not.toBeInTheDocument()
  })

  it('explains a store that did not respond in time', async () => {
    setup(
      confirmedOrder({
        status: 'CANCELLED',
        cancellable: false,
        payment: { ...confirmedOrder().payment, status: 'REFUND_PENDING' },
        cancellation: { reason: 'STORE_NO_RESPONSE', note: null, by: 'SYSTEM', at: '2026-11-11T10:08:00Z' },
      }),
    )
    renderApp('/orders/o1')

    expect(await screen.findByText('The store did not respond in time, so your order was cancelled.')).toBeInTheDocument()
    expect(screen.getByText(/Your refund of ₹125 is on its way/)).toBeInTheDocument()
  })

  it('says when the order does not exist (or belongs to someone else)', async () => {
    setup(orderView())
    renderApp('/orders/someone-elses')

    expect(await screen.findByText('Order not found')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'See my orders' })).toHaveAttribute('href', '/orders')
  })
})

describe('Order page — one status, details in a disclosure', () => {
  const hoursAgo = (hours: number) => new Date(Date.now() - hours * 3_600_000).toISOString()

  function deliveredOrder(overrides: Partial<OrderView> = {}): OrderView {
    return confirmedOrder({
      status: 'DELIVERED',
      cancellable: false,
      returnable: true,
      delivery: { partnerName: 'Ravi', assignedAt: hoursAgo(3), pickedUpAt: hoursAgo(2.5), deliveredAt: hoursAgo(2) },
      timeline: [
        { status: 'CREATED', at: '2026-11-11T06:00:00Z', reason: null },
        { status: 'CONFIRMED', at: '2026-11-11T06:01:00Z', reason: null },
        { status: 'ACCEPTED', at: '2026-11-11T06:02:00Z', reason: null },
        { status: 'OUT_FOR_DELIVERY', at: '2026-11-11T06:20:00Z', reason: null },
        { status: 'DELIVERED', at: '2026-11-12T06:30:00Z', reason: null },
      ],
      createdAt: '2026-11-11T06:00:00Z',
      ...overrides,
    })
  }

  it('says where a delivered order is once: one status line, the progress row with times, rate / return in the same card', async () => {
    const user = userEvent.setup()
    setup(deliveredOrder())
    renderApp('/orders/o1')

    const status = await screen.findByRole('status', { name: 'Delivered' })
    expect(status).toHaveTextContent(/Delivered at .* by Ravi\. Enjoy!/)
    expect(screen.getAllByRole('heading', { name: 'Delivered' })).toHaveLength(1)
    // No order-status badge next to the title: the payment is the only pill there.
    expect(screen.getByRole('group', { name: 'Total and payment' })).toHaveTextContent(/^Total ₹1252 itemsPaid$/)

    const progress = within(hero()).getByRole('list', { name: 'Order progress' })
    const steps = within(progress).getAllByRole('listitem')
    expect(steps.map((step) => step.textContent?.replace(/\s*\(.*\).*$/, ''))).toEqual(['Placed', 'Confirmed', 'Packing', 'On the way', 'Delivered'])
    expect(steps[0]?.querySelector('time')).toHaveAttribute('dateTime', '2026-11-11T06:00:00Z')

    // Rate and return: one strip inside the order's card, not a card of its own.
    const after = within(hero()).getByRole('region', { name: 'After delivery' })
    expect(within(after).getByRole('link', { name: 'Rate your order' })).toHaveAttribute('href', '/orders/o1/review')
    expect(within(after).getByRole('button', { name: 'Return items' })).toBeInTheDocument()

    // The full history: times, and the date for a change on a later day than the order.
    const history = await openUpdates(user)
    const entries = within(history).getAllByRole('listitem')
    expect(entries).toHaveLength(5)
    expect(entries[0]).not.toHaveTextContent('2026')
    expect(entries[4]).toHaveTextContent('Delivered')
    expect(entries[4]).toHaveTextContent('12 Nov 2026')
    await user.click(screen.getByRole('button', { name: /Hide updates/ }))
    expect(screen.queryByRole('list', { name: 'Status history' })).not.toBeInTheDocument()
  })

  it('puts the payment at the top of an unpaid order, before the progress, with "Cancel order" under it', async () => {
    setup(orderView())
    renderApp('/orders/o1')

    const payment = await screen.findByRole('region', { name: 'Pay ₹125 to confirm your order' })
    const progress = screen.getByRole('list', { name: 'Order progress' })
    expect(hero()).toContainElement(payment)
    expect(payment.compareDocumentPosition(progress) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // The payment panel is the status while the order waits for it: no second status line.
    expect(screen.queryByRole('heading', { name: 'Waiting for your payment' })).not.toBeInTheDocument()
    expect(within(progress).getAllByRole('listitem')[0]).toHaveAttribute('aria-current', 'step')
    const cancel = within(hero()).getByRole('button', { name: 'Cancel order' })
    expect(progress.compareDocumentPosition(cancel) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('explains a delivery that failed, with the reason in the history', async () => {
    const user = userEvent.setup()
    setup(
      deliveredOrder({
        status: 'DELIVERY_FAILED',
        returnable: false,
        timeline: [
          { status: 'CREATED', at: '2026-11-11T06:00:00Z', reason: null },
          { status: 'OUT_FOR_DELIVERY', at: '2026-11-11T06:20:00Z', reason: null },
          { status: 'DELIVERY_FAILED', at: '2026-11-11T06:40:00Z', reason: 'ADDRESS_NOT_FOUND' },
        ],
      }),
    )
    renderApp('/orders/o1')

    expect(await screen.findByRole('status', { name: 'We could not deliver your order' })).toHaveTextContent('Our support team will contact you about a refund.')
    const steps = within(screen.getByRole('list', { name: 'Order progress' })).getAllByRole('listitem')
    expect(steps.at(-1)).toHaveTextContent('Not delivered (the order ended here)')
    expect(screen.queryByRole('region', { name: 'After delivery' })).not.toBeInTheDocument()
    expect(await openUpdates(user)).toHaveTextContent('The address could not be found')
  })
})
