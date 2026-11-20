import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

function request(id, productName, overrides = {}) {
  return {
    id,
    productId: `prod-${id}`,
    productName,
    unitLabel: '500 g',
    vendorId: 'v1',
    vendorName: 'Green Basket',
    currentMrp: 60,
    currentSellingPrice: 49,
    requestedMrp: 60,
    requestedSellingPrice: 25,
    changePercent: -48.98,
    status: 'PENDING',
    note: 'Clearance',
    requestedBy: 'user-1',
    requestedAt: '2026-11-11T09:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionReason: null,
    ...overrides,
  }
}

const tomato = request('r1', 'Tomato')
const milk = request('r2', 'Milk', {
  currentMrp: 30,
  currentSellingPrice: 28,
  requestedMrp: 40,
  requestedSellingPrice: 36,
  changePercent: 28.57,
  note: null,
})
const approvedOnion = request('r0', 'Onion', {
  status: 'APPROVED',
  decidedBy: 'admin-1',
  decidedAt: '2026-11-10T12:00:00Z',
  decisionReason: 'Seasonal price',
})

function page(content) {
  return {
    status: 200,
    data: { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 },
  }
}

function approvalsApi(routes = {}) {
  return mockApi({
    'GET /admin/price-change-requests': (call) =>
      call.params.status === 'APPROVED' ? page([approvedOnion]) : page([tomato, milk]),
    ...routes,
  })
}

describe('Admin price approvals', () => {
  it('lists the pending queue with the change in percent and approves with an optional note', async () => {
    const user = userEvent.setup()
    signInAs(['FINANCE_ADMIN'])
    const api = approvalsApi({
      'POST /admin/price-change-requests/:id/approve': { status: 200, data: { ...tomato, status: 'APPROVED' } },
    })
    renderApp('/admin/price-approvals')

    const table = await screen.findByRole('table', { name: 'Pending price changes' })
    const tomatoRow = within(table).getByText('Tomato').closest('tr')
    expect(tomatoRow).toHaveTextContent('Green Basket')
    expect(tomatoRow).toHaveTextContent('₹49.00')
    expect(tomatoRow).toHaveTextContent('₹25.00')
    expect(within(tomatoRow).getByText('−48.98%')).toBeInTheDocument()
    expect(tomatoRow).toHaveTextContent('(big drop)')
    expect(within(table).getByText('Milk').closest('tr')).toHaveTextContent('+28.57%')
    expect(within(table).getByText('Milk').closest('tr')).not.toHaveTextContent('(big')
    expect(api.callsTo('GET /admin/price-change-requests')[0]?.params).toMatchObject({ status: 'PENDING', page: 0 })
    expect(within(screen.getByRole('tab', { name: /pending/i })).getByText('2')).toBeInTheDocument()

    await user.click(within(table).getByRole('button', { name: 'Approve the price change for Tomato' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve the price change for Tomato?' })
    expect(dialog).toHaveTextContent('from ₹49.00 to ₹25.00 (−48.98%)')
    await user.type(within(dialog).getByLabelText(/^Note/), 'Clearance sale ok')
    await user.click(within(dialog).getByRole('button', { name: 'Approve change' }))

    expect(await screen.findByText('Approved. Tomato now sells at ₹25.00.')).toBeInTheDocument()
    const approve = api.callsTo('POST /admin/price-change-requests/:id/approve')
    expect(approve[0]?.pathParams.id).toBe('r1')
    expect(approve[0]?.body).toEqual({ note: 'Clearance sale ok' })
  })

  it('requires a reason to reject', async () => {
    const user = userEvent.setup()
    signInAs(['OPS_ADMIN'])
    const api = approvalsApi({
      'POST /admin/price-change-requests/:id/reject': (call) => ({
        status: 200,
        data: { ...milk, status: 'REJECTED', decisionReason: call.body.reason },
      }),
    })
    renderApp('/admin/price-approvals')

    const table = await screen.findByRole('table', { name: 'Pending price changes' })
    await user.click(within(table).getByRole('button', { name: 'Reject the price change for Milk' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject the price change for Milk?' })
    await user.click(within(dialog).getByRole('button', { name: 'Reject change' }))
    expect(await within(dialog).findByText(/enter a reason/i)).toBeInTheDocument()
    expect(api.callsTo('POST /admin/price-change-requests/:id/reject')).toHaveLength(0)

    await user.type(within(dialog).getByLabelText(/reason/i), 'Too high for this category')
    await user.click(within(dialog).getByRole('button', { name: 'Reject change' }))

    expect(await screen.findByText('Price change for Milk rejected. The live price stays.')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/price-change-requests/:id/reject')[0]?.body).toEqual({
      reason: 'Too high for this category',
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('tells the admin when someone else decided first (409 PRICE_CHANGE_NOT_PENDING) and refreshes the queue', async () => {
    const user = userEvent.setup()
    signInAs(['SUPER_ADMIN'])
    const api = approvalsApi({
      'POST /admin/price-change-requests/:id/approve': problem(
        409,
        'PRICE_CHANGE_NOT_PENDING',
        'Request is not pending',
      ),
    })
    renderApp('/admin/price-approvals')

    const table = await screen.findByRole('table', { name: 'Pending price changes' })
    const listCalls = api.callsTo('GET /admin/price-change-requests').length
    await user.click(within(table).getByRole('button', { name: 'Approve the price change for Tomato' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve the price change for Tomato?' })
    await user.click(within(dialog).getByRole('button', { name: 'Approve change' }))

    expect(await screen.findByText(/price change for tomato was already decided/i)).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /admin/price-change-requests').length).toBeGreaterThan(listCalls))
  })

  it('is read-only for admin roles without the approval permission, and shows the history by status', async () => {
    const user = userEvent.setup()
    signInAs(['SUPPORT'])
    const api = approvalsApi()
    const { router } = renderApp('/admin/price-approvals')

    const table = await screen.findByRole('table', { name: 'Pending price changes' })
    expect(screen.getByText(/can view price changes but not approve or reject them/i)).toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: /approve/i })).not.toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: /reject/i })).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Approved' }))
    expect(router.state.location.search).toBe('?status=APPROVED')
    const history = await screen.findByRole('table', { name: 'Approved price changes' })
    expect(within(history).getByText('Onion').closest('tr')).toHaveTextContent('Seasonal price')
    expect(within(history).getByText('Approved')).toBeInTheDocument()
    expect(api.callsTo('GET /admin/price-change-requests').at(-1)?.params).toMatchObject({ status: 'APPROVED' })
  })
})
