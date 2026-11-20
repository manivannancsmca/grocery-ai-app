import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { ApprovalView } from '../../../features/ai/api'
import type { UserRole } from '../../../shared/auth/roles'
import { refundApproval } from '../../../test/aiFixtures'
import { type MockHandler, mockApi, problem } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

/** Proposed by another admin: this finance admin may decide it (four-eyes is satisfied). */
const toDecide = refundApproval({ requestedBy: 'admin-other', canDecide: true })
/** Proposed by this admin: an admin cancel, already completed. */
const mineDone = refundApproval({
  id: 'a3',
  action: 'ADMIN_CANCEL_ORDER',
  status: 'COMPLETED',
  fourEyes: false,
  requestedBy: 'admin-1',
  canDecide: false,
  summary: { title: 'Cancel GA-RSTR0002', lines: [{ label: 'Reason', value: 'Store closed' }], amount: null, currency: null },
  decidedAt: '2026-11-13T09:00:00Z',
  result: { orderId: 'o2', orderNumber: 'GA-RSTR0002' },
})

function setup(roles: UserRole[], routes: Record<string, MockHandler> = {}) {
  signInAs(roles, { id: 'admin-1' })
  const server: { approvals: ApprovalView[] } = { approvals: [toDecide, mineDone] }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /ai/agents': { status: 200, data: [] },
    'GET /approvals': (request) => {
      const { scope, status } = request.params
      const rows = server.approvals.filter((approval) =>
        scope === 'to-decide' ? approval.status === 'PENDING_APPROVAL' && approval.canDecide : !status || approval.status === status,
      )
      return pageOf(rows)
    },
    'GET /approvals/:id': (request) => {
      const found = server.approvals.find((approval) => approval.id === request.pathParams.id)
      return found ? { status: 200, data: found } : problem(404, 'APPROVAL_NOT_FOUND', 'Approval request not found')
    },
    ...routes,
  })
  return { api, server }
}

function rows(table: HTMLElement) {
  return within(table).getAllByRole('row').slice(1)
}

