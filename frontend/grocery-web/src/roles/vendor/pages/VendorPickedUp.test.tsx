import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { conflictStatus } from '../../../features/store-orders/errors'
import { closedAt } from '../../../features/store-orders/hooks'
import { storeOrderStatusLabel } from '../../../features/store-orders/labels'
import { ApiError } from '../../../shared/api/problemDetail'
import { mockApi } from '../../../test/mockApi'
import { mockEventStream } from '../../../test/mockEventStream'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { acceptedStoreOrder, storeOrder } from '../../../test/storeOrderFixtures'

const iso = (offsetMs: number) => new Date(Date.now() + offsetMs).toISOString()

/** Phase 9: the partner picked the order up — the store order is PICKED_UP (terminal success). */
function pickedUpOrder() {
  const ready = acceptedStoreOrder({ orderId: 'so4', orderNumber: 'GA-PIK00004', status: 'READY_FOR_PICKUP', readyAt: iso(-10 * 60_000) })
  return { ...ready, status: 'PICKED_UP' as const, timeline: [...ready.timeline, { status: 'PICKED_UP' as const, at: iso(-60_000) }] }
}

describe('Store board — picked up (Phase 9)', { timeout: 20_000 }, () => {
  it('names the new status and accepts it as a 409 current status', () => {
    expect(storeOrderStatusLabel('VENDOR', 'PICKED_UP')).toBe('Picked up')
    expect(storeOrderStatusLabel('RESTAURANT', 'PICKED_UP')).toBe('Picked up')
    const conflict = new ApiError('Conflict', {
      kind: 'http',
      status: 409,
      problem: { status: 409, errorCode: 'INVALID_STORE_ORDER_TRANSITION', currentStatus: 'PICKED_UP' },
      correlationId: null,
    })
    expect(conflictStatus(conflict)).toBe('PICKED_UP')
    const order = pickedUpOrder()
    expect(closedAt(order)).toBe(order.timeline.at(-1)?.at)
    expect(closedAt({ ...order, pickedUpAt: '2026-11-11T10:30:00Z' })).toBe('2026-11-11T10:30:00Z')
  })

  it('keeps a picked-up order off the columns and lists it under "Closed today"; STORE_ORDERS_CHANGED moves it there live', async () => {
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const sse = mockEventStream()
    const ready = acceptedStoreOrder({ orderId: 'so4', orderNumber: 'GA-PIK00004', status: 'READY_FOR_PICKUP', readyAt: iso(-10 * 60_000) })
    const server = { active: [storeOrder({ storeId: 'v1' }), ready], pickedUp: [] as ReturnType<typeof pickedUpOrder>[] }
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /vendor/orders': (request) => {
        switch (request.params.status) {
          case 'ACTIVE':
            return pageOf(server.active, { size: 100 })
          case 'PICKED_UP':
            return pageOf(server.pickedUp, { size: 50 })
          default:
            return pageOf([], { size: 50 })
        }
      },
    })
    renderApp('/vendor/orders')

    const readyColumn = await screen.findByRole('region', { name: 'Ready' })
    expect(await within(readyColumn).findByRole('article', { name: 'Order GA-PIK00004' })).toBeInTheDocument()
    const closed = screen.getByRole('region', { name: 'Closed today' })
    expect(await within(closed).findByText('Nothing picked up, rejected or cancelled today.')).toBeInTheDocument()
    expect(api.callsTo('GET /vendor/orders').find((call) => call.params.status === 'PICKED_UP')?.params).toEqual({ status: 'PICKED_UP', page: 0, size: 50 })

    // The partner picks it up: the store service moves it, notification-service tells the board.
    const stream = await sse.connection()
    server.active = [server.active[0] as (typeof server.active)[number]]
    server.pickedUp = [pickedUpOrder()]
    stream.signal({ type: 'STORE_ORDERS_CHANGED', data: { orderId: 'so4', status: 'PICKED_UP' } })

    expect(await within(closed).findByText('GA-PIK00004')).toBeInTheDocument()
    expect(within(closed).getByText('GA-PIK00004').closest('li')).toHaveTextContent(/Picked up.*by the delivery partner/)
    await waitFor(() => expect(within(readyColumn).queryByRole('article')).not.toBeInTheDocument())
    expect(within(screen.getByRole('region', { name: 'New' })).getByRole('article', { name: 'Order GA-7K3F9Q2M' })).toBeInTheDocument()
  })

  it('never shows a PICKED_UP order in a column, even from a stale active list', async () => {
    signInAs(['VENDOR'], { vendorId: 'v1' })
    mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /vendor/orders': (request) => pageOf(request.params.status === 'ACTIVE' ? [pickedUpOrder()] : [], { size: 100 }),
    })
    renderApp('/vendor/orders')

    expect(await screen.findByText('Nothing waiting for pickup.')).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Order GA-PIK00004' })).not.toBeInTheDocument()
  })
})
