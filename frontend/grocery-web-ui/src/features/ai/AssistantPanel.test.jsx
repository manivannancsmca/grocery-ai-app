import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import {
  ADMIN_BI_AGENT,
  aiStatus,
  completedOrder,
  PAYMENT_AGENT,
  placeOrderApproval,
  refundApproval,
  SHOPPING_AGENT,
  SUPPORT_AGENT,
} from '../../test/aiFixtures'
import { cartView } from '../../test/cartFixtures'
import { mockApi, problem } from '../../test/mockApi'
import { mockChatStream } from '../../test/mockChatStream'
import { pageOf } from '../../test/orderFixtures'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'

function customer(routes = {}) {
  signInAs(['CUSTOMER'])
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /ai/agents': { status: 200, data: [SHOPPING_AGENT] },
    'GET /ai/status': { status: 200, data: aiStatus() },
    'GET /ai/conversations': pageOf([]),
    'GET /cart': { status: 200, data: cartView([]) },
    'GET /orders': pageOf([]),
    ...routes,
  })
}

function admin(roles, routes = {}) {
  signInAs(roles, { id: 'admin-1', fullName: 'Ravi Admin' })
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /ai/agents': { status: 200, data: [ADMIN_BI_AGENT] },
    'GET /ai/status': { status: 200, data: aiStatus() },
    'GET /ai/conversations': pageOf([]),
    'GET /admin/ops/:service/dead-letters': pageOf([]),
    'GET /admin/system/connectors': { status: 200, data: {} },
    'GET /admin/orders/stuck': { status: 200, data: [] },
    ...routes,
  })
}

async function openPanel(user, name) {
  await user.click(await screen.findByRole('button', { name: 'Open the assistant' }))
  return screen.findByRole('dialog', { name })
}

async function ask(user, panel, message) {
  await user.type(within(panel).getByRole('textbox', { name: 'Message the assistant' }), message)
  await user.click(within(panel).getByRole('button', { name: 'Send' }))
}

