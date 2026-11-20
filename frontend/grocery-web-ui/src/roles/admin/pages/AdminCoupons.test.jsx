import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { coupon } from '../../../test/storeAdminFixtures'

function setup(roles, coupons, routes = {}) {
  signInAs(roles, { id: 'admin-1' })
  const server = { coupons }
  const api = mockApi({
    'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
    'GET /admin/coupons': () => pageOf(server.coupons),
    'POST /admin/coupons': (request) => {
      const body = request.body
      const created = coupon({
        ...body,
        id: 'c-new',
        storeId: body.storeId ?? null,
        storeType: body.storeType ?? null,
        usedCount: 0,
        discountGiven: 0,
      })
      server.coupons = [created, ...server.coupons]
      return { status: 201, data: created }
    },
    'PUT /admin/coupons/:id': (request) => ({
      status: 200,
      data: coupon({ ...request.body, id: request.pathParams.id }),
    }),
    'POST /admin/coupons/:id/pause': (request) => {
      server.coupons = server.coupons.map((item) =>
        item.id === request.pathParams.id ? { ...item, active: false } : item,
      )
      return { status: 200, data: server.coupons[0] }
    },
    'GET /admin/coupons/:id/usage': pageOf([
      {
        orderId: 'o1',
        orderNumber: 'GA-7K3F9Q2M',
        discount: 50,
        deliveryFeeDiscount: 0,
        status: 'CONFIRMED',
        createdAt: '2026-11-12T10:00:00Z',
      },
      {
        orderId: 'o2',
        orderNumber: 'GA-REL00002',
        discount: 20,
        deliveryFeeDiscount: 25,
        status: 'RELEASED',
        createdAt: '2026-11-12T11:00:00Z',
      },
    ]),
    ...routes,
  })
  return { api, server }
}

