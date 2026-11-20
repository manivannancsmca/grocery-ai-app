import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { MenuView } from '../../../features/menu/api'
import type { RestaurantView } from '../../../features/restaurant/api'
import { mockApi } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const restaurant: RestaurantView = {
  id: 'r1',
  ownerUserId: 'owner-1',
  status: 'PENDING_APPROVAL',
  name: 'Spice Route',
  description: null,
  cuisines: ['BIRYANI'],
  fssaiLicense: '12345678901234',
  gstin: null,
  contactEmail: 'hello@spiceroute.in',
  contactPhone: '+919876543210',
  address: null,
  openingHours: [],
  acceptingOrders: true,
  statusReason: null,
  submittedAt: '2026-11-10T09:00:00Z',
  approvedAt: null,
  createdAt: '2026-11-09T09:00:00Z',
  updatedAt: '2026-11-10T09:00:00Z',
}

const menu: MenuView = {
  restaurantId: 'r1',
  restaurantStatus: 'PENDING_APPROVAL',
  acceptingOrders: true,
  itemCount: 1,
  categories: [
    {
      id: 'mc1',
      name: 'Biryani',
      description: null,
      sortOrder: 0,
      items: [
        {
          id: 'i1',
          categoryId: 'mc1',
          name: 'Chicken Biryani',
          description: null,
          price: 249,
          foodType: 'NON_VEG',
          imageUrl: null,
          available: false,
          sortOrder: 0,
          updatedAt: '2026-11-10T09:00:00Z',
        },
      ],
    },
  ],
}

describe('Admin restaurant review', () => {
  it('shows the restaurant’s menu in the detail drawer', async () => {
    const user = userEvent.setup()
    signInAs(['OPS_ADMIN'])
    const api = mockApi({
      'GET /admin/restaurants': { status: 200, data: { content: [restaurant], page: 0, size: 20, totalElements: 1, totalPages: 1 } },
      'GET /admin/restaurants/:id': { status: 200, data: restaurant },
      'GET /admin/restaurants/:id/history': { status: 200, data: [] },
      'GET /admin/restaurants/:id/menu': { status: 200, data: menu },
    })
    renderApp('/admin/restaurants')

    await user.click(await screen.findByRole('button', { name: 'View Spice Route' }))
    const drawer = await screen.findByRole('dialog', { name: 'Spice Route' })
    expect(await within(drawer).findByText('Chicken Biryani')).toBeInTheDocument()
    expect(within(drawer).getByText('₹249')).toBeInTheDocument()
    expect(within(drawer).getByText('Sold out')).toBeInTheDocument()
    expect(api.callsTo('GET /admin/restaurants/:id/menu')[0]?.pathParams.id).toBe('r1')
  })
})
