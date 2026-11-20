import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { PartnerView } from '../../../features/delivery/api'
import type { VendorView } from '../../../features/vendor/api'
import { mockApi } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const pendingVendor: VendorView = {
  id: 'v1',
  ownerUserId: 'owner-1',
  status: 'PENDING_APPROVAL',
  businessName: 'FreshMart',
  legalName: 'FreshMart Pvt Ltd',
  gstin: '29ABCDE1234F1Z5',
  fssaiLicense: null,
  contactEmail: 'ops@freshmart.in',
  contactPhone: '+919876543210',
  address: null,
  statusReason: null,
  submittedAt: '2026-11-10T09:00:00Z',
  approvedAt: null,
  createdAt: '2026-11-09T09:00:00Z',
  updatedAt: '2026-11-10T09:00:00Z',
}

const pendingPartner: PartnerView = {
  id: 'p1',
  email: 'ravi@example.com',
  status: 'PENDING_APPROVAL',
  fullName: 'Ravi Kumar',
  phone: '+919812345678',
  city: 'Bengaluru',
  vehicleType: 'SCOOTER',
  vehicleNumber: 'KA01AB1234',
  drivingLicenseNumber: 'KA0120201234567',
  statusReason: null,
  submittedAt: '2026-11-10T09:00:00Z',
  approvedAt: null,
  createdAt: '2026-11-09T09:00:00Z',
  updatedAt: '2026-11-10T09:00:00Z',
}

function page<T>(content: T[]) {
  return { status: 200, data: { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 } }
}

function mockAdminApi() {
  return mockApi({
    'GET /admin/vendors': (request) =>
      request.params.status === 'PENDING_APPROVAL' ? page([pendingVendor]) : page([]),
    'GET /admin/vendors/:id': { status: 200, data: pendingVendor },
    'GET /admin/vendors/:id/history': {
      status: 200,
      data: [{ fromStatus: 'DRAFT', toStatus: 'PENDING_APPROVAL', reason: null, changedBy: 'owner-1', changedAt: '2026-11-10T09:00:00Z' }],
    },
    'POST /admin/vendors/:id/reject': (request) => ({
      status: 200,
      data: { ...pendingVendor, status: 'REJECTED', statusReason: (request.body as { reason: string }).reason },
    }),
    'GET /admin/restaurants': page([]),
    'GET /admin/delivery-partners': page([pendingPartner]),
    'GET /admin/delivery-partners/:id': { status: 200, data: pendingPartner },
    'GET /admin/delivery-partners/:id/history': { status: 200, data: [] },
  })
}

describe('Approvals', () => {
  it('shows the queue at a glance: totals, a tile per type, cards with how long each waited, and an empty queue', async () => {
    const user = userEvent.setup()
    signInAs(['OPS_ADMIN'])
    mockAdminApi()
    renderApp('/admin/approvals')

    // The banner adds up the three queues and names the oldest submission.
    const hero = await screen.findByRole('region', { name: 'Approvals' })
    await waitFor(() => expect(within(hero).getByText('Waiting for review').closest('div')).toHaveTextContent('2'))
    expect(within(hero).getByText('Oldest submission').closest('div')).toHaveTextContent(/days ago/)

    // One tile per type, with its waiting and approved counts; vendors first.
    const tiles = screen.getByRole('tablist', { name: 'Stakeholder type' })
    const vendors = within(tiles).getByRole('tab', { name: /vendors/i })
    expect(vendors).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(vendors).toHaveTextContent(/0 approved.*1\s*waiting/i))
    expect(within(tiles).getByRole('tab', { name: /delivery partners/i })).toHaveTextContent(/1 approved/)

    // A card per submission: who, how to reach them, how long it has waited.
    const card = await screen.findByRole('article', { name: 'FreshMart' })
    expect(card).toHaveTextContent('ops@freshmart.in')
    expect(card).toHaveTextContent(/Waiting \d+ days/)
    expect(within(card).getByRole('button', { name: 'Review FreshMart' })).toBeInTheDocument()

    // An empty queue is a calm "all caught up"; arrow keys move between the tiles.
    const restaurants = within(tiles).getByRole('tab', { name: /restaurants/i })
    await user.click(restaurants)
    expect(await screen.findByText('All caught up!')).toBeInTheDocument()
    await user.keyboard('{ArrowRight}')
    const partners = within(tiles).getByRole('tab', { name: /delivery partners/i })
    expect(partners).toHaveAttribute('aria-selected', 'true')
    expect(partners).toHaveFocus()
    expect(await screen.findByRole('article', { name: 'Ravi Kumar' })).toBeInTheDocument()
  })

  it('requires a reason to reject an application', async () => {
    const user = userEvent.setup()
    signInAs(['OPS_ADMIN'])
    const api = mockAdminApi()
    renderApp('/admin/approvals')

    await user.click(await screen.findByRole('button', { name: 'Review FreshMart' }))
    const drawer = await screen.findByRole('dialog', { name: 'FreshMart' })
    expect(await within(drawer).findByText('ops@freshmart.in')).toBeInTheDocument()

    await user.click(within(drawer).getByRole('button', { name: 'Reject' }))
    const reasonDialog = await screen.findByRole('dialog', { name: 'Reject FreshMart' })
    await user.click(within(reasonDialog).getByRole('button', { name: 'Reject application' }))

    expect(await within(reasonDialog).findByText(/enter a reason/i)).toBeInTheDocument()
    expect(api.callsTo('POST /admin/vendors/:id/reject')).toHaveLength(0)

    await user.type(within(reasonDialog).getByLabelText(/reason/i), 'GSTIN does not match the legal name.')
    await user.click(within(reasonDialog).getByRole('button', { name: 'Reject application' }))

    expect(await screen.findByText('FreshMart rejected.')).toBeInTheDocument()
    const reject = api.callsTo('POST /admin/vendors/:id/reject')
    expect(reject).toHaveLength(1)
    expect(reject[0]?.pathParams.id).toBe('v1')
    expect(reject[0]?.body).toEqual({ reason: 'GSTIN does not match the legal name.' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('hides approve / reject from admin roles without permission', async () => {
    const user = userEvent.setup()
    signInAs(['FINANCE_ADMIN'])
    mockAdminApi()
    renderApp('/admin/approvals')

    await user.click(await screen.findByRole('button', { name: 'Review FreshMart' }))
    const drawer = await screen.findByRole('dialog', { name: 'FreshMart' })

    expect(within(drawer).getByText(/can view vendors but not change their status/i)).toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(within(drawer).queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('lets a delivery manager decide on delivery partners but not on vendors', async () => {
    const user = userEvent.setup()
    signInAs(['DELIVERY_MANAGER'])
    mockAdminApi()
    renderApp('/admin/approvals?type=delivery-partners')

    await user.click(await screen.findByRole('button', { name: 'Review Ravi Kumar' }))
    const partnerDrawer = await screen.findByRole('dialog', { name: 'Ravi Kumar' })
    expect(within(partnerDrawer).getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(within(partnerDrawer).getByRole('button', { name: 'Reject' })).toBeInTheDocument()
    await user.click(within(partnerDrawer).getByRole('button', { name: 'Close' }))

    await user.click(screen.getByRole('tab', { name: /vendors/i }))
    await user.click(await screen.findByRole('button', { name: 'Review FreshMart' }))
    const vendorDrawer = await screen.findByRole('dialog', { name: 'FreshMart' })
    expect(within(vendorDrawer).queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })
})
