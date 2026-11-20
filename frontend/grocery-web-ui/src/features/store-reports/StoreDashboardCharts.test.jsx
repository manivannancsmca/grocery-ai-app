import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { mockApi } from '../../test/mockApi'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import { lastDays } from './range'
import { StoreDashboardCharts } from './StoreDashboardCharts'

function report(overrides = {}) {
  const range = lastDays(7)
  const days = Array.from({ length: 7 }, (_, index) => {
    const date = new Date(`${range.from}T00:00:00Z`)
    date.setUTCDate(date.getUTCDate() + index)
    return {
      date: date.toISOString().slice(0, 10),
      ordersReceived: index === 6 ? 3 : 0,
      ordersAccepted: index === 6 ? 2 : 0,
      grossSales: index === 6 ? 340 : 0,
    }
  })
  return {
    from: range.from,
    to: range.to,
    timezone: 'Asia/Kolkata',
    currency: 'INR',
    ordersReceived: 10,
    ordersAccepted: 7,
    ordersRejected: 2,
    autoRejected: 1,
    ordersCancelled: 1,
    acceptanceRate: 0.7,
    grossSales: 1450,
    itemsSold: 23,
    averageOrderValue: 207.14,
    daily: days,
    topItems: [
      { itemId: 'p1', name: 'Milk', quantity: 9, sales: 297 },
      { itemId: 'p2', name: 'Banana', quantity: 4, sales: 240 },
    ],
    ...overrides,
  }
}

describe('Store dashboard charts', () => {
  it('shows a grocery store its last 7 days: orders and sales by day, how orders ended, best sellers and stock health', async () => {
    signInAs(['VENDOR'])
    const api = mockApi({
      'GET /vendor/reports/sales': { status: 200, data: report() },
      'GET /vendor/inventory/summary': { status: 200, data: { products: 12, inStock: 11, lowStock: 2, outOfStock: 1 } },
    })
    renderApp('/vendor', [{ path: '/vendor', element: <StoreDashboardCharts kind="VENDOR" /> }])

    const outcomes = await screen.findByRole('region', { name: 'How orders ended' })
    // Parts that add up to the 10 orders received: 7 accepted, 1 refused, 1 not answered in time, 1 other.
    expect(outcomes).toHaveTextContent('Accepted')
    expect(outcomes).toHaveTextContent('Refused by you')
    expect(outcomes).toHaveTextContent('Not answered in time')
    expect(outcomes).toHaveTextContent('Waiting or cancelled')
    expect(api.callsTo('GET /vendor/reports/sales')[0]?.params).toEqual(lastDays(7))

    expect(screen.getByRole('region', { name: 'Orders by day' })).toHaveTextContent('10 orders received')
    expect(screen.getByRole('region', { name: 'Sales by day' })).toHaveTextContent('₹1,450.00')
    const top = screen.getByRole('region', { name: 'Top products' })
    expect(within(top).getByText('Milk')).toBeInTheDocument()
    expect(top).toHaveTextContent('9 sold')
    const stock = await screen.findByRole('region', { name: 'Stock health' })
    expect(stock).toHaveTextContent('12 products')
    expect(stock).toHaveTextContent('Running low')
    expect(screen.getByRole('region', { name: 'Week in numbers' })).toHaveTextContent('70%')
    expect(screen.getByRole('link', { name: 'Full sales reports →' })).toHaveAttribute('href', '/vendor/reports')
  })

  it('shows a restaurant its dishes and menu availability, and an empty week plainly', async () => {
    signInAs(['RESTAURANT_OWNER'])
    mockApi({
      'GET /restaurant/reports/sales': {
        status: 200,
        data: report({
          ordersReceived: 0,
          ordersAccepted: 0,
          ordersRejected: 0,
          autoRejected: 0,
          ordersCancelled: 0,
          grossSales: 0,
          topItems: [],
        }),
      },
      'GET /restaurant/menu': {
        status: 200,
        data: {
          restaurantId: 'r1',
          restaurantStatus: 'APPROVED',
          acceptingOrders: true,
          itemCount: 3,
          categories: [
            {
              id: 'c1',
              name: 'Tiffin',
              description: null,
              sortOrder: 0,
              items: ['Dosa', 'Idli', 'Vada'].map((name, index) => ({
                id: `i${index}`,
                categoryId: 'c1',
                name,
                description: null,
                price: 80,
                foodType: 'VEG',
                imageUrl: null,
                available: index !== 2,
                sortOrder: index,
                updatedAt: '2026-11-15T10:00:00Z',
              })),
            },
          ],
        },
      },
    })
    renderApp('/restaurant', [{ path: '/restaurant', element: <StoreDashboardCharts kind="RESTAURANT" /> }])

    expect(await screen.findByRole('region', { name: 'Top dishes' })).toHaveTextContent(
      'Your best sellers appear here after your first accepted order.',
    )
    expect(screen.getByRole('region', { name: 'How orders ended' })).toHaveTextContent('None yet')
    const menu = await screen.findByRole('region', { name: 'Menu availability' })
    expect(menu).toHaveTextContent('3 dishes')
    expect(menu).toHaveTextContent('Sold out')
    expect(screen.getByRole('link', { name: 'Full sales reports →' })).toHaveAttribute('href', '/restaurant/reports')
  })
})
