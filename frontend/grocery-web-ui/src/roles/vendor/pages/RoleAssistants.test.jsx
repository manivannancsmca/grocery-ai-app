import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ADMIN_BI_AGENT, aiStatus, placeOrderApproval } from '../../../test/aiFixtures'
import { mockApi } from '../../../test/mockApi'
import { mockChatStream } from '../../../test/mockChatStream'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { adjustStockApproval, DELIVERY_AGENT, RESTAURANT_AGENT, VENDOR_AGENT } from '../../../test/storeAdminFixtures'

function setup(roles, user, agents, approvals = [], routes = {}) {
  signInAs(roles, { id: 'user-1', ...user })
  const server = { approvals }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /ai/agents': { status: 200, data: agents },
    'GET /ai/status': { status: 200, data: aiStatus({ provider: 'scripted', model: 'scripted' }) },
    'GET /ai/conversations': pageOf([]),
    'GET /approvals': (request) =>
      pageOf(
        server.approvals.filter((approval) => !request.params.status || approval.status === request.params.status),
      ),
    'GET /approvals/:id': (request) => ({
      status: 200,
      data: server.approvals.find((approval) => approval.id === request.pathParams.id),
    }),
    'POST /approvals/:id/approve': (request) => {
      const current = server.approvals.find((approval) => approval.id === request.pathParams.id)
      const done = {
        ...current,
        status: 'COMPLETED',
        canDecide: false,
        decidedBy: 'user-1',
        decidedAt: '2026-11-13T10:05:00Z',
        executedAt: '2026-11-13T10:05:01Z',
        result: { productId: 'p1', onHand: 40 },
      }
      server.approvals = server.approvals.map((approval) => (approval.id === done.id ? done : approval))
      return { status: 200, data: done }
    },
    'POST /approvals/:id/reject': (request) => {
      const current = server.approvals.find((approval) => approval.id === request.pathParams.id)
      const rejected = { ...current, status: 'REJECTED', canDecide: false, comment: request.body.comment }
      server.approvals = server.approvals.map((approval) => (approval.id === rejected.id ? rejected : approval))
      return { status: 200, data: rejected }
    },
    ...routes,
  })
  return { api, server }
}

async function openPanel(user, name) {
  await user.click(await screen.findByRole('button', { name: 'Open the assistant' }))
  return screen.findByRole('dialog', { name })
}