describe('Admin coupons', { timeout: 30_000 }, () => {
  it('lists coupons with their state, use and discount given; shows a coupon’s usage', async () => {
    const user = userEvent.setup()
    setup(
      ['FINANCE_ADMIN'],
      [
        coupon(),
        coupon({
          id: 'c2',
          code: 'FREESHIP',
          name: 'Free delivery',
          discountType: 'FREE_DELIVERY',
          value: null,
          maxDiscount: null,
          active: false,
          public: false,
          scope: 'STORE',
          storeId: 'v1',
          storeName: 'Fresh Mart',
          storeType: null,
        }),
      ],
    )
    renderApp('/admin/coupons')

    const table = await screen.findByRole('table', { name: 'Coupons' })
    const rows = within(table).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('WELCOME10')
    expect(rows[1]).toHaveTextContent('10% off up to ₹100')
    expect(rows[1]).toHaveTextContent('Min. order ₹199')
    expect(rows[1]).toHaveTextContent('42 / 500')
    expect(rows[1]).toHaveTextContent('₹3,150.50')
    expect(rows[1]).toHaveTextContent('Active')
    expect(rows[1]).toHaveTextContent('Platform · Every store')
    expect(rows[2]).toHaveTextContent('Paused')
    expect(rows[2]).toHaveTextContent('Unlisted')
    expect(rows[2]).toHaveTextContent('Store: Fresh Mart')

    // Managers have several actions per row, in the row's "⋯" menu.
    await user.click(within(rows[1]).getByRole('button', { name: 'Actions for WELCOME10' }))
    await user.click(await screen.findByRole('menuitem', { name: 'View usage' }))
    const drawer = await screen.findByRole('dialog', { name: 'Usage of WELCOME10' })
    const uses = await within(drawer).findByRole('table', { name: 'Uses of WELCOME10' })
    expect(within(uses).getAllByRole('row')[1]).toHaveTextContent('GA-7K3F9Q2M')
    expect(within(uses).getAllByRole('row')[2]).toHaveTextContent('Released')
    expect(within(uses).getAllByRole('row')[2]).toHaveTextContent('+ ₹25.00 delivery')
    expect(drawer).toHaveTextContent('The platform')
  })

  it('creates a platform coupon: validation first (code, percent, dates), then the request; a taken code is shown on the field', async () => {
    const user = userEvent.setup()
    const { api } = setup(['OPS_ADMIN'], [], {
      'POST /admin/coupons': (request) =>
        request.body.code === 'TAKEN1'
          ? problem(409, 'COUPON_CODE_TAKEN', 'Code taken.')
          : { status: 201, data: coupon({ ...request.body, id: 'c9' }) },
    })
    renderApp('/admin/coupons')

    await screen.findByText('No coupons yet')
    await user.click(screen.getAllByRole('button', { name: 'New coupon' })[0])
    const dialog = await screen.findByRole('dialog', { name: 'New coupon' })
    const field = (name) => within(dialog).getByLabelText(new RegExp(`^${name}`))

    await user.type(field('Code'), 'ab 1')
    await user.type(field('Name'), 'Diwali')
    await user.type(field('Percent off'), '95')
    fireEvent.change(field('Valid from'), { target: { value: '2026-12-10T10:00' } })
    fireEvent.change(field('Valid until'), { target: { value: '2026-12-01T10:00' } })
    await user.click(within(dialog).getByRole('button', { name: 'Create coupon' }))

    expect(await within(dialog).findByText('Use 4–20 letters, digits or dashes (no spaces).')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a percentage from 1 to 90.')).toBeInTheDocument()
    expect(within(dialog).getByText('The end must be after the start.')).toBeInTheDocument()
    expect(api.callsTo('POST /admin/coupons')).toHaveLength(0)

    await user.clear(field('Code'))
    await user.type(field('Code'), 'taken1')
    await user.clear(field('Percent off'))
    await user.type(field('Percent off'), '15')
    await user.type(field('Maximum discount'), '120')
    fireEvent.change(field('Valid until'), { target: { value: '2026-12-31T23:59' } })
    await user.selectOptions(field('Store type'), 'Restaurants')
    await user.click(within(dialog).getByRole('button', { name: 'Create coupon' }))
    expect(
      await within(dialog).findByText('TAKEN1 is already used by another coupon. Choose another code.'),
    ).toBeInTheDocument()

    await user.clear(field('Code'))
    await user.type(field('Code'), 'diwali15')
    await user.click(within(dialog).getByRole('button', { name: 'Create coupon' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New coupon' })).not.toBeInTheDocument())
    expect(api.callsTo('POST /admin/coupons').at(-1)?.body).toEqual({
      code: 'DIWALI15',
      name: 'Diwali',
      description: null,
      scope: 'PLATFORM',
      storeType: 'RESTAURANT',
      storeId: null,
      discountType: 'PERCENT',
      value: 15,
      maxDiscount: 120,
      minOrderSubtotal: 0,
      validFrom: new Date('2026-12-10T10:00').toISOString(),
      validUntil: new Date('2026-12-31T23:59').toISOString(),
      totalUsageLimit: null,
      perCustomerLimit: 1,
      public: true,
    })
  })

  it('a store coupon needs the store id; edit and pause', async () => {
    const user = userEvent.setup()
    const { api } = setup(['SUPER_ADMIN'], [coupon()])
    renderApp('/admin/coupons')

    const row = (await screen.findAllByRole('row'))[1]
    await user.click(within(row).getByRole('button', { name: 'Actions for WELCOME10' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit coupon WELCOME10' })
    expect(within(dialog).getByLabelText(/^Code/)).toHaveValue('WELCOME10')
    expect(within(dialog).getByLabelText('Percent off', { exact: false })).toHaveValue('10')
    await user.selectOptions(within(dialog).getByLabelText('Scope', { exact: false }), 'One store')
    await user.click(within(dialog).getByRole('button', { name: 'Save coupon' }))
    expect(await within(dialog).findByText('Enter the ID of the vendor or restaurant.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Store ID', { exact: false }), 'v1')
    await user.click(within(dialog).getByRole('button', { name: 'Save coupon' }))
    await waitFor(() => expect(api.callsTo('PUT /admin/coupons/:id')).toHaveLength(1))
    expect(api.callsTo('PUT /admin/coupons/:id')[0]?.body).toMatchObject({
      scope: 'STORE',
      storeId: 'v1',
      storeType: null,
      code: 'WELCOME10',
    })

    await user.click(
      within((await screen.findAllByRole('row'))[1]).getByRole('button', { name: 'Actions for WELCOME10' }),
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Pause' }))
    await waitFor(() => expect(api.callsTo('POST /admin/coupons/:id/pause')).toHaveLength(1))
    // Paused now: the row's menu offers "Resume" instead.
    await waitFor(() => expect(screen.getAllByRole('row')[1]).toHaveTextContent('Paused'))
    await user.click(within(screen.getAllByRole('row')[1]).getByRole('button', { name: 'Actions for WELCOME10' }))
    expect(await screen.findByRole('menuitem', { name: 'Resume' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Pause' })).not.toBeInTheDocument()
  })

  it('support reads coupons but cannot manage them', async () => {
    setup(['SUPPORT'], [coupon()])
    renderApp('/admin/coupons')
    const row = (await screen.findAllByRole('row'))[1]
    expect(within(row).getByRole('button', { name: 'Usage of WELCOME10' })).toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: 'Edit WELCOME10' })).not.toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: 'Actions for WELCOME10' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New coupon' })).not.toBeInTheDocument()
  })
})
