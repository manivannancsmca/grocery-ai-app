import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { adminComplaintView } from '../../../test/complaintFixtures'
import { mockApi, problem } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const OPEN = adminComplaintView({ id: 'c1', complaintNumber: 'CMP-OPEN0001' })
const WORKING = adminComplaintView({
  id: 'c2',
  complaintNumber: 'CMP-WORK0002',
  category: 'APP',
  status: 'IN_PROGRESS',
  orderId: null,
  orderNumber: null,
  storeName: null,
  description: 'The app logs me out every time.',
  handledBy: 'admin-9-long-id',
})
const RESOLVED = adminComplaintView({
  id: 'c3',
  complaintNumber: 'CMP-DONE0003',
  category: 'PAYMENT',
  status: 'RESOLVED',
  resolution: 'The double charge was refunded.',
  resolvedAt: '2026-11-19T12:00:00Z',
  handledBy: 'admin-1',
})

/** order-service: the admin queue (filtered by `status`) and the start / resolve actions, kept in memory. */
function setup(roles, initial, routes = {}) {
  signInAs(roles, { id: 'admin-1' })
  const server = { complaints: [...initial] }
  const change = (id, patch) => {
    server.complaints = server.complaints.map((complaint) =>
      complaint.id === id ? { ...complaint, ...patch } : complaint,
    )
    return { status: 200, data: server.complaints.find((complaint) => complaint.id === id) }
  }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /admin/complaints': (request) => {
      const { status, category } = request.params
      const content = server.complaints.filter(
        (complaint) => (!status || complaint.status === status) && (!category || complaint.category === category),
      )
      return pageOf(content, { size: 10 })
    },
    'POST /admin/complaints/:id/start': (request) =>
      change(request.pathParams.id ?? '', { status: 'IN_PROGRESS', handledBy: 'admin-1' }),
    'POST /admin/complaints/:id/resolve': (request) =>
      change(request.pathParams.id ?? '', {
        status: 'RESOLVED',
        resolution: request.body.resolution,
        resolvedAt: '2026-11-19T13:00:00Z',
        handledBy: 'admin-1',
      }),
    ...routes,
  })
  return { api, server }
}

