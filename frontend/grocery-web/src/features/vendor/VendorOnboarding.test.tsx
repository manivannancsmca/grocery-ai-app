import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../test/mockApi'
import { renderApp } from '../../test/renderApp'
import { signInAs } from '../../test/session'
import type { VendorView } from './api'

function vendor(overrides: Partial<VendorView> = {}): VendorView {
  return {
    id: 'v1',
    ownerUserId: 'user-1',
    status: 'DRAFT',
    businessName: 'FreshMart',
    legalName: 'FreshMart Pvt Ltd',
    gstin: '29ABCDE1234F1Z5',
    fssaiLicense: null,
    contactEmail: 'ops@freshmart.in',
    contactPhone: '+919876543210',
    address: {
      line1: '12 MG Road',
      line2: null,
      city: 'Bengaluru',
      state: 'Karnataka',
      postalCode: '560001',
      country: 'IN',
      latitude: 12.9716,
      longitude: 77.5946,
    },
    statusReason: null,
    submittedAt: null,
    approvedAt: null,
    createdAt: '2026-11-11T10:00:00Z',
    updatedAt: '2026-11-11T10:00:00Z',
    ...overrides,
  }
}

const EMPTY_DRAFT = vendor({
  businessName: null,
  legalName: null,
  gstin: null,
  contactEmail: null,
  contactPhone: null,
  address: null,
})

describe('Vendor onboarding', () => {
  it('shows 422 PROFILE_INCOMPLETE field errors from submit on the matching fields', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'])
    const api = mockApi({
      'GET /vendors/me': { status: 200, data: vendor() },
      'PUT /vendors/me': (request) => ({ status: 200, data: { ...vendor(), ...(request.body as object) } }),
      'POST /vendors/me/submit': problem(422, 'PROFILE_INCOMPLETE', 'Profile is incomplete', [
        { field: 'gstin', message: 'GSTIN could not be verified' },
        { field: 'address.postalCode', message: 'PIN code does not match the city' },
      ]),
    })
    renderApp('/vendor/profile')

    await user.click(await screen.findByRole('button', { name: 'Submit for approval' }))

    expect(await screen.findByText('GSTIN could not be verified')).toBeInTheDocument()
    expect(screen.getByLabelText(/^GSTIN/)).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('PIN code does not match the city')).toBeInTheDocument()
    expect(screen.getByLabelText(/^PIN code/)).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('alert')).toHaveTextContent(/complete the highlighted fields/i)

    // Saved first (PUT), then submitted.
    expect(api.callsTo('PUT /vendors/me')).toHaveLength(1)
    expect(api.callsTo('POST /vendors/me/submit')).toHaveLength(1)
    expect(api.callsTo('PUT /vendors/me')[0]?.body).toMatchObject({
      gstin: '29ABCDE1234F1Z5',
      address: { postalCode: '560001', latitude: 12.9716, longitude: 77.5946, country: 'IN' },
    })
  })

  it('requires the contract fields before submitting, but saves an incomplete draft', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'])
    const api = mockApi({
      'GET /vendors/me': { status: 200, data: EMPTY_DRAFT },
      'PUT /vendors/me': (request) => ({ status: 200, data: { ...EMPTY_DRAFT, ...(request.body as object) } }),
    })
    renderApp('/vendor/profile')

    await user.click(await screen.findByRole('button', { name: 'Submit for approval' }))
    expect(await screen.findAllByText('This field is required.')).not.toHaveLength(0)
    expect(screen.getByLabelText(/^Business name/)).toHaveAttribute('aria-invalid', 'true')
    expect(api.callsTo('PUT /vendors/me')).toHaveLength(0)

    await user.type(screen.getByLabelText(/^Business name/), 'FreshMart')
    await user.type(screen.getByLabelText(/^GSTIN/), 'not-a-gstin')
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    expect(await screen.findByText(/valid 15-character GSTIN/i)).toBeInTheDocument()
    expect(api.callsTo('PUT /vendors/me')).toHaveLength(0)

    await user.clear(screen.getByLabelText(/^GSTIN/))
    await user.click(screen.getByRole('button', { name: 'Save draft' }))
    expect(await screen.findByText('Business profile saved as draft.')).toBeInTheDocument()
    expect(api.callsTo('PUT /vendors/me')[0]?.body).toMatchObject({ businessName: 'FreshMart', gstin: null, address: null })
  })

  it('shows the onboarding read-only while the submission is under review', async () => {
    signInAs(['VENDOR'])
    mockApi({
      'GET /vendors/me': {
        status: 200,
        data: vendor({ status: 'PENDING_APPROVAL', submittedAt: '2026-11-10T09:00:00Z' }),
      },
    })
    renderApp('/vendor')

    expect(await screen.findByText('Waiting for approval')).toBeInTheDocument()
    expect(screen.getByLabelText(/^Business name/)).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Submit for approval' })).not.toBeInTheDocument()
  })

  it('shows the rejection reason and allows editing again', async () => {
    signInAs(['VENDOR'])
    mockApi({
      'GET /vendors/me': { status: 200, data: vendor({ status: 'REJECTED', statusReason: 'GST certificate is blurry.' }) },
    })
    renderApp('/vendor')

    expect(await screen.findByText(/rejected: GST certificate is blurry\./i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^Business name/)).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Submit for approval' })).toBeInTheDocument()
  })
})
