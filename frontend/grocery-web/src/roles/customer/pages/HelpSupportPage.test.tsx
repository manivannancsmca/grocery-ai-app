import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { ComplaintView, NewComplaint } from '../../../features/complaints/api'
import { cartView, SPICE_ROUTE } from '../../../test/cartFixtures'
import { complaintView } from '../../../test/complaintFixtures'
import { type MockHandler, type MockReply, mockApi, problem } from '../../../test/mockApi'
import { confirmedOrder, orderSummary, orderView, pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

/** The customer's recent orders for the picker: a grocery order (o1) and a restaurant order (o2). */
const RECENT_ORDERS = [
  orderSummary({ status: 'DELIVERED' }),
  orderSummary({ id: 'o2', orderNumber: 'GA-SPICE002', type: 'RESTAURANT', store: SPICE_ROUTE, status: 'DELIVERED', createdAt: '2026-11-15T13:00:00Z' }),
]

const ANSWERED = complaintView({
  id: 'c3',
  complaintNumber: 'CMP-ANSWER03',
  category: 'PAYMENT',
  status: 'RESOLVED',
  orderId: 'o2',
  orderNumber: 'GA-SPICE002',
  storeName: 'Spice Route',
  description: 'I was charged twice for this order.',
  resolution: 'Sorry about that — the second charge was refunded today.',
  resolvedAt: '2026-11-18T09:00:00Z',
})
const WORKING = complaintView({ id: 'c2', complaintNumber: 'CMP-WORKING2', category: 'APP', status: 'IN_PROGRESS', orderId: null, orderNumber: null, storeName: null, description: 'The app logs me out every time I open it.' })

/** order-service: the customer's complaints (newest first, filtered by `status`) and `POST /complaints`. */
function setup(initial: ComplaintView[], routes: Record<string, MockHandler> = {}) {
  signInAs(['CUSTOMER'])
  const server = { complaints: [...initial], next: 1 }
  const api = mockApi({
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /orders': pageOf(RECENT_ORDERS),
    'GET /complaints': (request) => {
      const status = request.params.status
      const content = server.complaints.filter((complaint) => !status || complaint.status === status)
      return pageOf(content, { size: 10 })
    },
    'POST /complaints': (request) => {
      const body = request.body as NewComplaint
      const order = RECENT_ORDERS.find((summary) => summary.id === body.orderId)
      const created = complaintView({
        id: `new${server.next}`,
        complaintNumber: `CMP-NEW0000${server.next++}`,
        category: body.category,
        orderId: order?.id ?? null,
        orderNumber: order?.orderNumber ?? null,
        storeName: order?.store.name ?? null,
        description: body.description,
        createdAt: '2026-11-19T11:00:00Z',
      })
      server.complaints = [created, ...server.complaints]
      return { status: 201, data: created }
    },
    ...routes,
  })
  return { api, server }
}

describe('Help & support (customer complaints)', { timeout: 20_000 }, () => {
  it('lists the complaints with their status, order and our answer, and filters by status', async () => {
    const user = userEvent.setup()
    const { api } = setup([WORKING, ANSWERED])
    const { router } = renderApp('/account/complaints')

    expect(await screen.findByRole('heading', { level: 1, name: 'Help & support' })).toBeInTheDocument()
    const answered = await screen.findByRole('article', { name: /CMP-ANSWER03/ })
    expect(api.callsTo('GET /complaints')[0]?.params).toEqual({ page: 0, size: 10 })
    expect(answered).toHaveTextContent('Payment')
    expect(answered).toHaveTextContent('Resolved')
    expect(answered).toHaveTextContent('I was charged twice for this order.')
    expect(within(answered).getByText('Our answer')).toBeInTheDocument()
    expect(answered).toHaveTextContent('Sorry about that — the second charge was refunded today.')
    expect(within(answered).getByRole('link', { name: /Order GA-SPICE002/ })).toHaveAttribute('href', '/orders/o2')
    expect(answered).toHaveTextContent('Spice Route')

    // Not about an order: no order link, and a line on where it stands instead of an answer.
    const working = screen.getByRole('article', { name: /CMP-WORKING2/ })
    expect(working).toHaveTextContent('In progress')
    expect(working).toHaveTextContent('Our support team is working on it.')
    expect(within(working).queryByRole('link')).not.toBeInTheDocument()
    expect(within(working).queryByText('Our answer')).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Resolved' }))
    await waitFor(() => expect(api.callsTo('GET /complaints').at(-1)?.params).toEqual({ status: 'RESOLVED', page: 0, size: 10 }))
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('RESOLVED')
    await waitFor(() => expect(screen.queryByRole('article', { name: /CMP-WORKING2/ })).not.toBeInTheDocument())
    expect(screen.getByRole('article', { name: /CMP-ANSWER03/ })).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Open' }))
    expect(await screen.findByText('No open complaints')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Show all complaints' }))
    await waitFor(() => expect(router.state.location.search).toBe(''))
  })

  it('shows an empty state before the first complaint', async () => {
    setup([])
    renderApp('/account/complaints')
    expect(await screen.findByText('No complaints yet')).toBeInTheDocument()
  })

  it('raises a complaint about an order: checks the form, sends it and shows it at the top', async () => {
    const user = userEvent.setup()
    const { api } = setup([ANSWERED])
    renderApp('/account/complaints')

    const form = await screen.findByRole('form', { name: 'Raise a complaint' })
    // The picker offers the recent orders (number, store and date).
    expect(await within(form).findByRole('option', { name: 'GA-SPICE002 · Spice Route · 15 Nov 2026' })).toBeInTheDocument()
    expect(api.callsTo('GET /orders')[0]?.params).toEqual({ size: 20 })

    // Nothing chosen yet.
    await user.click(within(form).getByRole('button', { name: 'Send complaint' }))
    expect(within(form).getByText('Choose what the complaint is about.')).toBeInTheDocument()
    expect(within(form).getByText('Describe the problem in at least 10 characters.')).toBeInTheDocument()
    expect(api.callsTo('POST /complaints')).toHaveLength(0)

    await user.click(within(form).getByRole('radio', { name: 'Delivery' }))
    expect(within(form).queryByText('Choose what the complaint is about.')).not.toBeInTheDocument()
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Which order? (optional)' }), 'o2')
    const description = within(form).getByRole('textbox', { name: /What happened/ })
    await user.type(description, '  The food came two hours late.  ')
    expect(within(form).getByText('33 / 1000')).toBeInTheDocument()
    await user.click(within(form).getByRole('button', { name: 'Send complaint' }))

    await waitFor(() => expect(api.callsTo('POST /complaints')).toHaveLength(1))
    expect(api.callsTo('POST /complaints')[0]?.body).toEqual({ category: 'DELIVERY', orderId: 'o2', description: 'The food came two hours late.' })

    expect(await screen.findByText('Complaint CMP-NEW00001 sent')).toBeInTheDocument()
    // The new complaint is first in the list, marked new; the form is empty again.
    const list = await screen.findByRole('list', { name: 'Your complaints' })
    await waitFor(() => expect(within(list).getAllByRole('article')[0]).toHaveAccessibleName(/CMP-NEW00001/))
    const created = within(list).getAllByRole('article')[0]!
    expect(created).toHaveTextContent('New')
    expect(created).toHaveTextContent('Open')
    expect(within(created).getByRole('link', { name: /Order GA-SPICE002/ })).toHaveAttribute('href', '/orders/o2')
    expect(within(form).getByRole('textbox', { name: /What happened/ })).toHaveValue('')
    expect(within(form).getByRole('radio', { name: 'Delivery' })).not.toBeChecked()
  })

  it('preselects the order from ?orderId=, and sends a complaint without an order', async () => {
    const user = userEvent.setup()
    const { api } = setup([])
    const { router } = renderApp('/account/complaints?orderId=o1')

    const form = await screen.findByRole('form', { name: 'Raise a complaint' })
    const picker = within(form).getByRole('combobox', { name: 'Which order? (optional)' })
    await waitFor(() => expect(picker).toHaveValue('o1'))
    expect(picker).toHaveAccessibleDescription(/Green Basket · Delivered/)

    // Items: a return request is the faster way to a refund, on the chosen order.
    await user.click(within(form).getByRole('radio', { name: 'Items' }))
    expect(within(form).getByRole('link', { name: 'Request a return on GA-7K3F9Q2M' })).toHaveAttribute('href', '/orders/o1')
    expect(screen.getByRole('link', { name: 'Open order GA-7K3F9Q2M' })).toHaveAttribute('href', '/orders/o1')

    // Not about an order after all: an app problem.
    await user.selectOptions(picker, '')
    expect(screen.getByRole('link', { name: 'Go to my orders' })).toHaveAttribute('href', '/orders')
    await user.click(within(form).getByRole('radio', { name: 'App or website' }))
    await user.type(within(form).getByRole('textbox', { name: /What happened/ }), 'The checkout button does nothing.')
    await user.click(within(form).getByRole('button', { name: 'Send complaint' }))

    await waitFor(() => expect(api.callsTo('POST /complaints')[0]?.body).toEqual({ category: 'APP', description: 'The checkout button does nothing.' }))
    expect(await screen.findByText('Complaint CMP-NEW00001 sent')).toBeInTheDocument()
    // The order link has done its job.
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('orderId')).toBeNull())
    expect(await screen.findByRole('article', { name: /CMP-NEW00001/ })).toHaveTextContent('App or website')
  })

  it('preselects an older order that is not among the recent ones', async () => {
    setup([], { 'GET /orders/:id': { status: 200, data: orderView({ id: 'o9', orderNumber: 'GA-OLDER009', createdAt: '2026-09-01T10:00:00Z' }) } })
    renderApp('/account/complaints?orderId=o9')

    const picker = await screen.findByRole('combobox', { name: 'Which order? (optional)' })
    await waitFor(() => expect(picker).toHaveValue('o9'))
    expect(within(picker).getByRole('option', { name: /^GA-OLDER009 · Green Basket · 1 Sep/ })).toBeInTheDocument()
  })

  it('shows the server’s refusals next to the form and keeps what was typed', async () => {
    const user = userEvent.setup()
    let refusal: MockReply = problem(409, 'COMPLAINT_ALREADY_OPEN', 'Order GA-7K3F9Q2M already has an open complaint: CMP-OPEN0001')
    const { api } = setup([], { 'POST /complaints': () => refusal })
    renderApp('/account/complaints?orderId=o1')

    const form = await screen.findByRole('form', { name: 'Raise a complaint' })
    await waitFor(() => expect(within(form).getByRole('combobox', { name: 'Which order? (optional)' })).toHaveValue('o1'))
    await user.click(within(form).getByRole('radio', { name: 'Delivery' }))
    await user.type(within(form).getByRole('textbox', { name: /What happened/ }), 'Still waiting for my order.')
    const send = within(form).getByRole('button', { name: 'Send complaint' })

    await user.click(send)
    expect(await within(form).findByRole('alert')).toHaveTextContent('You already have an open complaint about this order (CMP-OPEN0001).')

    refusal = problem(409, 'TOO_MANY_OPEN_COMPLAINTS')
    await user.click(send)
    await waitFor(() => expect(within(form).getByRole('alert')).toHaveTextContent('You have too many open complaints.'))

    refusal = problem(404, 'ORDER_NOT_FOUND')
    await user.click(send)
    await waitFor(() => expect(within(form).getByRole('alert')).toHaveTextContent('We could not find that order on your account.'))

    // A 400 puts the server's message on its field.
    refusal = problem(400, 'VALIDATION_FAILED', 'Validation failed', [{ field: 'description', message: 'Tell us a little more.' }])
    await user.click(send)
    await waitFor(() => expect(within(form).getByRole('textbox', { name: /What happened/ })).toHaveAccessibleDescription(/Tell us a little more\./))
    expect(within(form).queryByRole('alert')).not.toBeInTheDocument()

    expect(api.callsTo('POST /complaints')).toHaveLength(4)
    expect(within(form).getByRole('textbox', { name: /What happened/ })).toHaveValue('Still waiting for my order.')
  })

  it('links from an order to Help & support with that order chosen', async () => {
    const user = userEvent.setup()
    setup([], { 'GET /orders/:id': { status: 200, data: confirmedOrder() } })
    renderApp('/orders/o1')

    const help = await screen.findByRole('link', { name: 'Need help with this order?' })
    expect(help).toHaveAttribute('href', '/account/complaints?orderId=o1')
    await user.click(help)
    const picker = await screen.findByRole('combobox', { name: 'Which order? (optional)' })
    await waitFor(() => expect(picker).toHaveValue('o1'))
  })
})
