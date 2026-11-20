import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { pageOf, paymentView, refundView } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

/** ₹125 captured, ₹25 already refunded → ₹100 refundable. */
const captured = paymentView({ refundedAmount: 25, refundStatus: 'PARTIAL', refunds: [refundView()] })
const expired = paymentView({
  id: 'pay-2',
  orderId: 'o2',
  orderNumber: 'GA-EXPD0002',
  status: 'EXPIRED',
  method: null,
  razorpayPaymentId: null,
  capturedAt: null,
  attempts: [],
})

function keyOf(call) {
  const value = call?.headers.get('Idempotency-Key')
  return typeof value === 'string' ? value : undefined
}

function setup(roles, refunds = { status: 201, data: refundView({ id: 'rf2', amount: 50, note: 'Late delivery' }) }) {
  signInAs(roles)
  return mockApi({
    'GET /admin/payments': pageOf([
      { ...captured, attempts: undefined },
      { ...expired, attempts: undefined },
    ]),
    'GET /admin/payments/:id': (request) => ({
      status: 200,
      data: request.pathParams.id === 'pay-2' ? expired : captured,
    }),
    'POST /admin/refunds': refunds,
  })
}

async function openRefundDialog(user) {
  const table = await screen.findByRole('table', { name: 'Payments' })
  await user.click(within(table).getByRole('button', { name: 'View payment for order GA-7K3F9Q2M' }))
  const drawer = await screen.findByRole('dialog', { name: 'Payment for order GA-7K3F9Q2M' })
  await user.click(within(drawer).getByRole('button', { name: 'Issue refund' }))
  return screen.findByRole('dialog', { name: 'Refund order GA-7K3F9Q2M' })
}

