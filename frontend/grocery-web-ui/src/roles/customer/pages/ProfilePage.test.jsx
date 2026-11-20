import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { refreshSession } from '../../../shared/auth/session'
import { mockApi } from '../../../test/mockApi'
import { orderSummary, pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { fakeAuthResponse, signInAs } from '../../../test/session'

function address(id, label, isDefault, line1) {
  return {
    id,
    label,
    recipientName: 'Kavya Sriram',
    phone: '+919840066001',
    line1,
    line2: 'Velachery',
    landmark: null,
    city: 'Chennai',
    state: 'Tamil Nadu',
    postalCode: '600042',
    country: 'IN',
    latitude: 12.97,
    longitude: 80.21,
    isDefault,
    createdAt: '2026-11-16T10:00:00Z',
    updatedAt: '2026-11-16T10:00:00Z',
  }
}

describe('My profile', () => {
  it('shows the details, every saved address (default first), the latest order and the account shortcuts', async () => {
    signInAs(['CUSTOMER'])
    mockApi({
      'GET /customers/me': {
        status: 200,
        data: {
          id: 'c1',
          email: 'kavya@example.com',
          fullName: 'Kavya Sriram',
          phone: '+919840066001',
          createdAt: '2026-11-16T10:00:00Z',
          updatedAt: '2026-11-16T10:00:00Z',
        },
      },
      'GET /customers/me/addresses': {
        status: 200,
        data: [
          address('a2', 'Office', false, 'International Tech Park'),
          address('a1', 'Home', true, 'AGS Colony 4th Main Road'),
        ],
      },
      'GET /orders': pageOf([
        orderSummary({
          id: 'o9',
          status: 'DELIVERED',
          store: { id: 's1', type: 'GROCERY', name: 'Vijaya Nagar Fresh Mart' },
          total: 566,
        }),
      ]),
      'GET /reviews/mine': pageOf([]),
    })
    renderApp('/account/profile')

    expect(await screen.findByRole('heading', { name: 'Kavya Sriram' })).toBeInTheDocument()

    const saved = await screen.findByRole('region', { name: 'Saved addresses' })
    const places = within(saved).getAllByRole('listitem')
    expect(places[0]).toHaveTextContent('HomeDefault')
    expect(places[1]).toHaveTextContent('Office')

    const latest = screen.getByRole('region', { name: 'Latest order' })
    const order = await within(latest).findByRole('link', { name: /Vijaya Nagar Fresh Mart/ })
    expect(order).toHaveAttribute('href', '/orders/o9')
    expect(order).toHaveTextContent('₹566')

    const shortcuts = screen.getByRole('navigation', { name: 'Your account' })
    expect(within(shortcuts).getByRole('link', { name: /Returns/ })).toHaveAttribute('href', '/account/returns')
    expect(within(shortcuts).getByRole('button', { name: /Sign out/ })).toBeInTheDocument()
  })

  it('shows a new name in the header at once and keeps it until the account has it too', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'], { fullName: 'Kavya S' })
    const profile = {
      id: 'user-1',
      email: 'asha@example.com',
      fullName: 'Kavya S',
      phone: null,
      createdAt: '2026-11-16T10:00:00Z',
      updatedAt: '2026-11-16T10:00:00Z',
    }
    // What identity says on a refresh: the old name until the CustomerProfileUpdated event has reached it
    let accountName = 'Kavya S'
    const api = mockApi({
      'GET /customers/me': { status: 200, data: profile },
      'PUT /customers/me': (request) => ({
        status: 200,
        data: { ...profile, ...request.body, updatedAt: '2026-11-17T09:00:00Z' },
      }),
      'GET /customers/me/addresses': { status: 200, data: [] },
      'GET /orders': pageOf([]),
      'GET /reviews/mine': pageOf([]),
      'POST /auth/refresh': () => ({
        status: 200,
        data: fakeAuthResponse('renewed', { roles: ['CUSTOMER'], fullName: accountName }),
      }),
    })
    renderApp('/account/profile')

    const name = await screen.findByLabelText(/Full name/)
    await user.clear(name)
    await user.type(name, 'Kavya Sriram')
    await user.click(screen.getByRole('button', { name: 'Save profile' }))

    expect(await screen.findByRole('button', { name: /account menu for kavya sriram/i })).toBeInTheDocument()
    expect(api.callsTo('PUT /customers/me')[0]?.body).toEqual({ fullName: 'Kavya Sriram', phone: null })

    // A refresh before the account has the new name (token expiry, another tab) does not bring the old one back
    await act(async () => {
      await refreshSession()
    })
    expect(screen.getByRole('button', { name: /account menu for kavya sriram/i })).toBeInTheDocument()

    // Once the account has it, the session follows the account again
    accountName = 'Kavya Sriram'
    await act(async () => {
      await refreshSession()
    })
    accountName = 'Kavya R'
    await act(async () => {
      await refreshSession()
    })
    expect(await screen.findByRole('button', { name: /account menu for kavya r$/i })).toBeInTheDocument()
  })
})
