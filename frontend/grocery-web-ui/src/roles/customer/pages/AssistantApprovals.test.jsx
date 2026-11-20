import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { aiStatus, completedOrder, placeOrderApproval, SHOPPING_AGENT } from '../../../test/aiFixtures'
import { cartView } from '../../../test/cartFixtures'
import { mockApi } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const pending = placeOrderApproval()
const done = completedOrder(
  placeOrderApproval({
    id: 'a0',
    summary: { ...placeOrderApproval().summary, title: 'Place order: 1 item from Green Basket' },
  }),
)
const cancelOrder = placeOrderApproval({
  id: 'a5',
  action: 'CANCEL_ORDER',
  summary: {
    title: 'Cancel order GA-7K3F9Q2M',
    lines: [{ label: 'Reason', value: 'Requested through the assistant' }],
    amount: null,
    currency: null,
  },
})

function setup(approvals) {
  signInAs(['CUSTOMER'])
  const server = { approvals }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /ai/agents': { status: 200, data: [SHOPPING_AGENT] },
    'GET /ai/status': { status: 200, data: aiStatus({ provider: 'scripted', model: 'scripted' }) },
    'GET /ai/conversations': pageOf([]),
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /orders': pageOf([]),
    'GET /approvals': (request) =>
      pageOf(
        server.approvals.filter((approval) => !request.params.status || approval.status === request.params.status),
      ),
    'GET /approvals/:id': (request) => ({
      status: 200,
      data: server.approvals.find((approval) => approval.id === request.pathParams.id),
    }),
    'POST /approvals/:id/approve': (request) => {
      const approved = completedOrder(server.approvals.find((approval) => approval.id === request.pathParams.id))
      server.approvals = server.approvals.map((approval) => (approval.id === approved.id ? approved : approval))
      return { status: 200, data: approved }
    },
  })
  return { api, server }
}

describe('Assistant actions (customer)', () => {
  it('lists what waits for the customer and the history; "Confirm order" runs it', async () => {
    const user = userEvent.setup()
    const { api } = setup([pending, cancelOrder, done])
    renderApp('/assistant/approvals')

    expect(await screen.findByRole('heading', { level: 1, name: 'Assistant actions' })).toBeInTheDocument()
    const waiting = screen.getByRole('region', { name: 'Waiting for you' })
    const card = await within(waiting).findByRole('region', { name: 'Place order: 2 items from Fresh Mart' })
    const cancelCard = within(waiting).getByRole('region', { name: 'Cancel order GA-7K3F9Q2M' })
    expect(within(cancelCard).getByRole('button', { name: 'Confirm cancellation' })).toBeInTheDocument()
    expect(within(cancelCard).getByRole('button', { name: 'Not now' })).toBeInTheDocument()

    const history = screen.getByRole('table', { name: 'Assistant action history' })
    await waitFor(() => expect(within(history).getAllByRole('row')).toHaveLength(4))
    expect(history).toHaveTextContent('Place order: 1 item from Green Basket')
    expect(history).toHaveTextContent('Completed')

    expect(api.callsTo('GET /approvals').map((call) => call.params)).toEqual(
      expect.arrayContaining([
        { scope: 'mine', status: 'PENDING_APPROVAL', page: 0, size: 20 },
        { scope: 'mine', page: 0, size: 10 },
      ]),
    )

    const reads = api.callsTo('GET /approvals').length
    await user.click(within(card).getByRole('button', { name: 'Confirm order' }))
    // The lists are re-read; the decided card stays (with its outcome) until the page is left.
    await waitFor(() => expect(api.callsTo('GET /approvals').length).toBeGreaterThanOrEqual(reads + 2))
    expect(await within(card).findByText('Order GA-AI000001 placed')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Open order GA-AI000001' })).toHaveAttribute('href', '/orders/o9')
    await waitFor(() => expect(within(history).getAllByRole('row')).toHaveLength(4))
    expect(within(history).getAllByText('Completed')).toHaveLength(2)
  })

  it('?id= opens one request; the history filter is in the URL', async () => {
    const user = userEvent.setup()
    const { api } = setup([pending, done])
    const { router } = renderApp('/assistant/approvals?id=a0')

    const drawer = await screen.findByRole('dialog', { name: 'Place order: 1 item from Green Basket' })
    expect(within(drawer).getByRole('link', { name: 'Open order GA-AI000001' })).toHaveAttribute('href', '/orders/o9')
    // Customers do not see the admins' bookkeeping.
    expect(drawer).not.toHaveTextContent('Requested by')
    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('id')).toBeNull())

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'COMPLETED')
    await waitFor(() =>
      expect(api.callsTo('GET /approvals').at(-1)?.params).toEqual({
        scope: 'mine',
        status: 'COMPLETED',
        page: 0,
        size: 10,
      }),
    )
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('COMPLETED')
  })

  it('nothing pending: offers to open the assistant', async () => {
    const user = userEvent.setup()
    setup([])
    renderApp('/assistant/approvals')

    expect(await screen.findByText('Nothing to confirm')).toBeInTheDocument()
    expect(await screen.findByText('No assistant actions yet')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Ask the assistant' }))
    expect(await screen.findByRole('dialog', { name: 'Shopping assistant' })).toBeInTheDocument()
  })
})
