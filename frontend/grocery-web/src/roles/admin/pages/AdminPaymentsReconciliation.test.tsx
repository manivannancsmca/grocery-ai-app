import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { PaymentReconciliation } from '../../../features/admin-payments/api'
import { type MockHandler, mockApi, problem } from '../../../test/mockApi'
import { pageOf, paymentView } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const RUN: PaymentReconciliation = {
  lastRunAt: new Date(Date.now() - 3 * 60_000).toISOString(),
  checked: 12,
  recovered: 2,
  failures: 1,
  lastRecovered: [
    { orderId: 'o1', orderNumber: 'GA-7K3F9Q2M', outcome: 'CAPTURED', at: '2026-11-12T10:02:00Z' },
    { orderId: 'o2', orderNumber: 'GA-CONF0002', outcome: 'AUTHORIZED', at: '2026-11-12T09:40:00Z' },
  ],
}

function setup(reconciliation: MockHandler) {
  signInAs(['FINANCE_ADMIN'])
  return mockApi({
    'GET /admin/payments/reconciliation': reconciliation,
    'GET /admin/payments': pageOf([{ ...paymentView(), attempts: undefined }]),
  })
}

describe('Admin payments — reconciliation summary', () => {
  it('shows the last run, the counts and the recovered payments linking to their orders', async () => {
    const user = userEvent.setup()
    setup({ status: 200, data: RUN })
    renderApp('/admin/payments')

    const summary = await screen.findByRole('region', { name: 'Payment reconciliation' })
    const figures = await within(summary).findByLabelText('Last reconciliation run')
    expect(within(figures).getByText('Checked').nextElementSibling).toHaveTextContent('12')
    expect(within(figures).getByText('Recovered').nextElementSibling).toHaveTextContent('2')
    expect(within(figures).getByText('Failures').nextElementSibling).toHaveTextContent('1')
    expect(summary).toHaveTextContent('Last run 3 min ago')

    await user.click(within(summary).getByText('Last recovered payments (2)'))
    const recovered = within(summary).getByRole('list', { name: 'Recovered payments' })
    const [first, second] = within(recovered).getAllByRole('listitem')
    expect(first).toHaveTextContent('Captured')
    expect(within(first as HTMLElement).getByRole('link', { name: 'GA-7K3F9Q2M' })).toHaveAttribute('href', '/admin/orders?q=GA-7K3F9Q2M&order=o1')
    expect(second).toHaveTextContent('Authorized, then captured')
    // The payments list is unaffected.
    expect(await screen.findByRole('table', { name: 'Payments' })).toBeInTheDocument()
  })

  it('says when the job has not run yet', async () => {
    setup({ status: 200, data: { ...RUN, lastRunAt: null, checked: 0, recovered: 0, failures: 0, lastRecovered: [] } })
    renderApp('/admin/payments')

    const summary = await screen.findByRole('region', { name: 'Payment reconciliation' })
    expect(await within(summary).findByText(/Not run yet/)).toBeInTheDocument()
    expect(within(summary).queryByText(/Last recovered payments/)).not.toBeInTheDocument()
  })

  it('stays out of the way when the status cannot be loaded', async () => {
    const user = userEvent.setup()
    let calls = 0
    setup(() => {
      calls++
      return calls === 1 ? problem(503, 'SERVICE_UNAVAILABLE', 'Payment service is down') : { status: 200, data: RUN }
    })
    renderApp('/admin/payments')

    const summary = await screen.findByRole('region', { name: 'Payment reconciliation' })
    expect(await within(summary).findByText(/Reconciliation status is unavailable: Payment service is down/)).toBeInTheDocument()
    expect(within(summary).queryByRole('alert')).not.toBeInTheDocument()
    await user.click(within(summary).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(summary).toHaveTextContent('Last run 3 min ago'))
  })
})
