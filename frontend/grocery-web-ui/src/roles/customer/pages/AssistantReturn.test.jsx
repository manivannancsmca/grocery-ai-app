import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { approvalActionLabel, keysChangedBy, TOOL_LABELS } from '../../../features/ai/labels'
import { orderKeys } from '../../../features/orders/api'
import { returnKeys } from '../../../features/returns/api'
import { aiStatus, placeOrderApproval, SHOPPING_AGENT } from '../../../test/aiFixtures'
import { cartView } from '../../../test/cartFixtures'
import { mockApi } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

/** Phase 12 §6: the shopping assistant's `requestReturn` → REQUEST_RETURN, decided by the customer. */
const requestReturn = placeOrderApproval({
  id: 'a7',
  action: 'REQUEST_RETURN',
  summary: {
    title: 'Return 1 item from order GA-7K3F9Q2M',
    lines: [
      { label: 'Basmati Rice', value: '1' },
      { label: 'Reason', value: 'Damaged' },
    ],
    amount: null,
    currency: null,
  },
  rationale: 'You said the rice packet was torn.',
})

describe('Assistant return requests (customer)', () => {
  it('names the action and its tool, and refreshes orders and returns when it ran', () => {
    expect(approvalActionLabel('REQUEST_RETURN')).toBe('Request a return')
    expect(TOOL_LABELS.requestReturn).toBe('Request a return')
    expect(keysChangedBy('REQUEST_RETURN')).toEqual([orderKeys.all, returnKeys.all])
  })

  it('"Confirm return" runs it; the card shows the return number, the server’s refund and a link to my returns', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    let approvals = [requestReturn]
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /ai/agents': { status: 200, data: [SHOPPING_AGENT] },
      'GET /ai/status': { status: 200, data: aiStatus({ provider: 'scripted', model: 'scripted' }) },
      'GET /ai/conversations': pageOf([]),
      'GET /cart': { status: 200, data: cartView([]) },
      'GET /approvals': (request) =>
        pageOf(approvals.filter((approval) => !request.params.status || approval.status === request.params.status)),
      'GET /approvals/:id': (request) => ({
        status: 200,
        data: approvals.find((approval) => approval.id === request.pathParams.id),
      }),
      'POST /approvals/:id/approve': () => {
        const done = {
          ...requestReturn,
          status: 'COMPLETED',
          canDecide: false,
          decidedBy: 'user-1',
          decidedAt: '2026-11-13T10:05:00Z',
          executedAt: '2026-11-13T10:05:01Z',
          result: {
            id: 'ret1',
            returnNumber: 'RT-7K3M9QX2',
            orderId: 'o1',
            orderNumber: 'GA-7K3F9Q2M',
            refundAmount: 47.5,
            status: 'REQUESTED',
          },
        }
        approvals = [done]
        return { status: 200, data: done }
      },
    })
    renderApp('/assistant/approvals')

    const waiting = await screen.findByRole('region', { name: 'Waiting for you' })
    const card = await within(waiting).findByRole('region', { name: 'Return 1 item from order GA-7K3F9Q2M' })
    expect(within(card).getByText('Request a return')).toBeInTheDocument()
    await user.click(within(card).getByRole('button', { name: 'Confirm return' }))

    await waitFor(() => expect(api.callsTo('POST /approvals/:id/approve')).toHaveLength(1))
    expect(
      await within(card).findByText('Return RT-7K3M9QX2 requested. If the store approves it, ₹47.50 will be refunded.'),
    ).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'See my returns' })).toHaveAttribute('href', '/account/returns')
  })
})
