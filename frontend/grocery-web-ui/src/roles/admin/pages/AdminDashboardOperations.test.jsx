import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DEAD_LETTER_SERVICES } from '../../../features/operations/api'
import { mockApi, problem } from '../../../test/mockApi'
import { connectorsResponse, deadLetter, healthyConnectors, stuckOrder } from '../../../test/operationsFixtures'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

/** PENDING dead letters per service (the card asks for page size 1 and reads `totalElements`). */
const PENDING = { notification: 2, inventory: 1 }

describe('Admin dashboard — stuck orders and pipeline', () => {
  it('counts stuck orders, names the longest overdue, and flags failed connectors and pending dead letters', async () => {
    signInAs(['OPS_ADMIN'])
    const api = mockApi({
      'GET /admin/orders/stuck': {
        status: 200,
        data: [
          stuckOrder(),
          stuckOrder({
            orderId: 'o7',
            orderNumber: 'GA-PAY00007',
            reason: 'PAYMENT_EXPIRY_OVERDUE',
            minutesOverdue: 4,
          }),
        ],
      },
      'GET /admin/system/connectors': { status: 200, data: connectorsResponse() },
      'GET /admin/ops/:service/dead-letters': (request) => {
        const service = request.pathParams.service ?? ''
        if (service === 'payment') return problem(503, 'SERVICE_UNAVAILABLE', 'payment-service is down')
        const count = PENDING[service] ?? 0
        return pageOf(count > 0 ? [deadLetter()] : [], { size: 1, totalElements: count, totalPages: count })
      },
    })
    renderApp('/admin')

    const stuck = await screen.findByRole('region', { name: 'Stuck orders' })
    await waitFor(() =>
      expect(stuck).toHaveTextContent('Longest: GA-7K3F9Q2M · Store response overdue · 1 h 25 min overdue'),
    )
    expect(within(stuck).getByText('2')).toBeInTheDocument()
    expect(within(stuck).getByRole('link', { name: 'View stuck orders →' })).toHaveAttribute(
      'href',
      '/admin/operations?tab=stuck-orders',
    )

    const pipeline = screen.getByRole('region', { name: 'Pipeline' })
    expect(await within(pipeline).findByText('1 of 2 need attention')).toBeInTheDocument()
    await waitFor(() =>
      expect(within(pipeline).getByText('Dead letters pending').nextElementSibling).toHaveTextContent('3'),
    )
    expect(await within(pipeline).findByText('Not counted (unavailable): Payment')).toBeInTheDocument()
    expect(within(pipeline).getByRole('link', { name: 'Open operations →' })).toHaveAttribute(
      'href',
      '/admin/operations?tab=connectors',
    )

    const counts = api.callsTo('GET /admin/ops/:service/dead-letters')
    expect(counts).toHaveLength(DEAD_LETTER_SERVICES.length)
    expect(counts.every((call) => call.params.status === 'PENDING' && call.params.size === 1)).toBe(true)
  })

  it('reads healthy when nothing is stuck, every connector runs and no dead letter waits', async () => {
    signInAs(['FINANCE_ADMIN'])
    mockApi({
      'GET /admin/orders/stuck': { status: 200, data: [] },
      'GET /admin/system/connectors': { status: 200, data: healthyConnectors() },
      'GET /admin/ops/:service/dead-letters': pageOf([], { size: 1 }),
    })
    renderApp('/admin')

    const stuck = await screen.findByRole('region', { name: 'Stuck orders' })
    expect(await within(stuck).findByText('Every open order is moving on time.')).toBeInTheDocument()
    expect(within(stuck).getByText('0')).toBeInTheDocument()

    const pipeline = screen.getByRole('region', { name: 'Pipeline' })
    expect(await within(pipeline).findByText('All 2 running')).toBeInTheDocument()
    await waitFor(() =>
      expect(within(pipeline).getByText('Dead letters pending').nextElementSibling).toHaveTextContent('0'),
    )
    expect(within(pipeline).queryByText(/Not counted/)).not.toBeInTheDocument()
    expect(within(pipeline).getByRole('link', { name: 'Open operations →' })).toHaveAttribute(
      'href',
      '/admin/operations',
    )
  })
})