describe('AI approvals (admin)', () => {
  it('"To decide" lists the pending requests this admin may decide (scope=to-decide), with its count', async () => {
    const { api } = setup(['FINANCE_ADMIN'])
    renderApp('/admin/ai/approvals')

    expect(await screen.findByRole('heading', { level: 1, name: 'AI approvals' })).toBeInTheDocument()
    const table = await screen.findByRole('table', { name: 'Approval requests to decide' })
    expect(rows(table)).toHaveLength(1)
    expect(rows(table)[0]).toHaveTextContent('Refund ₹2,500.00 for GA-7K3F9Q2M')
    expect(rows(table)[0]).toHaveTextContent('Refund payment · Business-intelligence assistant')
    expect(rows(table)[0]).toHaveTextContent('₹2,500.00')
    expect(rows(table)[0]).toHaveTextContent('Waiting for approval')
    expect(rows(table)[0]).toHaveTextContent('Four-eyes')
    expect(within(rows(table)[0] as HTMLElement).getByRole('button', { name: 'Open request Refund ₹2,500.00 for GA-7K3F9Q2M' })).toHaveTextContent('Decide')
    expect(screen.getByRole('tab', { name: 'To decide 1' })).toHaveAttribute('aria-selected', 'true')
    expect(api.callsTo('GET /approvals').map((call) => call.params)).toEqual([{ scope: 'to-decide', page: 0, size: 20 }])
  })

  it('"All" lists mine (scope=mine) with a status filter in the URL', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'])
    const { router } = renderApp('/admin/ai/approvals')
    await screen.findByRole('table', { name: 'Approval requests to decide' })

    await user.click(screen.getByRole('tab', { name: /^All/ }))
    const table = await screen.findByRole('table', { name: 'All approval requests' })
    await waitFor(() => expect(rows(table)).toHaveLength(2))
    expect(new URLSearchParams(router.state.location.search).get('tab')).toBe('all')
    // The "To decide" count stays visible from a one-row page.
    expect(await screen.findByRole('tab', { name: 'To decide 1' })).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'COMPLETED')
    await waitFor(() => expect(rows(screen.getByRole('table', { name: 'All approval requests' }))).toHaveLength(1))
    expect(screen.getByRole('table', { name: 'All approval requests' })).toHaveTextContent('Cancel GA-RSTR0002')
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('COMPLETED')
    expect(api.callsTo('GET /approvals').map((call) => call.params)).toEqual(
      expect.arrayContaining([
        { scope: 'mine', page: 0, size: 20 },
        { scope: 'to-decide', page: 0, size: 1 },
        { scope: 'mine', status: 'COMPLETED', page: 0, size: 20 },
      ]),
    )
  })

  it('?id= opens the request; approving with a comment runs it and refreshes the lists', async () => {
    const user = userEvent.setup()
    const { api, server } = setup(['FINANCE_ADMIN'], {
      'POST /approvals/:id/approve': () => {
        const done = { ...toDecide, status: 'COMPLETED', canDecide: false, decidedBy: 'admin-1', comment: 'Photos checked', result: { orderId: 'o1', orderNumber: 'GA-7K3F9Q2M' } }
        server.approvals = [done, mineDone]
        return { status: 200, data: done }
      },
    })
    const { router } = renderApp('/admin/ai/approvals?id=a2')

    const drawer = await screen.findByRole('dialog', { name: 'Refund ₹2,500.00 for GA-7K3F9Q2M' })
    expect(drawer).toHaveTextContent('Super admin or Finance admin')
    expect(drawer).toHaveTextContent('Yes — the requester may not decide')
    expect(drawer).toHaveTextContent('admin-other')
    expect(drawer).toHaveTextContent('The customer reported damaged items.')
    expect(api.callsTo('GET /approvals/:id')[0]?.path).toBe('/approvals/a2')

    await user.click(within(drawer).getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve this request?' })
    await user.type(within(dialog).getByRole('textbox', { name: 'Comment (optional)' }), 'Photos checked')
    await user.click(within(dialog).getByRole('button', { name: 'Approve and run' }))

    expect(await within(drawer).findByText('Done')).toBeInTheDocument()
    expect(within(drawer).getByRole('link', { name: 'Open order GA-7K3F9Q2M' })).toHaveAttribute('href', '/admin/orders?q=GA-7K3F9Q2M&order=o1')
    expect(api.callsTo('POST /approvals/:id/approve')[0]?.body).toEqual({ comment: 'Photos checked' })
    // The decided request leaves "To decide".
    expect(await screen.findByText('Nothing to decide')).toBeInTheDocument()

    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(new URLSearchParams(router.state.location.search).get('id')).toBeNull()
  })

  it('rejecting needs a comment', async () => {
    const user = userEvent.setup()
    const { api } = setup(['FINANCE_ADMIN'], {
      'POST /approvals/:id/reject': (request) => ({
        status: 200,
        data: { ...toDecide, status: 'REJECTED', canDecide: false, comment: (request.body as { comment: string }).comment },
      }),
    })
    renderApp('/admin/ai/approvals')
    const table = await screen.findByRole('table', { name: 'Approval requests to decide' })
    await user.click(within(table).getByRole('button', { name: /^Open request/ }))
    const drawer = await screen.findByRole('dialog', { name: 'Refund ₹2,500.00 for GA-7K3F9Q2M' })

    await user.click(within(drawer).getByRole('button', { name: 'Reject' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject this request?' })
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    expect(await within(dialog).findByText('Enter a comment: why is it not approved?')).toBeInTheDocument()
    expect(api.callsTo('POST /approvals/:id/reject')).toHaveLength(0)

    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Not enough evidence')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    expect(await within(drawer).findByText('“Not enough evidence”')).toBeInTheDocument()
    expect(api.callsTo('POST /approvals/:id/reject')[0]?.body).toEqual({ comment: 'Not enough evidence' })
  })

  it('FOUR_EYES_REQUIRED and APPROVAL_NOT_PENDING are explained in the drawer', async () => {
    const user = userEvent.setup()
    let approveCalls = 0
    setup(['SUPER_ADMIN'], {
      'POST /approvals/:id/approve': () => {
        approveCalls++
        return approveCalls === 1
          ? problem(403, 'FOUR_EYES_REQUIRED', 'Another admin must approve')
          : { status: 409, data: { ...problem(409, 'APPROVAL_NOT_PENDING').data, currentStatus: 'REJECTED' } }
      },
    })
    renderApp('/admin/ai/approvals?id=a2')
    const drawer = await screen.findByRole('dialog', { name: 'Refund ₹2,500.00 for GA-7K3F9Q2M' })

    await user.click(within(drawer).getByRole('button', { name: 'Approve' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Approve this request?' })).getByRole('button', { name: 'Approve and run' }))
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('another admin has to decide it (four-eyes rule)')

    await user.click(within(drawer).getByRole('button', { name: 'Approve' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Approve this request?' })).getByRole('button', { name: 'Approve and run' }))
    await waitFor(() =>
      expect(within(drawer).getByRole('alert')).toHaveTextContent('This request was already decided (now: Not approved), so nothing was done this time.'),
    )
  })

  it('an unknown ?id= says the request was not found', async () => {
    setup(['SUPPORT'])
    renderApp('/admin/ai/approvals?id=nope')
    const drawer = await screen.findByRole('dialog', { name: 'Approval request' })
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('Request not found')
  })
})
