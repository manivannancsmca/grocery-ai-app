import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { storeReturn } from '../../../test/storeAdminFixtures'

function setup(returns, routes = {}) {
  const server = { returns }
  const decide = (status, extra) => (request) => {
    const current = server.returns.find((item) => item.id === request.pathParams.id)
    const updated = {
      ...current,
      status,
      decidedAt: '2026-11-13T10:00:00Z',
      decidedByRole: 'VENDOR',
      ...extra(request.body),
    }
    server.returns = server.returns.map((item) => (item.id === updated.id ? updated : item))
    return { status: 200, data: updated }
  }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /store/returns': (request) =>
      pageOf(server.returns.filter((item) => !request.params.status || item.status === request.params.status)),
    'POST /store/returns/:id/approve': decide('APPROVED', (body) => ({ restock: Boolean(body.restock) })),
    'POST /store/returns/:id/reject': decide('REJECTED', (body) => ({ comment: String(body.comment) })),
    ...routes,
  })
  return { api, server }
}

describe('Store returns', { timeout: 20_000 }, () => {
  it('vendor: lists the undecided returns; approving asks whether the goods can be restocked', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const { api } = setup([storeReturn()])
    renderApp('/vendor/returns')

    const card = await screen.findByRole('region', { name: 'Return RT-7K3M9QX2' })
    expect(api.callsTo('GET /store/returns')[0]?.params).toEqual({ status: 'REQUESTED', page: 0, size: 10 })
    expect(card).toHaveTextContent('Order GA-7K3F9Q2M')
    expect(card).toHaveTextContent('Damaged — “Two were squashed.”')
    expect(card).toHaveTextContent('Tomato 500 g × 2')
    expect(card).toHaveTextContent('₹95.00')
    expect(card).toHaveTextContent(/Decide by .*, or it escalates to admins\./)

    await user.click(within(card).getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve return RT-7K3M9QX2?' })
    expect(dialog).toHaveTextContent('The customer is refunded ₹95.00 for 2 item(s).')
    await user.click(within(dialog).getByRole('button', { name: 'Approve and refund' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Say whether the goods came back in a sellable state.',
    )
    expect(api.callsTo('POST /store/returns/:id/approve')).toHaveLength(0)

    await user.click(within(dialog).getByRole('radio', { name: 'Yes — add them back to stock' }))
    await user.click(within(dialog).getByRole('button', { name: 'Approve and refund' }))
    await waitFor(() => expect(api.callsTo('POST /store/returns/:id/approve')[0]?.body).toEqual({ restock: true }))
    expect(await screen.findByText('Nothing to decide')).toBeInTheDocument()
  })

  it('vendor: rejects with a comment for the customer; a 409 RETURN_NOT_PENDING explains and refreshes', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const second = storeReturn({ id: 'ret2', returnNumber: 'RT-SECOND02', orderNumber: 'GA-SECOND02' })
    const { api } = setup([storeReturn(), second], {
      'POST /store/returns/:id/approve': {
        ...problem(409, 'RETURN_NOT_PENDING'),
        data: { ...problem(409, 'RETURN_NOT_PENDING').data, currentStatus: 'ESCALATED' },
      },
    })
    renderApp('/vendor/returns')

    const card = await screen.findByRole('region', { name: 'Return RT-7K3M9QX2' })
    await user.click(within(card).getByRole('button', { name: 'Reject' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reject return RT-7K3M9QX2?' })
    await user.click(within(dialog).getByRole('button', { name: 'Reject return' }))
    expect(
      await within(dialog).findByText('Enter a comment for the customer: why is the return rejected?'),
    ).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: 'Reason' }), 'The photos show the tomatoes intact.')
    await user.click(within(dialog).getByRole('button', { name: 'Reject return' }))
    await waitFor(() =>
      expect(api.callsTo('POST /store/returns/:id/reject')[0]?.body).toEqual({
        comment: 'The photos show the tomatoes intact.',
      }),
    )
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Return RT-7K3M9QX2' })).not.toBeInTheDocument())

    const other = screen.getByRole('region', { name: 'Return RT-SECOND02' })
    const reads = api.callsTo('GET /store/returns').length
    await user.click(within(other).getByRole('button', { name: 'Approve' }))
    const approve = await screen.findByRole('dialog', { name: 'Approve return RT-SECOND02?' })
    await user.click(within(approve).getByRole('radio', { name: /No — do not restock/ }))
    await user.click(within(approve).getByRole('button', { name: 'Approve and refund' }))
    expect(await within(other).findByRole('alert')).toHaveTextContent(
      'Return RT-SECOND02 was already decided (now: escalated to admins), so nothing was changed.',
    )
    expect(api.callsTo('POST /store/returns/:id/approve')[0]?.body).toEqual({ restock: false })
    await waitFor(() => expect(api.callsTo('GET /store/returns').length).toBeGreaterThan(reads))
  })

  it('restaurant: no restock question (always false); "All returns" shows decided ones with their outcome', async () => {
    const user = userEvent.setup()
    signInAs(['RESTAURANT_OWNER'], { restaurantId: 'r1' })
    const problemReport = storeReturn({
      id: 'ret3',
      returnNumber: 'RT-DOSA0003',
      orderType: 'RESTAURANT',
      storeId: 'r1',
      reason: 'QUALITY',
      note: 'Cold dosa',
      items: [{ itemId: 'm1', name: 'Masala Dosa', quantity: 1, refundAmount: 120 }],
      refundAmount: 120,
    })
    const refunded = storeReturn({
      id: 'ret4',
      returnNumber: 'RT-DONE0004',
      orderType: 'RESTAURANT',
      status: 'REFUNDED',
      restock: false,
      decidedAt: '2026-11-12T10:00:00Z',
      decidedByRole: 'RESTAURANT_OWNER',
      refundedAt: '2026-11-12T10:05:00Z',
    })
    const { api } = setup([problemReport, refunded])
    const { router } = renderApp('/restaurant/returns')

    expect(await screen.findByRole('heading', { level: 1, name: 'Returns and problems' })).toBeInTheDocument()
    const card = await screen.findByRole('region', { name: 'Return RT-DOSA0003' })
    await user.click(within(card).getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve return RT-DOSA0003?' })
    expect(within(dialog).queryByRole('radio')).not.toBeInTheDocument()
    expect(dialog).not.toHaveTextContent('sellable')
    await user.click(within(dialog).getByRole('button', { name: 'Approve and refund' }))
    await waitFor(() => expect(api.callsTo('POST /store/returns/:id/approve')[0]?.body).toEqual({ restock: false }))

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'All returns')
    await waitFor(() => expect(api.callsTo('GET /store/returns').at(-1)?.params).toEqual({ page: 0, size: 10 }))
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('ALL')
    const done = await screen.findByRole('region', { name: 'Return RT-DONE0004' })
    expect(done).toHaveTextContent('Refunded')
    expect(done).toHaveTextContent('by the store')
    expect(within(done).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })
})
