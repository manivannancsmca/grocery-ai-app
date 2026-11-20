import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { emailEntry, purchaseOrder } from '../../../test/storeAdminFixtures'

describe('Admin email log', { timeout: 20_000 }, () => {
  it('lists sent, pending and failed emails with attempts and the last error; filters by status and recipient', async () => {
    const user = userEvent.setup()
    signInAs(['SUPPORT'], { id: 'admin-1' })
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /admin/notifications/emails': pageOf([
        emailEntry(),
        emailEntry({
          id: 'e2',
          to: 'ravi@example.com',
          subject: 'Refund failed',
          type: 'REFUND_FAILED',
          status: 'FAILED',
          attempts: 4,
          lastError: 'Connection refused: mailpit:1025',
          sentAt: null,
        }),
        emailEntry({
          id: 'e3',
          subject: 'Approval requested',
          type: 'AI_APPROVAL_REQUESTED',
          status: 'PENDING',
          attempts: 1,
          sentAt: null,
        }),
      ]),
    })
    const { router } = renderApp('/admin/emails')

    const table = await screen.findByRole('table', { name: 'Email log' })
    expect(api.callsTo('GET /admin/notifications/emails')[0]?.params).toEqual({ page: 0, size: 20 })
    const [, sent, failed, pending] = within(table).getAllByRole('row')
    expect(sent).toHaveTextContent('asha@example.com')
    expect(sent).toHaveTextContent('Order GA-7K3F9Q2M confirmed')
    expect(sent).toHaveTextContent('Order Confirmed')
    expect(sent).toHaveTextContent('Sent')
    expect(failed).toHaveTextContent('Failed')
    expect(failed).toHaveTextContent('Connection refused: mailpit:1025')
    expect(failed).toHaveTextContent('4')
    expect(pending).toHaveTextContent('Pending')

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'Failed')
    await user.type(screen.getByRole('searchbox', { name: 'Recipient' }), 'ravi@')
    await waitFor(() =>
      expect(api.callsTo('GET /admin/notifications/emails').at(-1)?.params).toEqual({
        status: 'FAILED',
        to: 'ravi@',
        page: 0,
        size: 20,
      }),
    )
    expect(new URLSearchParams(router.state.location.search).get('status')).toBe('FAILED')
  })
})

describe('Admin purchase orders (read-only)', { timeout: 20_000 }, () => {
  it('lists every vendor’s purchase orders and shows one with its lines and receipts', async () => {
    const user = userEvent.setup()
    signInAs(['FINANCE_ADMIN'], { id: 'admin-1' })
    const received = purchaseOrder({
      vendorName: 'Fresh Mart',
      status: 'PARTIALLY_RECEIVED',
      lines: [
        { productId: 'p1', productName: 'Tomato 500 g', orderedQuantity: 10, unitCost: 18.5, receivedQuantity: 10 },
        { productId: 'p2', productName: 'Onion 1 kg', orderedQuantity: 6, unitCost: 30, receivedQuantity: 0 },
      ],
      receipts: [
        {
          id: 'rc1',
          receivedAt: '2026-11-13T08:00:00Z',
          note: 'Morning delivery',
          lines: [{ productId: 'p1', quantity: 10 }],
        },
      ],
    })
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /admin/purchase-orders': pageOf([received]),
    })
    renderApp('/admin/purchase-orders')

    const table = await screen.findByRole('table', { name: 'Purchase orders' })
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Fresh Mart')
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Partially received')
    await user.type(screen.getByRole('searchbox', { name: 'Vendor ID' }), 'v1')
    await waitFor(() =>
      expect(api.callsTo('GET /admin/purchase-orders').at(-1)?.params).toEqual({ vendorId: 'v1', page: 0, size: 20 }),
    )

    await user.click(within(table).getByRole('button', { name: 'View PO-7K3M9QX2' }))
    const drawer = await screen.findByRole('dialog', { name: 'Purchase order PO-7K3M9QX2' })
    expect(within(drawer).getByRole('table', { name: 'Purchase order lines' })).toHaveTextContent('Onion 1 kg')
    expect(within(drawer).getByRole('list', { name: 'Goods receipts' })).toHaveTextContent('10 × Tomato 500 g')
    expect(within(drawer).getByRole('list', { name: 'Goods receipts' })).toHaveTextContent('Morning delivery')
    // Read-only: nothing to act on.
    expect(within(drawer).queryByRole('button', { name: /Receive|Send|Cancel|Close short/ })).not.toBeInTheDocument()
  })
})