describe('Assistant panel — agent by role', () => {
  it('a customer gets the shopping assistant, with its example prompts', async () => {
    const user = userEvent.setup()
    const api = customer()
    const chat = mockChatStream()
    renderApp('/orders')

    const panel = await openPanel(user, 'Shopping assistant')
    expect(panel).toHaveTextContent(SHOPPING_AGENT.description)
    const prompts = within(panel).getByRole('list', { name: 'Example prompts' })
    expect(
      within(prompts)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(SHOPPING_AGENT.examplePrompts)
    expect(within(panel).getByRole('link', { name: 'Assistant actions →' })).toHaveAttribute(
      'href',
      '/assistant/approvals',
    )

    // An example prompt is sent as it is, to the agent the server offered.
    await user.click(within(prompts).getByRole('button', { name: 'show my cart' }))
    const stream = await chat.connection()
    expect(stream.request.agent).toBe('shopping')
    expect(stream.request.body).toEqual({ message: 'show my cart' })
    expect(api.callsTo('GET /ai/status')).toHaveLength(1)
  })

  it('a customer has the shopping, payment and support assistants and switches between them', async () => {
    const user = userEvent.setup()
    customer({ 'GET /ai/agents': { status: 200, data: [SUPPORT_AGENT, SHOPPING_AGENT, PAYMENT_AGENT] } })
    const chat = mockChatStream()
    renderApp('/orders')

    // The customer starts with the shopping assistant; the switcher keeps one order whatever the server sends.
    let panel = await openPanel(user, 'Shopping assistant')
    let switcher = within(panel).getByRole('group', { name: 'Assistants' })
    expect(
      within(switcher)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(['Shopping', 'Payments', 'Support'])
    expect(within(switcher).getByRole('button', { name: 'Shopping assistant' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(switcher).getByRole('button', { name: 'Switch to Payment assistant' })).toHaveAttribute(
      'title',
      PAYMENT_AGENT.description,
    )

    await user.click(within(switcher).getByRole('button', { name: 'Switch to Payment assistant' }))
    panel = await screen.findByRole('dialog', { name: 'Payment assistant' })
    expect(panel).toHaveTextContent(PAYMENT_AGENT.description)
    expect(within(panel).getByRole('link', { name: 'Assistant actions →' })).toHaveAttribute(
      'href',
      '/assistant/approvals',
    )
    const prompts = within(panel).getByRole('list', { name: 'Example prompts' })
    await user.click(within(prompts).getByRole('button', { name: 'Where is my refund?' }))
    const stream = await chat.connection()
    expect(stream.request.agent).toBe('payment')

    // Switching waits while an answer streams; afterwards the support assistant is one tap away.
    switcher = within(panel).getByRole('group', { name: 'Assistants' })
    expect(within(switcher).getByRole('button', { name: 'Switch to Customer support assistant' })).toBeDisabled()
    stream.done()
    await waitFor(() =>
      expect(within(switcher).getByRole('button', { name: 'Switch to Customer support assistant' })).toBeEnabled(),
    )
    await user.click(within(switcher).getByRole('button', { name: 'Switch to Customer support assistant' }))
    expect(await screen.findByRole('dialog', { name: 'Customer support assistant' })).toHaveTextContent(
      SUPPORT_AGENT.description,
    )
  })

  it('an admin gets the business assistant', async () => {
    const user = userEvent.setup()
    admin(['FINANCE_ADMIN'])
    const chat = mockChatStream()
    renderApp('/admin/operations')

    const panel = await openPanel(user, 'Business assistant')
    expect(within(panel).getByRole('link', { name: 'AI approvals →' })).toHaveAttribute('href', '/admin/ai/approvals')
    await ask(user, panel, 'sales today')
    expect((await chat.connection()).request.agent).toBe('admin-bi')
  })

  it('Phase 12: store roles ask for their agent too; no launcher while the server offers none', async () => {
    signInAs(['VENDOR'])
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /ai/agents': { status: 200, data: [] },
    })
    renderApp('/notifications')
    expect(await screen.findByRole('heading', { level: 1, name: 'Notifications' })).toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /ai/agents')).toHaveLength(1))
    expect(screen.queryByRole('button', { name: 'Open the assistant' })).not.toBeInTheDocument()
  })

  it('no launcher when the server offers no agent', async () => {
    const api = customer({ 'GET /ai/agents': { status: 200, data: [] } })
    renderApp('/orders')
    await waitFor(() => expect(api.callsTo('GET /ai/agents')).toHaveLength(1))
    expect(await screen.findByRole('heading', { level: 1, name: 'My orders' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Open the assistant' })).not.toBeInTheDocument()
  })
})

describe('Assistant panel — a streamed answer', () => {
  it('renders tokens as they arrive, tool chips per call, refreshes the cart after addToCart, and shows the approval card', async () => {
    const user = userEvent.setup()
    const api = customer()
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')

    await ask(user, panel, 'add 2 rice and check out')
    const log = within(panel).getByRole('log', { name: 'Conversation' })
    expect(within(log).getByText('add 2 rice and check out')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Stop' })).toBeInTheDocument()
    expect(within(log).getByLabelText('The assistant is writing')).toBeInTheDocument()

    const stream = await chat.connection()
    expect(stream.request.headers.get('Authorization')).toBe('Bearer test-access-token')
    stream.conversation('c1')
    stream.tool({ invocationId: 'i1', tool: 'searchProducts', label: 'Searched products', status: 'STARTED' })
    const tools = await within(log).findByRole('list', { name: 'Tools used' })
    expect(within(tools).getByRole('listitem')).toHaveTextContent('Searched products (Working)')
    stream.tool({
      invocationId: 'i1',
      tool: 'searchProducts',
      label: 'Searched products',
      status: 'SUCCEEDED',
      summary: '3 products',
    })
    await waitFor(() => expect(within(tools).getByRole('listitem')).toHaveTextContent('Searched products (Done)'))

    const cartReads = api.callsTo('GET /cart').length
    stream.tool({
      invocationId: 'i2',
      tool: 'addToCart',
      label: 'Added to cart',
      status: 'SUCCEEDED',
      summary: '2 × Smart Rice',
    })
    await waitFor(() => expect(api.callsTo('GET /cart').length).toBeGreaterThan(cartReads))

    stream.token('I added **2 × Smart Rice** ')
    expect(await within(log).findByText('2 × Smart Rice', { selector: 'strong' })).toBeInTheDocument()
    stream.token('to your cart.\n- Total ₹225\n- Delivery to Home')
    expect(await within(log).findByText('Total ₹225', { selector: 'li' })).toBeInTheDocument()
    expect(within(log).getByText('Delivery to Home', { selector: 'li' })).toBeInTheDocument()

    stream.tool({ invocationId: 'i3', tool: 'placeOrder', label: 'Prepared your order', status: 'APPROVAL_REQUIRED' })
    stream.tool({
      invocationId: 'i4',
      tool: 'refundOrder',
      label: 'refundOrder',
      status: 'BLOCKED',
      summary: 'TOOL_NOT_ALLOWED: not available to this assistant',
    })
    stream.approval(placeOrderApproval())
    stream.done()

    const card = await within(log).findByRole('region', { name: 'Place order: 2 items from Fresh Mart' })
    expect(card).toHaveTextContent('Smart Rice × 2₹200.00')
    expect(card).toHaveTextContent('Delivery fee₹25.00')
    expect(card).toHaveTextContent('Amount₹225.00')
    expect(card).toHaveTextContent('Fingerprint3f9a1c2e')
    expect(card).toHaveTextContent('The assistant’s noteYou asked me to check out.')
    expect(card).toHaveTextContent(/Expires in 1[45]:\d\d/)
    expect(within(card).getByText('Waiting for approval')).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Confirm order' })).toBeEnabled()
    expect(within(card).getByRole('button', { name: 'Not now' })).toBeEnabled()

    const chips = within(tools).getAllByRole('listitem')
    expect(chips.map((chip) => chip.getAttribute('data-status'))).toEqual([
      'SUCCEEDED',
      'SUCCEEDED',
      'APPROVAL_REQUIRED',
      'BLOCKED',
    ])
    expect(chips[2]).toHaveTextContent('Prepared your order · Needs approval')
    expect(chips[3]).toHaveTextContent('refundOrder · Blocked')
    expect(chips[3]?.className).toMatch(/red/)
    expect(within(log).getByText('refundOrder: TOOL_NOT_ALLOWED: not available to this assistant')).toBeInTheDocument()

    // The turn is over: the composer is back.
    expect(await within(panel).findByRole('button', { name: 'Send' })).toBeInTheDocument()
    expect(within(log).queryByLabelText('The assistant is writing')).not.toBeInTheDocument()
  })

  it('never injects markup from the model', async () => {
    const user = userEvent.setup()
    customer()
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')
    await ask(user, panel, 'hi')
    const stream = await chat.connection()
    stream.token('<img src=x onerror="alert(1)"> <b>bold</b>')
    stream.done()

    const log = within(panel).getByRole('log', { name: 'Conversation' })
    expect(await within(log).findByText('<img src=x onerror="alert(1)"> <b>bold</b>')).toBeInTheDocument()
    expect(log.querySelector('img, b')).toBeNull()
  })

  it('links the pages an answer is about: inline links to its own pages, buttons for what the tools found', async () => {
    const user = userEvent.setup()
    customer()
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')
    await ask(user, panel, 'show my cart')
    const stream = await chat.connection()
    stream.conversation('c1')
    stream.tool({ tool: 'getCart', status: 'STARTED', invocationId: 'i1', label: 'Checked the cart', links: [] })
    stream.tool({
      tool: 'getCart',
      status: 'SUCCEEDED',
      invocationId: 'i1',
      label: 'Checked the cart',
      summary: '2 items',
      links: [{ label: 'View my cart', path: '/cart' }],
    })
    stream.token(
      'You have 2 items. Ready? Go to [Checkout](/checkout). Or try [our partner](https://evil.example/x) or [admin](/admin/orders).',
    )
    const log = within(panel).getByRole('log', { name: 'Conversation' })
    expect(await within(log).findByRole('link', { name: 'Checkout' })).toHaveAttribute('href', '/checkout')
    // The buttons wait for the complete answer.
    expect(within(log).queryByRole('navigation', { name: 'Related pages' })).not.toBeInTheDocument()
    stream.done()

    const related = await within(log).findByRole('navigation', { name: 'Related pages' })
    expect(
      within(related)
        .getAllByRole('link')
        .map((link) => [link.textContent, link.getAttribute('href')]),
    ).toEqual([['View my cart', '/cart']])
    // Another website or another area's page is never a link: only its words remain.
    expect(within(log).queryByRole('link', { name: 'our partner' })).not.toBeInTheDocument()
    expect(within(log).queryByRole('link', { name: 'admin' })).not.toBeInTheDocument()
    expect(log).toHaveTextContent('Or try our partner or admin.')
    expect(log.innerHTML).not.toContain('evil.example')

    // Following a link opens the page and closes the panel.
    await user.click(within(related).getByRole('link', { name: 'View my cart' }))
    expect(await screen.findByRole('heading', { name: 'Your cart' })).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Shopping assistant' })).not.toBeInTheDocument())
  })

  it('Stop cancels the answer; what arrived stays', async () => {
    const user = userEvent.setup()
    customer()
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')
    await ask(user, panel, 'show my cart')
    const stream = await chat.connection()
    stream.token('Your cart has')
    const log = within(panel).getByRole('log', { name: 'Conversation' })
    expect(await within(log).findByText('Your cart has')).toBeInTheDocument()

    await user.click(within(panel).getByRole('button', { name: 'Stop' }))
    expect(await within(log).findByText('Stopped.')).toBeInTheDocument()
    expect(stream.aborted).toBe(true)
    expect(within(log).getByText('Your cart has')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Send' })).toBeInTheDocument()
  })

  it('continues the conversation: the next message carries its id', async () => {
    const user = userEvent.setup()
    customer()
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')
    await ask(user, panel, 'search rice')
    const first = await chat.connection(0)
    first.conversation('c42')
    first.token('Found 3.')
    first.done()
    expect(await within(panel).findByText('Found 3.')).toBeInTheDocument()

    await ask(user, panel, 'add the first')
    expect((await chat.connection(1)).request.body).toEqual({ conversationId: 'c42', message: 'add the first' })
  })
})

describe('Assistant panel — approval cards', () => {
  async function cardFor(approvalRoutes) {
    const user = userEvent.setup()
    const api = customer(approvalRoutes)
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')
    await ask(user, panel, 'place my order')
    const stream = await chat.connection()
    stream.conversation('c1')
    stream.approval(placeOrderApproval())
    stream.token('Please confirm your order.')
    stream.done()
    const card = await within(panel).findByRole('region', { name: 'Place order: 2 items from Fresh Mart' })
    return { user, api, panel, card }
  }

  it('"Confirm order" approves it; the completed card links to the order and the cart and orders are refreshed', async () => {
    const { user, api, card } = await cardFor({
      'POST /approvals/:id/approve': { status: 200, data: completedOrder() },
    })
    const cartReads = api.callsTo('GET /cart').length
    const orderReads = api.callsTo('GET /orders').length

    await user.click(within(card).getByRole('button', { name: 'Confirm order' }))

    expect(await within(card).findByText('Order GA-AI000001 placed')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Open order GA-AI000001' })).toHaveAttribute('href', '/orders/o9')
    expect(within(card).getByText('Completed')).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'Confirm order' })).not.toBeInTheDocument()
    const [approve] = api.callsTo('POST /approvals/:id/approve')
    expect(approve?.path).toBe('/approvals/a1/approve')
    expect(approve?.body).toEqual({})
    await waitFor(() => expect(api.callsTo('GET /cart').length).toBeGreaterThan(cartReads))
    await waitFor(() => expect(api.callsTo('GET /orders').length).toBeGreaterThan(orderReads))
  })

  it('shows a FAILED execution with its reason', async () => {
    const failed = {
      ...placeOrderApproval(),
      status: 'FAILED',
      canDecide: false,
      failure: {
        code: 'TOTAL_CHANGED',
        message: 'The total changed since the preview. Ask the assistant again.',
        httpStatus: 409,
      },
    }
    const { user, card } = await cardFor({ 'POST /approvals/:id/approve': { status: 200, data: failed } })
    await user.click(within(card).getByRole('button', { name: 'Confirm order' }))
    expect(await within(card).findByText('Could not complete')).toBeInTheDocument()
    expect(card).toHaveTextContent('The total changed since the preview. Ask the assistant again.')
    expect(card).toHaveTextContent('TOTAL_CHANGED')
  })

  it('"Not now" rejects it with a comment', async () => {
    const rejected = {
      ...placeOrderApproval(),
      status: 'REJECTED',
      canDecide: false,
      comment: 'Not now',
      decidedAt: '2026-11-13T10:05:00Z',
    }
    const { user, api, card } = await cardFor({ 'POST /approvals/:id/reject': { status: 200, data: rejected } })

    await user.click(within(card).getByRole('button', { name: 'Not now' }))

    expect(await within(card).findByText('“Not now”')).toBeInTheDocument()
    expect(within(card).getAllByText('Not approved').length).toBeGreaterThan(0)
    expect(api.callsTo('POST /approvals/:id/reject')[0]?.body).toEqual({ comment: 'Not now' })
  })

  it('409 APPROVAL_NOT_PENDING: explains it was decided already and re-reads the request', async () => {
    let reads = 0
    const { user, api, card } = await cardFor({
      'POST /approvals/:id/approve': {
        status: 409,
        data: { ...problem(409, 'APPROVAL_NOT_PENDING', 'Not pending').data, currentStatus: 'COMPLETED' },
      },
      'GET /approvals/:id': () => {
        reads++
        return { status: 200, data: completedOrder() }
      },
    })

    await user.click(within(card).getByRole('button', { name: 'Confirm order' }))

    expect(await within(card).findByRole('alert')).toHaveTextContent(
      'This request was already decided (now: Completed), so nothing was done this time. It has been refreshed.',
    )
    await waitFor(() => expect(reads).toBe(1))
    expect(await within(card).findByText('Order GA-AI000001 placed')).toBeInTheDocument()
    expect(api.callsTo('GET /approvals/:id')[0]?.path).toBe('/approvals/a1')
  })

  it('409 for an expired request says so', async () => {
    const { user, card } = await cardFor({
      'POST /approvals/:id/approve': {
        status: 409,
        data: { ...problem(409, 'APPROVAL_NOT_PENDING').data, currentStatus: 'EXPIRED' },
      },
      'GET /approvals/:id': { status: 200, data: { ...placeOrderApproval(), status: 'EXPIRED', canDecide: false } },
    })
    await user.click(within(card).getByRole('button', { name: 'Confirm order' }))
    expect(await within(card).findByRole('alert')).toHaveTextContent('This request has expired, so nothing was done.')
  })
})

describe('Assistant panel — admins decide with a comment', () => {
  it('approves a refund with an optional comment', async () => {
    const user = userEvent.setup()
    const approval = refundApproval({ requestedBy: 'someone-else', canDecide: true })
    const api = admin(['FINANCE_ADMIN'], {
      'POST /approvals/:id/approve': {
        status: 200,
        data: {
          ...approval,
          status: 'COMPLETED',
          canDecide: false,
          result: { refundId: 'r1' },
          comment: 'Checked the photos',
        },
      },
    })
    const chat = mockChatStream()
    renderApp('/admin/operations')
    const panel = await openPanel(user, 'Business assistant')
    await ask(user, panel, 'refund 2500 for GA-7K3F9Q2M because damaged')
    const stream = await chat.connection()
    stream.approval(approval)
    stream.done()

    const card = await within(panel).findByRole('region', { name: 'Refund ₹2,500.00 for GA-7K3F9Q2M' })
    expect(within(card).getByText('Four-eyes')).toBeInTheDocument()
    expect(card).toHaveTextContent('Amount₹2,500.00')
    expect(card).toHaveTextContent(/Expires in 2[23] h/)
    expect(within(card).queryByRole('button', { name: 'Confirm order' })).not.toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve this request?' })
    await user.type(within(dialog).getByRole('textbox', { name: 'Comment (optional)' }), 'Checked the photos')
    await user.click(within(dialog).getByRole('button', { name: 'Approve and run' }))

    expect(await within(card).findByText('Done')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Approve this request?' })).not.toBeInTheDocument()
    expect(api.callsTo('POST /approvals/:id/approve')[0]?.body).toEqual({ comment: 'Checked the photos' })
  })

  it('FOUR_EYES_REQUIRED: the requester is told another admin must decide', async () => {
    const user = userEvent.setup()
    // The server said canDecide (e.g. a stale card); the decision itself is refused.
    const approval = refundApproval({ requestedBy: 'admin-1', canDecide: true })
    admin(['SUPER_ADMIN'], {
      'POST /approvals/:id/approve': problem(403, 'FOUR_EYES_REQUIRED', 'Another admin must approve this'),
    })
    const chat = mockChatStream()
    renderApp('/admin/operations')
    const panel = await openPanel(user, 'Business assistant')
    await ask(user, panel, 'refund')
    const stream = await chat.connection()
    stream.approval(approval)
    stream.done()

    const card = await within(panel).findByRole('region', { name: 'Refund ₹2,500.00 for GA-7K3F9Q2M' })
    await user.click(within(card).getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve this request?' })
    await user.click(within(dialog).getByRole('button', { name: 'Approve and run' }))

    expect(await within(card).findByRole('alert')).toHaveTextContent(
      'You proposed this request, so another admin has to decide it (four-eyes rule). Nothing was done.',
    )
    expect(screen.queryByRole('dialog', { name: 'Approve this request?' })).not.toBeInTheDocument()
    // The requester may still withdraw it.
    expect(within(card).getByRole('button', { name: 'Cancel request' })).toBeInTheDocument()
  })

  it('the requester of a four-eyes request sees who decides, and can cancel it', async () => {
    const user = userEvent.setup()
    const approval = refundApproval({ requestedBy: 'admin-1', canDecide: false })
    const api = admin(['SUPER_ADMIN'], {
      'POST /approvals/:id/cancel': { status: 200, data: { ...approval, status: 'CANCELLED' } },
    })
    const chat = mockChatStream()
    renderApp('/admin/operations')
    const panel = await openPanel(user, 'Business assistant')
    await ask(user, panel, 'refund')
    const stream = await chat.connection()
    stream.approval(approval)
    stream.done()

    const card = await within(panel).findByRole('region', { name: 'Refund ₹2,500.00 for GA-7K3F9Q2M' })
    expect(card).toHaveTextContent('You proposed it, so another admin has to decide it (four-eyes rule).')
    expect(within(card).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    await user.click(within(card).getByRole('button', { name: 'Cancel request' }))
    const confirm = await screen.findByRole('dialog', { name: 'Cancel this request?' })
    await user.click(within(confirm).getByRole('button', { name: 'Cancel request' }))
    expect(await within(card).findByText('The request was withdrawn; nothing was done.')).toBeInTheDocument()
    expect(api.callsTo('POST /approvals/:id/cancel')).toHaveLength(1)
  })
})

describe('Assistant panel — offline, errors and limits', () => {
  it('`available: false` shows the offline state and disables sending; the page keeps working', async () => {
    const user = userEvent.setup()
    customer({ 'GET /ai/status': { status: 200, data: aiStatus({ available: false }) } })
    mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')

    const offline = await within(panel).findByRole('status')
    expect(offline).toHaveTextContent('The assistant is offline')
    expect(offline).toHaveTextContent('Everything else — shopping, your cart, orders and approvals — works as usual.')
    expect(within(panel).getByRole('textbox', { name: 'Message the assistant' })).toBeDisabled()
    expect(within(panel).getByRole('button', { name: 'Send' })).toBeDisabled()
    for (const prompt of within(within(panel).getByRole('list', { name: 'Example prompts' })).getAllByRole('button'))
      expect(prompt).toBeDisabled()
  })

  it('an `error` event ends the turn with its friendly message; the question stays', async () => {
    const user = userEvent.setup()
    customer()
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')
    await ask(user, panel, 'show my cart')
    const stream = await chat.connection()
    stream.conversation('c1')
    stream.error('AI_UNAVAILABLE', 'The assistant is not available right now. Everything else works as usual.')

    const log = within(panel).getByRole('log', { name: 'Conversation' })
    expect(await within(log).findByRole('alert')).toHaveTextContent(
      'The assistant is not available right now. Everything else works as usual.',
    )
    expect(within(log).getByText('show my cart')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Send' })).toBeInTheDocument()
  })

  it('429 AI_RATE_LIMITED tells the user to wait', async () => {
    const user = userEvent.setup()
    customer()
    mockChatStream(() => ({
      kind: 'status',
      status: 429,
      headers: { 'Retry-After': '600' },
      body: { status: 429, errorCode: 'AI_RATE_LIMITED', detail: 'Rate limited' },
    }))
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')
    await ask(user, panel, 'hello')

    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'You have sent the assistant a lot of messages in the last hour. Try again in about 10 min. Everything else works as usual.',
    )
  })

  it('an unreachable assistant is reported in the chat, not as a broken page', async () => {
    const user = userEvent.setup()
    customer()
    mockChatStream(() => ({
      kind: 'status',
      status: 503,
      body: { status: 503, errorCode: 'SERVICE_UNAVAILABLE', detail: 'down' },
    }))
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')
    await ask(user, panel, 'hello')
    expect(await within(panel).findByRole('alert')).toHaveTextContent(
      'The assistant is not available right now. Everything else works as usual.',
    )
    expect(screen.getByRole('heading', { level: 1, name: 'My orders' })).toBeInTheDocument()
  })
})

describe('Assistant panel — conversations', () => {
  it('lists, opens (with tools and approval cards from history) and deletes conversations', async () => {
    const user = userEvent.setup()
    const server = {
      conversations: [
        {
          id: 'c1',
          agent: 'shopping',
          title: 'add 2 rice',
          createdAt: '2026-11-13T09:00:00Z',
          updatedAt: new Date(Date.now() - 5 * 60_000).toISOString(),
        },
        {
          id: 'c2',
          agent: 'shopping',
          title: 'status of GA-7K3F9Q2M',
          createdAt: '2026-11-12T09:00:00Z',
          updatedAt: '2026-11-12T09:05:00Z',
        },
      ],
    }
    const api = customer({
      'GET /ai/conversations': () => pageOf(server.conversations),
      'GET /ai/conversations/:id': {
        status: 200,
        data: {
          id: 'c1',
          agent: 'shopping',
          title: 'add 2 rice',
          messages: [
            {
              id: 'm1',
              role: 'USER',
              content: 'add 2 rice',
              createdAt: '2026-11-13T09:00:00Z',
              tools: [],
              approvals: [],
            },
            {
              id: 'm2',
              role: 'ASSISTANT',
              content: 'Done. Shall I place the order?',
              createdAt: '2026-11-13T09:00:05Z',
              tools: [
                {
                  invocationId: 'i1',
                  tool: 'addToCart',
                  label: 'Added to cart',
                  status: 'SUCCEEDED',
                  summary: null,
                  links: [{ label: 'View my cart', path: '/cart' }],
                },
              ],
              approvals: [completedOrder()],
            },
          ],
        },
      },
      'DELETE /ai/conversations/:id': (request) => {
        server.conversations = server.conversations.filter((conversation) => conversation.id !== request.pathParams.id)
        return { status: 204 }
      },
    })
    const chat = mockChatStream()
    renderApp('/orders')
    const panel = await openPanel(user, 'Shopping assistant')

    await user.click(within(panel).getByRole('button', { name: 'Conversations' }))
    const list = await within(panel).findByRole('list', { name: 'Conversations' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(api.callsTo('GET /ai/conversations')[0]?.params).toEqual({ agent: 'shopping', page: 0, size: 20 })
    expect(list).toHaveTextContent('5 min ago')

    await user.click(within(list).getByRole('button', { name: /^add 2 rice/ }))
    const log = within(panel).getByRole('log', { name: 'Conversation' })
    expect(await within(log).findByText('Done. Shall I place the order?')).toBeInTheDocument()
    expect(within(log).getByText('add 2 rice')).toBeInTheDocument()
    expect(within(within(log).getByRole('list', { name: 'Tools used' })).getByRole('listitem')).toHaveTextContent(
      'Added to cart',
    )
    // The links stored with the tool call come back with the conversation.
    expect(
      within(within(log).getByRole('navigation', { name: 'Related pages' })).getByRole('link', {
        name: 'View my cart',
      }),
    ).toHaveAttribute('href', '/cart')
    const card = within(log).getByRole('region', { name: 'Place order: 2 items from Fresh Mart' })
    expect(within(card).getByRole('link', { name: 'Open order GA-AI000001' })).toHaveAttribute('href', '/orders/o9')

    // The next message continues it.
    await ask(user, panel, 'thanks')
    expect((await chat.connection()).request.body).toEqual({ conversationId: 'c1', message: 'thanks' })
    const stream = await chat.connection()
    stream.done()

    // Delete the open conversation: the panel starts a new one.
    await user.click(within(panel).getByRole('button', { name: 'Conversations' }))
    const again = await within(panel).findByRole('list', { name: 'Conversations' })
    await user.click(within(again).getByRole('button', { name: 'Delete conversation “add 2 rice”' }))
    const confirm = await screen.findByRole('dialog', { name: 'Delete this conversation?' })
    await user.click(within(confirm).getByRole('button', { name: 'Delete' }))
    await waitFor(() =>
      expect(within(panel).getByRole('list', { name: 'Conversations' }).querySelectorAll('li')).toHaveLength(1),
    )
    expect(api.callsTo('DELETE /ai/conversations/:id')[0]?.path).toBe('/ai/conversations/c1')

    await user.click(within(panel).getByRole('button', { name: 'Conversations' }))
    expect(await within(panel).findByRole('list', { name: 'Example prompts' })).toBeInTheDocument()
  })
})
