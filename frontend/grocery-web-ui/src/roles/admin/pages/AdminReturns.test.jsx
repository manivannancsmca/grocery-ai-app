import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { storeReturn } from '../../../test/storeAdminFixtures'

const escalated = storeReturn({
  id: 'ret7',
  returnNumber: 'RT-ESCAL007',
  status: 'ESCALATED',
  decideBy: '2026-11-12T06:00:00Z',
})
const restaurantReturn = storeReturn({
  id: 'ret8',
  returnNumber: 'RT-REST0008',
  orderType: 'RESTAURANT',
  storeId: 'r1',
  storeName: 'Spice Route',
})

function setup(roles, returns) {
  signInAs(roles, { id: 'admin-1' })
  return mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /admin/returns': pageOf(returns),
    'POST /admin/returns/:id/approve': (request) => ({
      status: 200,
      data: { ...escalated, status: 'APPROVED', restock: request.body.restock },
    }),
    'POST /admin/returns/:id/reject': { status: 200, data: { ...restaurantReturn, status: 'REJECTED' } },
  })
}

describe('Admin returns', { timeout: 20_000 }, () => {
  it('lists the open returns (server default) with the store; support approves an escalated grocery return with restock', async () => {
    const user = userEvent.setup()
    const api = setup(['SUPPORT'], [escalated, restaurantReturn])
    const { router } = renderApp('/admin/returns')

    const card = await screen.findByRole('region', { name: 'Return RT-ESCAL007' })
    expect(api.callsTo('GET /admin/returns')[0]?.params).toEqual({ page: 0, size: 10 })
    expect(card).toHaveTextContent('Escalated to admins')
    expect(card).toHaveTextContent('Fresh Mart')
    expect(card).toHaveTextContent('The store did not decide in time — an admin decides now.')

    await user.click(within(card).getByRole('button', { name: 'Approve' }))
    const dialog = await screen.findByRole('dialog', { name: 'Approve return RT-ESCAL007?' })
    await user.click(within(dialog).getByRole('radio', { name: 'Yes — add them back to stock' }))
    await user.click(within(dialog).getByRole('button', { name: 'Approve and refund' }))
    await waitFor(() =>
      expect(api.callsTo('POST /admin/returns/:id/approve')[0]).toMatchObject({
        pathParams: { id: 'ret7' },
        body: { restock: true },
      }),
    )

    // A restaurant order is never restocked: no question.
    const restaurant = screen.getByRole('region', { name: 'Return RT-REST0008' })
    expect(restaurant).toHaveTextContent('Spice Route')
    await user.click(within(restaurant).getByRole('button', { name: 'Approve' }))
    const second = await screen.findByRole('dialog', { name: 'Approve return RT-REST0008?' })
    expect(within(second).queryByRole('radio')).not.toBeInTheDocument()
    await user.click(within(second).getByRole('button', { name: 'Back' }))

    await user.click(within(restaurant).getByRole('button', { name: 'Reject' }))
    const reject = await screen.findByRole('dialog', { name: 'Reject return RT-REST0008?' })
    await user.type(within(reject).getByRole('textbox', { name: 'Reason' }), 'Not reproducible')
    await user.click(within(reject).getByRole('button', { name: 'Reject return' }))
    await waitFor(() =>
      expect(api.callsTo('POST /admin/returns/:id/reject')[0]?.body).toEqual({ comment: 'Not reproducible' }),
    )

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'Refunded')
    await waitFor(() =>
      expect(api.callsTo('GET /admin/returns').at(-1)?.params).toEqual({ status: 'REFUNDED', page: 0, size: 10 }),
    )
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('REFUNDED')
  })

  it('a finance admin sees returns without decision buttons', async () => {
    setup(['FINANCE_ADMIN'], [escalated])
    renderApp('/admin/returns')
    const card = await screen.findByRole('region', { name: 'Return RT-ESCAL007' })
    expect(within(card).queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByText(/Your role can view returns/)).toBeInTheDocument()
  })
})
