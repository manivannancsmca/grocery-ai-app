import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ORDER_FALLBACK_POLL_MS } from '../../../features/orders/hooks'
import { formatTime } from '../../../shared/utils/format'
import { cartView, SPICE_ROUTE } from '../../../test/cartFixtures'
import { mockApi, problem } from '../../../test/mockApi'
import { mockEventStream } from '../../../test/mockEventStream'
import { orderSummary, pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

// 14:30 in India on Wed 18 Nov 2026: "today" and "yesterday" below are India days.
const NOW = new Date('2026-11-18T09:00:00Z')
const minutesAgo = (minutes) => new Date(NOW.getTime() - minutes * 60_000).toISOString()

const awaiting = orderSummary({ createdAt: minutesAgo(3.4) })
const onTheWay = orderSummary({
  id: 'o2',
  orderNumber: 'GA-RSTR0002',
  type: 'RESTAURANT',
  status: 'OUT_FOR_DELIVERY',
  store: SPICE_ROUTE,
  itemCount: 1,
  total: 1249.5,
  firstItemName: 'Chicken Biryani',
  payment: { status: 'PAID' },
  createdAt: minutesAgo(30),
})
const confirmed = orderSummary({
  id: 'o3',
  orderNumber: 'GA-CONF0003',
  status: 'CONFIRMED',
  payment: { status: 'PAID' },
  createdAt: minutesAgo(1),
})
const deliveredToday = orderSummary({
  id: 'o4',
  orderNumber: 'GA-DLVR0004',
  type: 'RESTAURANT',
  status: 'DELIVERED',
  store: SPICE_ROUTE,
  itemCount: 4,
  total: 310,
  firstItemName: 'Ghee Roast Dosa',
  payment: { status: 'PAID' },
  createdAt: minutesAgo(90),
})
const cancelledYesterday = orderSummary({
  id: 'o5',
  orderNumber: 'GA-CNCL0005',
  status: 'CANCELLED',
  itemCount: 1,
  total: 239,
  firstItemName: 'Groundnut Oil',
  payment: { status: 'REFUNDED' },
  createdAt: '2026-11-17T10:00:00Z',
})
const deliveredEarlier = orderSummary({
  id: 'o6',
  orderNumber: 'GA-OLDD0006',
  status: 'DELIVERED',
  total: 566,
  payment: { status: 'PARTIALLY_REFUNDED' },
  createdAt: '2026-11-14T06:00:00Z',
})

function setup(
  orders = (request) => {
    if (request.params.status === 'OPEN')
      return pageOf([awaiting], { page: Number(request.params.page ?? 0), totalElements: 21, totalPages: 2 })
    if (request.params.status === 'REJECTED') return pageOf([])
    return pageOf([deliveredToday, cancelledYesterday, deliveredEarlier])
  },
) {
  signInAs(['CUSTOMER'])
  return mockApi({ 'GET /cart': { status: 200, data: cartView([]) }, 'GET /orders': orders })
}

describe('My orders', () => {
  beforeEach(() => {
    // Only the clock: timers stay real, so the queries and user events run as usual.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
  })
  afterEach(() => vi.useRealTimers())

  it('lists finished orders by day as rows that each link to the order', async () => {
    const api = setup()
    renderApp('/orders')

    const past = await screen.findByRole('list', { name: 'Past orders' })
    expect(screen.queryByRole('list', { name: 'Orders in progress' })).not.toBeInTheDocument()
    expect(
      within(past)
        .getAllByRole('heading', { level: 3 })
        .map((heading) => heading.textContent),
    ).toEqual([
      expect.stringMatching(/^Today.*18 Nov/),
      expect.stringMatching(/^Yesterday.*17 Nov/),
      expect.stringMatching(/14 Nov/),
    ])

    const today = within(screen.getByRole('list', { name: /^Today/ })).getAllByRole('link')
    expect(today).toHaveLength(1)
    expect(today[0]).toHaveAccessibleName('Order GA-DLVR0004 from Spice Route')
    expect(today[0]).toHaveAccessibleDescription(/^₹310, Delivered, Paid, Ghee Roast Dosa · 4 items, placed /)
    expect(today[0]).toHaveAttribute('href', '/orders/o4')
    expect(today[0]).toHaveTextContent('Ghee Roast Dosa · 4 items')
    expect(within(today[0]).getByText('Delivered')).toBeInTheDocument()
    // "Paid" is what every delivered order is: only said to screen readers, not as a second chip.
    expect(within(today[0]).queryByText('Paid')).not.toBeInTheDocument()

    const [yesterday] = within(screen.getByRole('list', { name: /^Yesterday/ })).getAllByRole('link')
    expect(yesterday).toHaveAccessibleName('Order GA-CNCL0005 from Green Basket')
    expect(within(yesterday).getByText('Cancelled')).toBeInTheDocument()
    expect(within(yesterday).getByText('Refunded')).toBeInTheDocument()
    expect(yesterday).toHaveTextContent('₹239')
    const [earlier] = within(screen.getByRole('list', { name: /14 Nov/ })).getAllByRole('link')
    expect(within(earlier).getByText('Partly refunded')).toBeInTheDocument()

    // No "View details" buttons: the whole row is the link.
    expect(screen.queryByText('View details')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'My orders' })).toBeInTheDocument()
    expect(screen.getByText('3 orders · newest first')).toBeInTheDocument()
    expect(api.callsTo('GET /orders')[0]?.params).toEqual({ page: 0, size: 20 })
    expect(screen.getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'All' })).toHaveTextContent('3')
    expect(screen.getByRole('navigation', { name: 'Pagination' })).toHaveTextContent('Showing 1–3 of 3')
  })

  it('puts the orders under way first, payment due leading, each with where it is and what to do next', async () => {
    setup(pageOf([confirmed, awaiting, onTheWay, deliveredToday]))
    renderApp('/orders')

    const active = await screen.findByRole('list', { name: 'Orders in progress' })
    expect(screen.getByRole('heading', { level: 2, name: /In progress/ })).toBeInTheDocument()
    // The strip follows the live stream (open in tests unless one says otherwise).
    expect(await screen.findByText('Live')).toHaveAttribute('title', 'Status changes show up here as they happen')
    const cards = within(active).getAllByRole('link')
    expect(cards.map((card) => card.getAttribute('href'))).toEqual(['/orders/o1', '/orders/o3', '/orders/o2'])

    expect(cards[0]).toHaveAccessibleName('Order GA-7K3F9Q2M from Green Basket')
    expect(cards[0]).toHaveAccessibleDescription(
      /^Pay within 12 min, ₹125, Awaiting payment, Payment pending, Basmati Rice · 2 items, placed /,
    )
    expect(cards[0]).toHaveTextContent('Pay within 12 min')
    expect(cards[0]).toHaveTextContent('Pay now')
    expect(cards[0]).toHaveTextContent('Placed 3 min ago')
    expect(within(cards[0]).getByText('Payment pending')).toBeInTheDocument()

    expect(cards[1]).toHaveTextContent('Order confirmed')
    expect(cards[1]).toHaveTextContent('Waiting for Green Basket to accept it.')
    expect(cards[1]).toHaveTextContent('View order')

    expect(cards[2]).toHaveAccessibleName('Order GA-RSTR0002 from Spice Route')
    expect(cards[2]).toHaveTextContent('Arriving soon')
    expect(cards[2]).toHaveTextContent('Track order')
    expect(cards[2]).toHaveTextContent('₹1,249.50')
    expect(within(cards[2]).getByText('Paid')).toBeInTheDocument()

    // The finished one follows under its own title and day.
    expect(screen.getByRole('heading', { level: 2, name: 'Past orders' })).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: /^Today/ })).getByRole('link')).toHaveAttribute('href', '/orders/o4')
  })

  it('counts down to the payment due time the server sends, every second, and says when the time is up', async () => {
    const dueAt = new Date(NOW.getTime() + 7.5 * 60_000).toISOString()
    // Placed 2 minutes ago: the old 15-minute estimate would say 13 minutes; the server's due time is what counts.
    const dueSoon = orderSummary({ createdAt: minutesAgo(2), payment: { status: 'PENDING', dueAt } })
    const timeUp = orderSummary({
      id: 'o7',
      orderNumber: 'GA-LATE0007',
      createdAt: minutesAgo(16),
      payment: { status: 'PENDING', dueAt: minutesAgo(0.5) },
    })
    setup(pageOf([dueSoon, timeUp]))
    renderApp('/orders')

    const cards = within(await screen.findByRole('list', { name: 'Orders in progress' })).getAllByRole('link')
    expect(cards[0]).toHaveTextContent('Pay within 8 min')
    expect(cards[0]).toHaveAccessibleDescription(/^Pay within 8 min, ₹125, Awaiting payment, Payment pending, /)
    expect(cards[0]).toHaveTextContent(`Pay by ${formatTime(dueAt)}, or the order is cancelled.`)
    expect(cards[0]).toHaveTextContent('Pay now')
    const timer = within(cards[0]).getByRole('timer', { name: 'Time left to pay' })
    expect(timer).toHaveTextContent('7:30')

    // The card's own clock moves the countdown every second (the page's moves every 30 s).
    vi.setSystemTime(NOW.getTime() + 10_000)
    await waitFor(() => expect(timer).toHaveTextContent('7:20'), { timeout: 3_000 })

    // Past the server's due time: nothing left to pay, the order is being cancelled.
    expect(cards[1]).toHaveTextContent('Payment time is up')
    expect(cards[1]).toHaveTextContent('This order is being cancelled.')
    expect(cards[1]).toHaveTextContent('View order')
    expect(cards[1]).not.toHaveTextContent('Pay now')
    expect(within(cards[1]).queryByRole('timer')).not.toBeInTheDocument()
  })

  it('puts the filter and the page in the URL and the request (Open = CREATED + AWAITING_PAYMENT)', async () => {
    const user = userEvent.setup()
    const api = setup()
    const { router } = renderApp('/orders')

    await screen.findByRole('list', { name: 'Past orders' })
    await user.click(screen.getByRole('tab', { name: 'Open' }))

    expect(router.state.location.search).toBe('?status=OPEN')
    await waitFor(() =>
      expect(api.callsTo('GET /orders').at(-1)?.params).toEqual({ status: 'OPEN', page: 0, size: 20 }),
    )
    expect(await screen.findByRole('list', { name: 'Orders in progress' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Open' })).toHaveTextContent('21'))
    expect(screen.getByText('21 open orders · newest first')).toBeInTheDocument()

    await user.click(await screen.findByRole('button', { name: 'Next' }))
    expect(router.state.location.search).toBe('?status=OPEN&page=2')
    await waitFor(() =>
      expect(api.callsTo('GET /orders').at(-1)?.params).toEqual({ status: 'OPEN', page: 1, size: 20 }),
    )

    await user.click(screen.getByRole('tab', { name: 'Rejected' }))
    expect(router.state.location.search).toBe('?status=REJECTED')
    expect(await screen.findByText('No rejected orders')).toBeInTheDocument()

    // The empty view leads back to every order.
    await user.click(screen.getByRole('button', { name: 'Show all orders' }))
    expect(router.state.location.search).toBe('')
    expect(screen.getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'true')
    expect(await screen.findByRole('list', { name: 'Past orders' })).toBeInTheDocument()
  })

  it('moves between the tabs with the arrow keys', async () => {
    const user = userEvent.setup()
    setup()
    const { router } = renderApp('/orders')

    await screen.findByRole('list', { name: 'Past orders' })
    screen.getByRole('tab', { name: 'All' }).focus()
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Open' })).toHaveFocus()
    expect(router.state.location.search).toBe('?status=OPEN')
    await user.keyboard('{End}')
    expect(screen.getByRole('tab', { name: 'Rejected' })).toHaveFocus()
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('Rejected')
  })

  it('reads the filter from the URL and is linked from the navigation and the account menu', async () => {
    const user = userEvent.setup()
    const api = setup()
    renderApp('/orders?status=CONFIRMED')

    expect(await screen.findByRole('tab', { name: 'Confirmed' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() =>
      expect(api.callsTo('GET /orders')[0]?.params).toEqual({ status: 'CONFIRMED', page: 0, size: 20 }),
    )

    const nav = screen.getByRole('navigation', { name: 'Main navigation' })
    expect(within(nav).getByRole('link', { name: 'My orders' })).toHaveAttribute('href', '/orders')
    await user.click(screen.getByRole('button', { name: /Account menu/ }))
    const links = screen.getAllByRole('link', { name: 'My orders' })
    expect(links.length).toBeGreaterThan(1)
    links.forEach((link) => expect(link).toHaveAttribute('href', '/orders'))
  })

  it('invites a first order when there are none', async () => {
    setup(pageOf([]))
    renderApp('/orders')

    expect(await screen.findByText('You have not placed any orders yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Start shopping' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: 'Order food' })).toHaveAttribute('href', '/restaurants')
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).not.toBeInTheDocument()
  })

  it('shows a skeleton while loading, then an error with a retry', async () => {
    const user = userEvent.setup()
    let fail = true
    const api = setup(() =>
      fail
        ? { ...problem(503, 'SERVICE_UNAVAILABLE', 'Orders are taking a break.'), delayMs: 400 }
        : pageOf([deliveredToday]),
    )
    renderApp('/orders')

    expect(await screen.findByRole('status', { name: 'Loading your orders' })).toBeInTheDocument()
    expect(await screen.findByText('Could not load your orders')).toBeInTheDocument()
    expect(screen.getByText('Orders are taking a break.')).toBeInTheDocument()

    fail = false
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('list', { name: 'Past orders' })).toBeInTheDocument()
    expect(api.callsTo('GET /orders').length).toBeGreaterThanOrEqual(2)
  })

  it('sends a page past the end back to the first page', async () => {
    const user = userEvent.setup()
    setup((request) =>
      Number(request.params.page) > 0
        ? pageOf([], { page: 3, totalElements: 3, totalPages: 1 })
        : pageOf([deliveredToday, cancelledYesterday, deliveredEarlier]),
    )
    const { router } = renderApp('/orders?page=4')

    expect(await screen.findByText('Nothing on this page')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'First page' }))
    expect(router.state.location.search).toBe('')
    expect(await screen.findByRole('list', { name: 'Past orders' })).toBeInTheDocument()
  })
})

describe('My orders without the live stream', () => {
  afterEach(() => vi.useRealTimers())

  it('re-reads the list slowly while an order is under way, and not once they are all finished', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    let orders = [onTheWay]
    const api = setup(() => pageOf(orders))
    mockEventStream(() => ({ kind: 'status', status: 503 }))
    renderApp('/orders')

    expect(await screen.findByRole('list', { name: 'Orders in progress' })).toBeInTheDocument()
    expect(screen.queryByText('Live')).not.toBeInTheDocument()
    expect(api.callsTo('GET /orders')).toHaveLength(1)

    orders = [{ ...onTheWay, status: 'DELIVERED' }]
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ORDER_FALLBACK_POLL_MS)
    })
    await waitFor(() => expect(api.callsTo('GET /orders')).toHaveLength(2))
    expect(await screen.findByRole('list', { name: 'Past orders' })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Orders in progress' })).not.toBeInTheDocument()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(ORDER_FALLBACK_POLL_MS * 2)
    })
    expect(api.callsTo('GET /orders')).toHaveLength(2)
  })
})
