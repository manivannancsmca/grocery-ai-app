import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { Address } from '../../../features/customer/api'
import { mockApi } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

function address(index: number, overrides: Partial<Address> = {}): Address {
  return {
    id: `a${index}`,
    label: `Place ${index}`,
    recipientName: 'Asha Rao',
    phone: '+919876543210',
    line1: `${index} MG Road`,
    line2: null,
    landmark: null,
    city: 'Bengaluru',
    state: 'Karnataka',
    postalCode: '560001',
    country: 'IN',
    latitude: 12.97,
    longitude: 77.59,
    isDefault: index === 1,
    createdAt: '2026-11-11T10:00:00Z',
    updatedAt: '2026-11-11T10:00:00Z',
    ...overrides,
  }
}

describe('AddressesPage', () => {
  it('disables adding an 11th address', async () => {
    signInAs(['CUSTOMER'])
    mockApi({
      'GET /customers/me/addresses': { status: 200, data: Array.from({ length: 10 }, (_, i) => address(i + 1)) },
    })
    renderApp('/account/addresses')

    expect(await screen.findByText(/maximum of 10 addresses/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add address' })).toBeDisabled()
  })

  it('asks for confirmation before deleting and sets a new default', async () => {
    const user = userEvent.setup()
    signInAs(['CUSTOMER'])
    const api = mockApi({
      'GET /customers/me/addresses': { status: 200, data: [address(1), address(2, { label: 'Work' })] },
      'DELETE /customers/me/addresses/:id': { status: 204 },
      'POST /customers/me/addresses/:id/default': { status: 200, data: address(2, { label: 'Work', isDefault: true }) },
    })
    renderApp('/account/addresses')

    const work = await screen.findByRole('article', { name: 'Work address' })
    await user.click(within(work).getByRole('button', { name: 'Set as default' }))
    expect(await screen.findByText('Work is now your default address.')).toBeInTheDocument()
    expect(api.callsTo('POST /customers/me/addresses/:id/default')[0]?.pathParams.id).toBe('a2')

    await user.click(within(work).getByRole('button', { name: 'Delete' }))
    const confirm = await screen.findByRole('dialog', { name: 'Delete Work?' })
    expect(api.callsTo('DELETE /customers/me/addresses/:id')).toHaveLength(0)
    await user.click(within(confirm).getByRole('button', { name: 'Delete address' }))

    expect(await screen.findByText('Address deleted.')).toBeInTheDocument()
    expect(api.callsTo('DELETE /customers/me/addresses/:id')[0]?.pathParams.id).toBe('a2')
  })
})