describe('Phase 12 assistants — launcher per role', { timeout: 30_000 }, () => {
  it('a vendor gets the vendor assistant: its prompts, its agent in the chat, and /vendor/assistant for its actions', async () => {
    const user = userEvent.setup()
    setup(['VENDOR'], { vendorId: 'v1' }, [VENDOR_AGENT])
    const chat = mockChatStream()
    renderApp('/vendor/assistant')

    // The page names what can be asked.
    const intro = await screen.findByRole('list', { name: 'You can ask' })
    expect(intro).toHaveTextContent('low stock')

    const panel = await openPanel(user, 'Vendor assistant')
    expect(within(panel).getByRole('link', { name: 'Assistant actions →' })).toHaveAttribute(
      'href',
      '/vendor/assistant',
    )
    const prompts = within(panel).getByRole('list', { name: 'Example prompts' })
    expect(
      within(prompts)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(VENDOR_AGENT.examplePrompts)
    await user.click(within(prompts).getByRole('button', { name: 'low stock' }))
    const stream = await chat.connection()
    expect(stream.request.agent).toBe('vendor')
    expect(stream.request.body).toEqual({ message: 'low stock' })
  })

  it('a restaurant owner gets the restaurant assistant', async () => {
    const user = userEvent.setup()
    setup(['RESTAURANT_OWNER'], { restaurantId: 'r1' }, [RESTAURANT_AGENT])
    const chat = mockChatStream()
    renderApp('/restaurant/assistant')

    const panel = await openPanel(user, 'Restaurant assistant')
    expect(within(panel).getByRole('link', { name: 'Assistant actions →' })).toHaveAttribute(
      'href',
      '/restaurant/assistant',
    )
    await user.click(
      within(within(panel).getByRole('list', { name: 'Example prompts' })).getByRole('button', { name: 'orders' }),
    )
    expect((await chat.connection()).request.agent).toBe('restaurant')
  })

  it('a delivery partner gets the delivery assistant', async () => {
    const user = userEvent.setup()
    setup(['DELIVERY_PARTNER'], {}, [DELIVERY_AGENT])
    mockChatStream()
    renderApp('/delivery/assistant')

    expect(await screen.findByRole('heading', { level: 1, name: 'Assistant actions' })).toBeInTheDocument()
    const panel = await openPanel(user, 'Delivery assistant')
    expect(within(panel).getByRole('link', { name: 'Assistant actions →' })).toHaveAttribute(
      'href',
      '/delivery/assistant',
    )
  })

  it('a delivery manager starts with the delivery assistant, may switch to the business one, and decides reassignments', async () => {
    const user = userEvent.setup()
    const reassign = placeOrderApproval({
      id: 'd1',
      action: 'REASSIGN_DELIVERY',
      agent: 'delivery',
      onBehalfOf: null,
      approverUserId: null,
      approverRoles: ['DELIVERY_MANAGER', 'OPS_ADMIN', 'SUPER_ADMIN'],
      summary: {
        title: 'Reassign delivery of GA-7K3F9Q2M',
        lines: [{ label: 'Reason', value: 'Partner unreachable' }],
        amount: null,
        currency: null,
      },
    })
    const { api } = setup(['DELIVERY_MANAGER'], {}, [ADMIN_BI_AGENT, DELIVERY_AGENT], [reassign])
    mockChatStream()
    renderApp('/delivery/assistant')

    const card = await screen.findByRole('region', { name: 'Reassign delivery of GA-7K3F9Q2M' })
    expect(within(card).getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Reject' })).toBeInTheDocument()

    let panel = await openPanel(user, 'Delivery assistant')
    await user.click(within(panel).getByRole('button', { name: 'Switch to Business assistant' }))
    panel = await screen.findByRole('dialog', { name: 'Business assistant' })
    expect(within(panel).getByRole('link', { name: 'AI approvals →' })).toHaveAttribute('href', '/admin/ai/approvals')
    expect(within(panel).getByRole('button', { name: 'Switch to Delivery assistant' })).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Close' }))

    await user.click(within(card).getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve this request?' })
    await user.click(within(dialog).getByRole('button', { name: 'Approve and run' }))
    await waitFor(() => expect(api.callsTo('POST /approvals/:id/approve')).toHaveLength(1))
    expect(await within(card).findByRole('link', { name: 'Open deliveries' })).toHaveAttribute(
      'href',
      '/admin/deliveries',
    )
  })
})

describe('Phase 12 assistants — approvals pages', { timeout: 30_000 }, () => {
  it('the vendor confirms its own stock change ("Confirm stock change" / "Not now", no cancel); the outcome links to inventory', async () => {
    const user = userEvent.setup()
    const price = adjustStockApproval({
      id: 's2',
      action: 'CHANGE_PRICE',
      summary: { title: 'Change price of Onion 1 kg to ₹45', lines: [], amount: 45, currency: 'INR' },
    })
    const { api } = setup(['VENDOR'], { vendorId: 'v1' }, [VENDOR_AGENT], [adjustStockApproval(), price])
    renderApp('/vendor/assistant')

    const waiting = await screen.findByRole('region', { name: 'Waiting for you' })
    const card = await within(waiting).findByRole('region', { name: 'Set stock of Tomato 500 g to 40' })
    expect(card).toHaveTextContent('Adjust stock')
    expect(within(card).queryByRole('button', { name: 'Cancel request' })).not.toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    await user.click(within(card).getByRole('button', { name: 'Confirm stock change' }))
    await waitFor(() => expect(api.callsTo('POST /approvals/:id/approve')[0]?.pathParams.id).toBe('s1'))
    expect(await within(card).findByRole('link', { name: 'Open inventory' })).toHaveAttribute(
      'href',
      '/vendor/inventory',
    )
    expect(within(card).queryByRole('link', { name: /Open order/ })).not.toBeInTheDocument()

    const priceCard = within(waiting).getByRole('region', { name: 'Change price of Onion 1 kg to ₹45' })
    await user.click(within(priceCard).getByRole('button', { name: 'Not now' }))
    await waitFor(() => expect(api.callsTo('POST /approvals/:id/reject')[0]?.body).toEqual({ comment: 'Not now' }))

    const history = screen.getByRole('table', { name: 'Assistant action history' })
    await waitFor(() => expect(within(history).getAllByRole('row')).toHaveLength(3))
    expect(api.callsTo('GET /approvals').map((call) => call.params)).toEqual(
      expect.arrayContaining([
        { scope: 'mine', status: 'PENDING_APPROVAL', page: 0, size: 20 },
        { scope: 'mine', page: 0, size: 10 },
      ]),
    )
  })

  it('a completed price change above the threshold says it waits for an admin; ?id= opens one request', async () => {
    const user = userEvent.setup()
    const done = {
      ...adjustStockApproval({
        id: 's3',
        action: 'CHANGE_PRICE',
        summary: { title: 'Change price of Rice 5 kg to ₹380', lines: [], amount: 380, currency: 'INR' },
      }),
      status: 'COMPLETED',
      canDecide: false,
      executedAt: '2026-11-13T10:00:00Z',
      result: { outcome: 'PENDING_APPROVAL', requestId: 'pr1' },
    }
    setup(['VENDOR'], { vendorId: 'v1' }, [VENDOR_AGENT], [done])
    const { router } = renderApp('/vendor/assistant?id=s3')

    const drawer = await screen.findByRole('dialog', { name: 'Change price of Rice 5 kg to ₹380' })
    expect(drawer).toHaveTextContent('it now waits for an admin’s approval')
    expect(within(drawer).getByRole('link', { name: 'Open pricing' })).toHaveAttribute('href', '/vendor/pricing')
    expect(drawer).toHaveTextContent('Vendor assistant')
    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(new URLSearchParams(router.state.location.search).get('id')).toBeNull())
  })

  it('the restaurant owner confirms marking a dish sold out', async () => {
    const user = userEvent.setup()
    const soldOut = adjustStockApproval({
      id: 'm1',
      action: 'SET_MENU_ITEM_AVAILABILITY',
      agent: 'restaurant',
      summary: { title: 'Mark Paneer Tikka sold out', lines: [], amount: null, currency: null },
    })
    const { api } = setup(['RESTAURANT_OWNER'], { restaurantId: 'r1' }, [RESTAURANT_AGENT], [soldOut])
    renderApp('/restaurant/assistant')

    const card = await screen.findByRole('region', { name: 'Mark Paneer Tikka sold out' })
    expect(card).toHaveTextContent('Dish availability')
    await user.click(within(card).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(api.callsTo('POST /approvals/:id/approve')).toHaveLength(1))
    expect(await within(card).findByRole('link', { name: 'Open the menu' })).toHaveAttribute('href', '/restaurant/menu')
  })
})
