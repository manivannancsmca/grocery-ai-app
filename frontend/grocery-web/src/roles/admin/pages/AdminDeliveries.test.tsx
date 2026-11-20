import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { AdminDeliveryDetail, AdminDeliveryView } from '../../../features/admin-deliveries/api'
import type { UserRole } from '../../../shared/auth/roles'
import { adminDeliveryDetail, adminDeliveryView, onlinePartner, unassignedDelivery } from '../../../test/deliveryFixtures'
import { type MockHandler, mockApi, problem } from '../../../test/mockApi'
import { mockEventStream } from '../../../test/mockEventStream'
import { notificationView } from '../../../test/notificationFixtures'
import { adminOrderSummary, adminOrderView, pageOf, paymentView } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const ONLINE_PARTNERS = [
  // Farther from the store than Sunil.
  onlinePartner({ partnerId: 'p-deepa', name: 'Deepa', vehicleType: 'BICYCLE', latitude: 13.05, longitude: 77.7 }),
  onlinePartner({ partnerId: 'p-kiran', name: 'Kiran', busy: true, activeDeliveryId: 'd7' }),
  onlinePartner(),
  onlinePartner({ partnerId: 'user-1', name: 'Ravi Kumar', busy: true, activeDeliveryId: 'd1' }),
]

/** delivery-service's admin API: GA-NOPART02 found nobody; GA-7K3F9Q2M is with Ravi. */
function setup(roles: UserRole[], routes: Record<string, MockHandler> = {}) {
  signInAs(roles)
  const server: { list: AdminDeliveryView[]; details: Record<string, AdminDeliveryDetail> } = {
    list: [unassignedDelivery(), adminDeliveryView()],
    details: { d2: adminDeliveryDetail(unassignedDelivery()), d1: adminDeliveryDetail(adminDeliveryView()) },
  }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /admin/deliveries': () => pageOf(server.list),
    'GET /admin/deliveries/:id': (request) => ({ status: 200, data: server.details[request.pathParams.id ?? ''] }),
    'GET /admin/delivery-partners/online': { status: 200, data: ONLINE_PARTNERS },
    'POST /admin/deliveries/:id/reassign': (request) => {
      const { partnerId } = request.body as { partnerId?: string }
      const id = request.pathParams.id ?? ''
      const updated: AdminDeliveryView = partnerId
        ? { ...(server.details[id] as AdminDeliveryDetail), status: 'ASSIGNED', partnerId, partnerName: 'Sunil', assignmentFailed: false }
        : { ...(server.details[id] as AdminDeliveryDetail), status: 'SEARCHING', partnerId: null, partnerName: null }
      return { status: 200, data: updated }
    },
    ...routes,
  })
  return { api, server }
}

async function openDelivery(user: ReturnType<typeof userEvent.setup>, orderNumber: string) {
  const table = await screen.findByRole('table', { name: 'Deliveries' })
  await user.click(within(table).getByRole('button', { name: `View delivery ${orderNumber}` }))
  const drawer = await screen.findByRole('dialog', { name: `Delivery for order ${orderNumber}` })
  await within(drawer).findByRole('heading', { name: 'Offers' })
  return drawer
}