describe('Admin payments', () => {
  it('lists payments and puts the filters in the URL and the request', async () => {
    const user = userEvent.setup()
    const api = setup(['SUPPORT'])
    const { router } = renderApp('/admin/payments')

    const table = await screen.findByRole('table', { name: 'Payments' })
    const row = within(table).getByText('GA-7K3F9Q2M').closest('tr')
    expect(row).toHaveTextContent('₹125.00')
    expect(row).toHaveTextContent('Captured')
    expect(row).toHaveTextContent('Partly refunded')
    expect(row).toHaveTextContent('₹25.00 refunded')
    expect(row).toHaveTextContent('UPI')
    expect(within(table).getByText('GA-EXPD0002').closest('tr')).toHaveTextContent('Expired')

    await user.selectOptions(screen.getByLabelText('Status'), 'CAPTURED')
    await user.selectOptions(screen.getByLabelText('Refunds'), 'PARTIAL')
    await user.type(screen.getByLabelText('Order ID'), 'o1')
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-11-11' } })

    await waitFor(() =>
      expect(api.callsTo('GET /admin/payments').at(-1)?.params).toEqual({
        status: 'CAPTURED',
        refundStatus: 'PARTIAL',
        orderId: 'o1',
        from: '2026-11-10',
        to: '2026-11-11',
        page: 0,
        size: 20,
      }),
    )
    expect(new URLSearchParams(router.state.location.search).get('refundStatus')).toBe('PARTIAL')
  })

  it('is read-only for admin roles without the refund permission', async () => {
    const user = userEvent.setup()
    setup(['OPS_ADMIN'])
    renderApp('/admin/payments')

    const table = await screen.findByRole('table', { name: 'Payments' })
    await user.click(within(table).getByRole('button', { name: 'View payment for order GA-7K3F9Q2M' }))
    const drawer = await screen.findByRole('dialog', { name: 'Payment for order GA-7K3F9Q2M' })

    expect(await within(drawer).findByRole('list', { name: 'Payment attempts' })).toHaveTextContent(
      'Payment declined by the bank',
    )
    expect(within(drawer).getByRole('list', { name: 'Refunds' })).toHaveTextContent('Damaged item')
    expect(within(drawer).getByText(/can view payments but not refund them/)).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Issue refund' })).not.toBeInTheDocument()
  })

  it('validates the refund, sends one Idempotency-Key per submission and reuses it when retrying after a 503', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const api = setup(['FINANCE_ADMIN'], () => {
      attempts++
      return attempts === 1
        ? problem(503, 'SERVICE_UNAVAILABLE', 'Payment service is busy')
        : { status: 201, data: refundView({ id: 'rf2', amount: 50 }) }
    })
    renderApp('/admin/payments')

    const dialog = await openRefundDialog(user)
    const refundable = within(dialog).getByText('Refundable').closest('div')
    expect(refundable).toHaveTextContent('₹100.00')
    expect(within(dialog).getByLabelText(/Amount/)).toHaveAccessibleDescription(
      /Leave empty to refund everything not yet refunded \(₹100\.00\)/,
    )

    // Note is required; the amount may not exceed what is left.
    await user.click(within(dialog).getByRole('button', { name: 'Refund' }))
    expect(await within(dialog).findByText('Enter a note. It is stored with the refund.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/Amount/), '150')
    await user.type(within(dialog).getByLabelText(/Note/), 'Late delivery')
    await user.click(within(dialog).getByRole('button', { name: 'Refund' }))
    expect(await within(dialog).findByText('At most ₹100.00 can be refunded.')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/refunds')).toHaveLength(0)

    await user.clear(within(dialog).getByLabelText(/Amount/))
    await user.type(within(dialog).getByLabelText(/Amount/), '50')
    await user.click(within(dialog).getByRole('button', { name: 'Refund' }))
    expect(await within(dialog).findByText(/Submitting the same values again is safe/)).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Refund' }))
    expect(await screen.findByText('Refund of ₹50.00 started for order GA-7K3F9Q2M.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Refund order GA-7K3F9Q2M' })).not.toBeInTheDocument()

    const calls = api.callsTo('POST /admin/refunds')
    expect(calls).toHaveLength(2)
    expect(calls[0]?.body).toEqual({ orderId: 'o1', amount: 50, note: 'Late delivery' })
    expect(keyOf(calls[0])).toMatch(/^[A-Za-z0-9._:-]{8,100}$/)
    expect(keyOf(calls[1])).toBe(keyOf(calls[0]))
  })

  it('maps 422 REFUND_EXCEEDS_CAPTURED to the refundable amount and starts a new attempt with a new key', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const api = setup(['SUPER_ADMIN'], () => {
      attempts++
      if (attempts === 1) {
        const reply = problem(422, 'REFUND_EXCEEDS_CAPTURED')
        return { ...reply, data: { ...reply.data, refundable: 40 } }
      }
      return { status: 201, data: refundView({ id: 'rf3', amount: 40, note: 'Full remaining' }) }
    })
    renderApp('/admin/payments')

    const dialog = await openRefundDialog(user)
    await user.type(within(dialog).getByLabelText(/Amount/), '60')
    await user.type(within(dialog).getByLabelText(/Note/), 'Full remaining')
    await user.click(within(dialog).getByRole('button', { name: 'Refund' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Refundable now: ₹40.00')
    expect(within(dialog).getByText('At most ₹40.00 can be refunded.')).toBeInTheDocument()
    expect(within(dialog).getByText('Refundable').closest('div')).toHaveTextContent('₹40.00')

    // Empty amount = everything that is left.
    await user.clear(within(dialog).getByLabelText(/Amount/))
    await user.click(within(dialog).getByRole('button', { name: 'Refund' }))
    expect(await screen.findByText('Refund of ₹40.00 started for order GA-7K3F9Q2M.')).toBeInTheDocument()

    const calls = api.callsTo('POST /admin/refunds')
    expect(calls[1]?.body).toEqual({ orderId: 'o1', note: 'Full remaining' })
    expect(keyOf(calls[1])).not.toBe(keyOf(calls[0]))
  })

  it('explains 409 PAYMENT_NOT_CAPTURED', async () => {
    const user = userEvent.setup()
    setup(['FINANCE_ADMIN'], problem(409, 'PAYMENT_NOT_CAPTURED'))
    renderApp('/admin/payments')

    const dialog = await openRefundDialog(user)
    await user.type(within(dialog).getByLabelText(/Note/), 'Goodwill')
    await user.click(within(dialog).getByRole('button', { name: 'Refund' }))

    expect(
      await within(dialog).findByText('This payment has not been captured, so there is nothing to refund.'),
    ).toBeInTheDocument()
  })

  it('offers no refund for a payment that was never captured', async () => {
    const user = userEvent.setup()
    setup(['FINANCE_ADMIN'])
    renderApp('/admin/payments')

    const table = await screen.findByRole('table', { name: 'Payments' })
    await user.click(within(table).getByRole('button', { name: 'View payment for order GA-EXPD0002' }))
    const drawer = await screen.findByRole('dialog', { name: 'Payment for order GA-EXPD0002' })
    expect(await within(drawer).findByText('No payment attempts yet.')).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Issue refund' })).not.toBeInTheDocument()
  })
})
