import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { CouponRequest } from '../../../features/coupon-admin/api'
import { mockApi } from '../../../test/mockApi'
import { pageOf } from '../../../test/orderFixtures'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'
import { coupon } from '../../../test/storeAdminFixtures'

describe('Store coupons (vendor and restaurant)', { timeout: 30_000 }, () => {
  it('a vendor creates a flat coupon for its own store (scope STORE, no store id, no store-type choice)', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /vendor/coupons': pageOf([coupon({ scope: 'STORE', storeId: 'v1', storeType: null })]),
      'POST /vendor/coupons': (request) => ({ status: 201, data: coupon({ ...(request.body as CouponRequest), id: 'c5' }) }),
    })
    renderApp('/vendor/coupons')

    expect(await screen.findByRole('table', { name: 'Coupons' })).toHaveTextContent('WELCOME10')
    expect(screen.queryByRole('columnheader', { name: 'Scope' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'New coupon' }))
    const dialog = await screen.findByRole('dialog', { name: 'New coupon' })
    expect(within(dialog).queryByLabelText('Scope', { exact: false })).not.toBeInTheDocument()

    await user.type(within(dialog).getByLabelText(/^Code/), 'FLAT50')
    await user.type(within(dialog).getByLabelText(/^Name/), '₹50 off')
    await user.selectOptions(within(dialog).getByLabelText('Type'), 'Amount off')
    expect(within(dialog).queryByLabelText('Percent off', { exact: false })).not.toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Amount off (₹)', { exact: false }), '50')
    await user.type(within(dialog).getByLabelText('Minimum order (₹)', { exact: false }), '299')
    await user.type(within(dialog).getByLabelText('Total uses', { exact: false }), '200')
    await user.clear(within(dialog).getByLabelText('Uses per customer', { exact: false }))
    await user.type(within(dialog).getByLabelText('Uses per customer', { exact: false }), '2')
    await user.click(within(dialog).getByRole('checkbox', { name: /Show as an offer/ }))
    fireEvent.change(within(dialog).getByLabelText('Valid from', { exact: false }), { target: { value: '2026-11-20T09:00' } })
    fireEvent.change(within(dialog).getByLabelText('Valid until', { exact: false }), { target: { value: '2026-11-30T21:00' } })
    await user.click(within(dialog).getByRole('button', { name: 'Create coupon' }))

    await waitFor(() => expect(api.callsTo('POST /vendor/coupons')).toHaveLength(1))
    expect(api.callsTo('POST /vendor/coupons')[0]?.body).toEqual({
      code: 'FLAT50',
      name: '₹50 off',
      description: null,
      scope: 'STORE',
      discountType: 'FLAT',
      value: 50,
      maxDiscount: null,
      minOrderSubtotal: 299,
      validFrom: new Date('2026-11-20T09:00').toISOString(),
      validUntil: new Date('2026-11-30T21:00').toISOString(),
      totalUsageLimit: 200,
      perCustomerLimit: 2,
      public: false,
    })
  })

  it('a restaurant owner edits and resumes its coupons under /restaurant/coupons', async () => {
    const user = userEvent.setup()
    signInAs(['RESTAURANT_OWNER'], { restaurantId: 'r1' })
    const paused = coupon({ id: 'c7', code: 'THALI20', name: 'Thali Tuesday', value: 20, active: false, scope: 'STORE', storeId: 'r1', storeType: null })
    const api = mockApi({
      'GET /notifications/unread-count': { status: 200, data: { count: 0 } },
      'GET /restaurant/coupons': pageOf([paused]),
      'PUT /restaurant/coupons/:id': (request) => ({ status: 200, data: coupon({ ...paused, ...(request.body as CouponRequest) }) }),
      'POST /restaurant/coupons/:id/resume': { status: 200, data: { ...paused, active: true } },
    })
    renderApp('/restaurant/coupons')

    const row = (await screen.findAllByRole('row'))[1] as HTMLElement
    expect(row).toHaveTextContent('Paused')
    // Several actions per row: they are in the row's "⋯" menu.
    await user.click(within(row).getByRole('button', { name: 'Actions for THALI20' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Resume' }))
    await waitFor(() => expect(api.callsTo('POST /restaurant/coupons/:id/resume')[0]?.pathParams.id).toBe('c7'))

    await user.click(within(row).getByRole('button', { name: 'Actions for THALI20' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog', { name: 'Edit coupon THALI20' })
    const percent = within(dialog).getByLabelText('Percent off', { exact: false })
    await user.clear(percent)
    await user.type(percent, '25')
    await user.click(within(dialog).getByRole('button', { name: 'Save coupon' }))
    await waitFor(() => expect(api.callsTo('PUT /restaurant/coupons/:id')).toHaveLength(1))
    expect(api.callsTo('PUT /restaurant/coupons/:id')[0]?.body).toMatchObject({ code: 'THALI20', scope: 'STORE', value: 25 })
    expect(api.callsTo('PUT /restaurant/coupons/:id')[0]?.body).not.toHaveProperty('storeId')
  })
})