describe('Admin delivery monitor', { timeout: 20_000 }, () => {
  it('lists deliveries and puts status, "No partner found" and the order number in the URL and the request', async () => {
    const user = userEvent.setup()
    const { api } = setup(['SUPPORT'])
    const { router } = renderApp('/admin/deliveries')

    const table = await screen.findByRole('table', { name: 'Deliveries' })
    const stuck = within(table).getByText('GA-NOPART02').closest('tr') as HTMLElement
    expect(stuck).toHaveTextContent('No partner found')
    expect(stuck).toHaveTextContent('Finding a partner')
    expect(stuck).toHaveTextContent('Green Basket')
    const assigned = within(table).getByText('GA-7K3F9Q2M').closest('tr') as HTMLElement
    expect(assigned).toHaveTextContent('Ravi Kumar')
    expect(assigned).toHaveTextContent('Partner assigned')
    expect(api.callsTo('GET /admin/deliveries')[0]?.params).toEqual({ page: 0, size: 20 })

    await user.selectOptions(screen.getByLabelText('Status'), 'SEARCHING')
    await user.click(screen.getByRole('checkbox', { name: 'No partner found' }))
    await user.type(screen.getByLabelText('Order number'), 'GA-NOPART02')

    await waitFor(() =>
      expect(api.callsTo('GET /admin/deliveries').at(-1)?.params).toEqual({
        status: 'SEARCHING',
        assignmentFailed: true,
        q: 'GA-NOPART02',
        page: 0,
        size: 20,
      }),
    )
    const search = new URLSearchParams(router.state.location.search)
    expect(search.get('status')).toBe('SEARCHING')
    expect(search.get('noPartner')).toBe('1')

    const nav = screen.getAllByRole('navigation', { name: 'Main navigation' })[0] as HTMLElement
    expect(within(nav).getByRole('link', { name: 'Deliveries' })).toHaveAttribute('aria-current', 'page')
  })

  it('shows the timeline and the offers with their outcomes, and lets a delivery manager assign the nearest free partner', async () => {
    const user = userEvent.setup()
    const { api } = setup(['DELIVERY_MANAGER'])
    renderApp('/admin/deliveries')

    const drawer = await openDelivery(user, 'GA-NOPART02')
    expect(within(drawer).getByText('No delivery partner found')).toBeInTheDocument()
    const offers = within(drawer).getByRole('list', { name: 'Offers' })
    expect(within(offers).getByText('Anil').closest('li')).toHaveTextContent('Expired')
    expect(within(offers).getByText('Meena').closest('li')).toHaveTextContent('Rejected')
    expect(within(drawer).getByRole('list', { name: 'Delivery history' })).toHaveTextContent('Finding a partner')
    expect(drawer).toHaveTextContent('Asha Rao')

    await user.click(within(drawer).getByRole('button', { name: 'Assign a partner' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reassign order GA-NOPART02' })
    const partners = await within(dialog).findByRole('list', { name: 'Online partners' })
    const radios = within(partners).getAllByRole('radio')
    // Free partners first, nearest to the store first; busy ones cannot be chosen.
    expect(radios.map((radio) => radio.closest('label')?.textContent)).toEqual([
      expect.stringContaining('Sunil'),
      expect.stringContaining('Deepa'),
      expect.stringContaining('Kiran'),
      expect.stringContaining('Ravi Kumar'),
    ])
    expect(within(partners).getByRole('radio', { name: /Kiran/ })).toBeDisabled()
    expect(within(partners).getByText(/Kiran/).closest('label')).toHaveTextContent('Busy with another delivery')

    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }))
    expect(await within(dialog).findByText('Choose a partner, or “Search again”.')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/deliveries/:id/reassign')).toHaveLength(0)

    await user.click(within(partners).getByRole('radio', { name: /Sunil/ }))
    await user.type(within(dialog).getByLabelText(/Reason/), 'Nearest free partner')
    const reads = api.callsTo('GET /admin/deliveries').length
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }))

    expect(await screen.findByText('Order GA-NOPART02 assigned to Sunil')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/deliveries/:id/reassign')[0]?.body).toEqual({ partnerId: 'p-sunil', reason: 'Nearest free partner' })
    expect(api.callsTo('POST /admin/deliveries/:id/reassign')[0]?.pathParams.id).toBe('d2')
    expect(screen.queryByRole('dialog', { name: 'Reassign order GA-NOPART02' })).not.toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('GET /admin/deliveries').length).toBeGreaterThan(reads))
  })

  it('searches again without the current partner', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'])
    renderApp('/admin/deliveries')

    const drawer = await openDelivery(user, 'GA-7K3F9Q2M')
    expect(within(drawer).getByText('Ravi Kumar')).toBeInTheDocument()
    await user.click(within(drawer).getByRole('button', { name: 'Reassign' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reassign order GA-7K3F9Q2M' })
    expect(within(dialog).getByText(/Ravi Kumar/, { selector: 'p' })).toHaveTextContent('Ravi Kumar is told the delivery was reassigned.')
    expect(await within(dialog).findByRole('radio', { name: /Ravi Kumar.*Current partner/ })).toBeDisabled()

    await user.click(within(dialog).getByRole('radio', { name: /Search again/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }))

    expect(await screen.findByText('Searching for a new partner for order GA-7K3F9Q2M')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/deliveries/:id/reassign')[0]?.body).toEqual({})
  })

  it('keeps the dialog open on 409 PARTNER_UNAVAILABLE and re-reads the online partners', async () => {
    const user = userEvent.setup()
    const { api } = setup(['SUPER_ADMIN'], { 'POST /admin/deliveries/:id/reassign': problem(409, 'PARTNER_UNAVAILABLE') })
    renderApp('/admin/deliveries')

    const drawer = await openDelivery(user, 'GA-NOPART02')
    await user.click(within(drawer).getByRole('button', { name: 'Assign a partner' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reassign order GA-NOPART02' })
    await user.click(await within(dialog).findByRole('radio', { name: /Sunil/ }))
    const reads = api.callsTo('GET /admin/delivery-partners/online').length
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Sunil is no longer available')
    await waitFor(() => expect(api.callsTo('GET /admin/delivery-partners/online').length).toBeGreaterThan(reads))
    expect(within(dialog).getByRole('radio', { name: /Sunil/ })).not.toBeChecked()
  })

  it('closes and explains 409 INVALID_DELIVERY_TRANSITION', async () => {
    const user = userEvent.setup()
    setup(['SUPER_ADMIN'], {
      'POST /admin/deliveries/:id/reassign': { status: 409, data: { ...problem(409, 'INVALID_DELIVERY_TRANSITION').data, currentStatus: 'PICKED_UP' } },
    })
    renderApp('/admin/deliveries')

    const drawer = await openDelivery(user, 'GA-7K3F9Q2M')
    await user.click(within(drawer).getByRole('button', { name: 'Reassign' }))
    const dialog = await screen.findByRole('dialog', { name: 'Reassign order GA-7K3F9Q2M' })
    await user.click(within(dialog).getByRole('radio', { name: /Search again/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Reassign' }))

    const toast = (await screen.findByText('Order GA-7K3F9Q2M can no longer be reassigned')).closest('li') as HTMLElement
    expect(toast).toHaveTextContent('It is now picked up. The monitor has been refreshed.')
    expect(screen.queryByRole('dialog', { name: 'Reassign order GA-7K3F9Q2M' })).not.toBeInTheDocument()
  })

  it.each(['FINANCE_ADMIN', 'SUPPORT'] as const)('shows deliveries to %s without the reassign action', async (role) => {
    const user = userEvent.setup()
    setup([role])
    renderApp('/admin/deliveries')

    const drawer = await openDelivery(user, 'GA-NOPART02')
    expect(within(drawer).queryByRole('button', { name: 'Assign a partner' })).not.toBeInTheDocument()
    expect(within(drawer).getByText(/can view deliveries but not reassign them/)).toBeInTheDocument()
  })

  it('refreshes on ADMIN_DELIVERIES_CHANGED and NO_DELIVERY_PARTNER', async () => {
    const sse = mockEventStream()
    const { server } = setup(['DELIVERY_MANAGER'])
    renderApp('/admin/deliveries')

    const table = await screen.findByRole('table', { name: 'Deliveries' })
    const stream = await sse.connection()

    server.list = [...server.list, unassignedDelivery({ deliveryId: 'd3', orderId: 'o3', orderNumber: 'GA-NEWSRCH3', assignmentFailed: false })]
    stream.signal({ type: 'ADMIN_DELIVERIES_CHANGED', data: { deliveryId: 'd3', orderId: 'o3' } })
    expect(await within(table).findByText('GA-NEWSRCH3')).toBeInTheDocument()

    server.list = [...server.list, unassignedDelivery({ deliveryId: 'd4', orderId: 'o4', orderNumber: 'GA-NOBODY04' })]
    stream.notification(
      notificationView({
        id: '0193a1b2-0000-7000-8000-0000000000f1',
        type: 'NO_DELIVERY_PARTNER',
        category: 'DELIVERY',
        severity: 'WARNING',
        title: 'No delivery partner for order GA-NOBODY04',
        body: 'Assign one by hand.',
        link: '/admin/deliveries',
        data: { orderId: 'o4', orderNumber: 'GA-NOBODY04', deliveryId: 'd4' },
      }),
    )
    expect(await within(table).findByText('GA-NOBODY04')).toBeInTheDocument()
  })

  it('shows the delivery partner in the admin order drawer and links to the delivery', async () => {
    const user = userEvent.setup()
    signInAs(['OPS_ADMIN'])
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /admin/orders': pageOf([adminOrderSummary({ status: 'OUT_FOR_DELIVERY', payment: { status: 'PAID' } })]),
      'GET /admin/orders/:id': {
        status: 200,
        data: adminOrderView({
          status: 'OUT_FOR_DELIVERY',
          cancellable: false,
          adminCancellable: true,
          delivery: { partnerName: 'Ravi Kumar', assignedAt: '2026-11-11T10:06:00Z', pickedUpAt: '2026-11-11T10:20:00Z', deliveredAt: null },
        }),
      },
      'GET /payments/orders/:orderId': { status: 200, data: paymentView() },
      'GET /admin/deliveries': pageOf([adminDeliveryView()]),
    })
    const { router } = renderApp('/admin/orders')

    const table = await screen.findByRole('table', { name: 'Orders' })
    expect(within(table).getByText('On the way')).toBeInTheDocument()
    await user.click(within(table).getByRole('button', { name: 'View order GA-7K3F9Q2M' }))
    const drawer = await screen.findByRole('dialog', { name: 'Order GA-7K3F9Q2M' })
    const delivery = (await within(drawer).findByRole('heading', { name: 'Delivery' })).closest('section') as HTMLElement
    expect(delivery).toHaveTextContent('Delivery partnerRavi Kumar')
    // Nobody cancels after pickup, whatever an older answer says.
    expect(within(drawer).queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()

    await user.click(within(delivery).getByRole('link', { name: 'Open in deliveries' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/deliveries'))
    expect(new URLSearchParams(router.state.location.search).get('q')).toBe('GA-7K3F9Q2M')
    await waitFor(() => expect(api.callsTo('GET /admin/deliveries').at(-1)?.params).toMatchObject({ q: 'GA-7K3F9Q2M' }))
  })
})