describe('Admin complaints', { timeout: 20_000 }, () => {
  it('opens on the Open tab; support starts a complaint and resolves it with an answer', async () => {
    const user = userEvent.setup()
    const { api } = setup(['SUPPORT'], [OPEN, WORKING])
    const { router } = renderApp('/admin/complaints')

    const card = await screen.findByRole('region', { name: 'Complaint CMP-OPEN0001' })
    expect(api.callsTo('GET /admin/complaints')[0]?.params).toEqual({ status: 'OPEN', page: 0, size: 10 })
    expect(screen.getByRole('tab', { name: /^Open/ })).toHaveAttribute('aria-selected', 'true')
    expect(card).toHaveTextContent('Delivery')
    expect(card).toHaveTextContent('Open')
    expect(card).toHaveTextContent('Customer 0193c0de…')
    expect(card).toHaveTextContent('Green Basket')
    expect(card).toHaveTextContent('The order came 2 hours late and the milk was warm.')
    expect(within(card).getByRole('link', { name: 'Order GA-7K3F9Q2M' })).toHaveAttribute(
      'href',
      '/admin/orders?order=o1',
    )
    expect(screen.queryByRole('region', { name: 'Complaint CMP-WORK0002' })).not.toBeInTheDocument()

    // Start: OPEN → IN_PROGRESS, so it leaves the Open tab.
    await user.click(within(card).getByRole('button', { name: 'Start' }))
    await waitFor(() => expect(api.callsTo('POST /admin/complaints/:id/start')[0]?.pathParams).toEqual({ id: 'c1' }))
    expect(await screen.findByText('CMP-OPEN0001 started')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: 'Complaint CMP-OPEN0001' })).not.toBeInTheDocument(),
    )
    expect(await screen.findByText('No open complaints')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'In progress' }))
    await waitFor(() =>
      expect(api.callsTo('GET /admin/complaints').at(-1)?.params).toEqual({ status: 'IN_PROGRESS', page: 0, size: 10 }),
    )
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('IN_PROGRESS')
    const started = await screen.findByRole('region', { name: 'Complaint CMP-OPEN0001' })
    // In progress: no Start any more, only Resolve.
    expect(within(started).queryByRole('button', { name: 'Start' })).not.toBeInTheDocument()
    // Not about an order: said so instead of an order link.
    expect(screen.getByRole('region', { name: 'Complaint CMP-WORK0002' })).toHaveTextContent('Not about an order')

    await user.click(within(started).getByRole('button', { name: 'Resolve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Resolve CMP-OPEN0001?' })
    expect(dialog).toHaveTextContent('The order came 2 hours late and the milk was warm.')
    const answer = within(dialog).getByRole('textbox', { name: /Answer for the customer/ })
    await user.type(answer, 'Ok')
    await user.click(within(dialog).getByRole('button', { name: 'Resolve complaint' }))
    expect(answer).toHaveAccessibleDescription(/Write an answer of at least 5 characters\./)
    expect(api.callsTo('POST /admin/complaints/:id/resolve')).toHaveLength(0)

    await user.clear(answer)
    await user.type(answer, '  We refunded the delivery fee.  ')
    await user.click(within(dialog).getByRole('button', { name: 'Resolve complaint' }))
    await waitFor(() =>
      expect(api.callsTo('POST /admin/complaints/:id/resolve')[0]).toMatchObject({
        pathParams: { id: 'c1' },
        body: { resolution: 'We refunded the delivery fee.' },
      }),
    )
    expect(await screen.findByText('CMP-OPEN0001 resolved')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    // All: no status sent; the resolved complaint shows the answer.
    await user.click(screen.getByRole('tab', { name: 'All' }))
    await waitFor(() => expect(api.callsTo('GET /admin/complaints').at(-1)?.params).toEqual({ page: 0, size: 10 }))
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('ALL')
    const resolved = await screen.findByRole('region', { name: 'Complaint CMP-OPEN0001' })
    await waitFor(() => expect(resolved).toHaveTextContent('Answer sent to the customer'))
    expect(resolved).toHaveTextContent('We refunded the delivery fee.')
    expect(within(resolved).queryByRole('button')).not.toBeInTheDocument()
  })

  it('sends the category filter and the search to the server', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'], [OPEN])
    const { router } = renderApp('/admin/complaints')
    await screen.findByRole('region', { name: 'Complaint CMP-OPEN0001' })

    await user.selectOptions(screen.getByRole('combobox', { name: 'Category' }), 'Payment')
    await waitFor(() =>
      expect(api.callsTo('GET /admin/complaints').at(-1)?.params).toEqual({
        status: 'OPEN',
        category: 'PAYMENT',
        page: 0,
        size: 10,
      }),
    )
    expect(await screen.findByText('No complaints match')).toBeInTheDocument()

    await user.type(screen.getByRole('searchbox', { name: 'Search' }), ' GA-7K ')
    await waitFor(() =>
      expect(api.callsTo('GET /admin/complaints').at(-1)?.params).toEqual({
        status: 'OPEN',
        category: 'PAYMENT',
        q: 'GA-7K',
        page: 0,
        size: 10,
      }),
    )

    await user.click(screen.getByRole('button', { name: 'Clear all' }))
    await waitFor(() => expect(router.state.location.search).toBe(''))
    await waitFor(() =>
      expect(api.callsTo('GET /admin/complaints').at(-1)?.params).toEqual({ status: 'OPEN', page: 0, size: 10 }),
    )
  })

  it('explains a conflict (someone else was quicker) and refreshes the list', async () => {
    const user = userEvent.setup()
    const { api } = setup(['SUPER_ADMIN'], [OPEN, RESOLVED], {
      'POST /admin/complaints/:id/start': problem(409, 'INVALID_COMPLAINT_STATUS'),
      'POST /admin/complaints/:id/resolve': problem(409, 'CONCURRENT_MODIFICATION'),
    })
    renderApp('/admin/complaints?status=ALL')

    const card = await screen.findByRole('region', { name: 'Complaint CMP-OPEN0001' })
    const reads = api.callsTo('GET /admin/complaints').length
    await user.click(within(card).getByRole('button', { name: 'Start' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'CMP-OPEN0001 was already started or resolved by someone else. The list has been refreshed',
    )
    await waitFor(() => expect(api.callsTo('GET /admin/complaints').length).toBeGreaterThan(reads))

    // The resolve dialog closes on a conflict and the page explains.
    await user.click(within(card).getByRole('button', { name: 'Resolve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Resolve CMP-OPEN0001?' })
    await user.type(within(dialog).getByRole('textbox', { name: /Answer for the customer/ }), 'Refunded the fee.')
    await user.click(within(dialog).getByRole('button', { name: 'Resolve complaint' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText(/Someone else changed CMP-OPEN0001 at the same time/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByText(/Someone else changed CMP-OPEN0001/)).not.toBeInTheDocument()
  })

  it('keeps other errors in the resolve dialog', async () => {
    const user = userEvent.setup()
    setup(['OPS_ADMIN'], [OPEN], {
      'POST /admin/complaints/:id/resolve': problem(503, 'SERVICE_UNAVAILABLE', 'Order service is busy, try again.'),
    })
    renderApp('/admin/complaints')

    const card = await screen.findByRole('region', { name: 'Complaint CMP-OPEN0001' })
    await user.click(within(card).getByRole('button', { name: 'Resolve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Resolve CMP-OPEN0001?' })
    await user.type(within(dialog).getByRole('textbox', { name: /Answer for the customer/ }), 'Refunded the fee.')
    await user.click(within(dialog).getByRole('button', { name: 'Resolve complaint' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Order service is busy, try again.')
  })

  it('a finance admin sees complaints read-only', async () => {
    setup(['FINANCE_ADMIN'], [OPEN, WORKING])
    renderApp('/admin/complaints?status=ALL')
    const card = await screen.findByRole('region', { name: 'Complaint CMP-OPEN0001' })
    expect(within(card).queryByRole('button')).not.toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: 'Complaint CMP-WORK0002' })).queryByRole('button'),
    ).not.toBeInTheDocument()
    expect(screen.getByText(/Your role can view complaints/)).toBeInTheDocument()
  })
})
