import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { PriceChangeRequestView, PriceRequest, PriceView } from '../../../features/vendor-pricing/api'
import { type MockHandler, mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

const tomato: PriceView = {
  productId: 'p1',
  productName: 'Tomato',
  unitLabel: '500 g',
  sku: 'TOM-500',
  productStatus: 'PUBLISHED',
  price: { mrp: 60, sellingPrice: 49, currency: 'INR', discountPercent: 18, updatedAt: '2026-11-10T10:00:00Z' },
  pendingRequest: null,
  approvalThresholdPercent: 20,
}

const onion: PriceView = {
  productId: 'p2',
  productName: 'Onion',
  unitLabel: '1 kg',
  sku: null,
  productStatus: 'PUBLISHED',
  price: null,
  pendingRequest: null,
  approvalThresholdPercent: 20,
}

const pendingRequest: PriceChangeRequestView = {
  id: 'r1',
  productId: 'p1',
  productName: 'Tomato',
  unitLabel: '500 g',
  vendorId: 'v1',
  currentMrp: 60,
  currentSellingPrice: 49,
  requestedMrp: 60,
  requestedSellingPrice: 25,
  changePercent: -48.98,
  status: 'PENDING',
  note: 'Clearance',
  requestedBy: 'user-1',
  requestedAt: '2026-11-11T09:00:00Z',
  decidedBy: null,
  decidedAt: null,
  decisionReason: null,
}

function pricingApi(routes: Record<string, MockHandler> = {}) {
  return mockApi({
    'GET /vendor/prices': { status: 200, data: { content: [onion, tomato], page: 0, size: 20, totalElements: 2, totalPages: 1 } },
    'GET /vendor/prices/summary': { status: 200, data: { products: 2, priced: 1, unpriced: 1, pendingApproval: 0 } },
    ...routes,
  })
}

async function openSetPrice(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('button', { name: `Set the price of ${name}` }))
  return screen.findByRole('dialog', { name: `Set the price of ${name}` })
}

describe('Vendor pricing', () => {
  it('lists prices and applies a small change immediately (200 APPLIED) with a live discount preview', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = pricingApi({
      'PUT /vendor/prices/:id': (request) => {
        const body = request.body as PriceRequest
        return {
          status: 200,
          data: { ...tomato, price: { ...tomato.price, sellingPrice: body.sellingPrice, discountPercent: 25 }, outcome: 'APPLIED' },
        }
      },
    })
    renderApp('/vendor/pricing')

    const table = await screen.findByRole('table', { name: 'Prices per product' })
    expect(within(table).getByText('Onion').closest('tr')).toHaveTextContent('No price')
    expect(within(table).getByText('Tomato').closest('tr')).toHaveTextContent('₹49.00')
    expect(within(table).getByText('Tomato').closest('tr')).toHaveTextContent('18%')

    const dialog = await openSetPrice(user, 'Tomato')
    expect(within(dialog).getByLabelText(/^MRP/)).toHaveValue('60')
    const selling = within(dialog).getByLabelText(/^Selling price/)
    expect(selling).toHaveValue('49')
    expect(within(dialog).getByText(/changes of up to 20% \(up or down\) apply immediately/i)).toBeInTheDocument()

    await user.clear(selling)
    await user.type(selling, '45')
    expect(within(dialog).getByText('25% off')).toBeInTheDocument()
    expect(within(dialog).getByText('−8.16%')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Save price' }))
    expect(await screen.findByText('Price updated')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const put = api.callsTo('PUT /vendor/prices/:id')
    expect(put).toHaveLength(1)
    expect(put[0]?.pathParams.id).toBe('p1')
    expect(put[0]?.body).toEqual({ mrp: 60, sellingPrice: 45, note: null })
  })

  it('warns when a change exceeds the approval threshold and reports 202 PENDING_APPROVAL', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = pricingApi({
      'PUT /vendor/prices/:id': { status: 202, data: { ...tomato, pendingRequest, outcome: 'PENDING_APPROVAL' } },
    })
    renderApp('/vendor/pricing')

    const dialog = await openSetPrice(user, 'Tomato')
    const selling = within(dialog).getByLabelText(/^Selling price/)
    await user.clear(selling)
    await user.type(selling, '25')

    const warning = within(dialog).getByRole('alert')
    expect(warning).toHaveTextContent('This change needs admin approval')
    expect(warning).toHaveTextContent('A change of −48.98% is more than 20%')
    await user.type(within(dialog).getByLabelText(/^Note/), 'Clearance')
    await user.click(within(dialog).getByRole('button', { name: 'Send for approval' }))

    expect(await screen.findByText('Sent for approval')).toBeInTheDocument()
    expect(screen.queryByText('Price updated')).not.toBeInTheDocument()
    expect(api.callsTo('PUT /vendor/prices/:id')[0]?.body).toEqual({ mrp: 60, sellingPrice: 25, note: 'Clearance' })
  })

  it('checks the selling price against the MRP and puts 422 SELLING_PRICE_ABOVE_MRP on the field', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = pricingApi({ 'PUT /vendor/prices/:id': problem(422, 'SELLING_PRICE_ABOVE_MRP', 'Selling price above MRP') })
    renderApp('/vendor/pricing')

    const dialog = await openSetPrice(user, 'Onion')
    expect(within(dialog).getByText(/first price of the product, so it applies immediately/i)).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/^MRP/), '50')
    await user.type(within(dialog).getByLabelText(/^Selling price/), '55')
    await user.click(within(dialog).getByRole('button', { name: 'Save price' }))
    expect(await within(dialog).findByText('The selling price cannot be more than the MRP.')).toBeInTheDocument()
    expect(api.callsTo('PUT /vendor/prices/:id')).toHaveLength(0)

    await user.clear(within(dialog).getByLabelText(/^Selling price/))
    await user.type(within(dialog).getByLabelText(/^Selling price/), '45')
    expect(within(dialog).queryByText('The selling price cannot be more than the MRP.')).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Save price' }))

    expect(await within(dialog).findByText('The selling price cannot be more than the MRP.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/^Selling price/)).toHaveAttribute('aria-invalid', 'true')
    expect(api.callsTo('PUT /vendor/prices/:id')[0]?.body).toEqual({ mrp: 50, sellingPrice: 45, note: null })
  })

  it('offers to cancel the pending request on 409 PRICE_CHANGE_PENDING, then saves', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    let cancelled = false
    const api = pricingApi({
      'PUT /vendor/prices/:id': () =>
        cancelled ? { status: 200, data: { ...tomato, outcome: 'APPLIED' } } : problem(409, 'PRICE_CHANGE_PENDING', 'A change is pending'),
      'GET /vendor/prices/:id': { status: 200, data: { ...tomato, pendingRequest } },
      'POST /vendor/price-change-requests/:id/cancel': () => {
        cancelled = true
        return { status: 200, data: { ...pendingRequest, status: 'CANCELLED' } }
      },
    })
    renderApp('/vendor/pricing')

    const dialog = await openSetPrice(user, 'Tomato')
    const selling = within(dialog).getByLabelText(/^Selling price/)
    await user.clear(selling)
    await user.type(selling, '47')
    await user.click(within(dialog).getByRole('button', { name: 'Save price' }))

    expect(await within(dialog).findByText('A price change is waiting for approval')).toBeInTheDocument()
    expect(within(dialog).getByText(/cancel it first/i)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel pending request' }))

    expect(await within(dialog).findByText(/the pending request was cancelled/i)).toBeInTheDocument()
    expect(api.callsTo('POST /vendor/price-change-requests/:id/cancel')[0]?.pathParams.id).toBe('r1')
    await user.click(within(dialog).getByRole('button', { name: 'Save price' }))
    expect(await screen.findByText('Price updated')).toBeInTheDocument()
    expect(api.callsTo('PUT /vendor/prices/:id')).toHaveLength(2)
  })

  it('lists my price-change requests and cancels a pending one', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = pricingApi({
      'GET /vendor/price-change-requests': {
        status: 200,
        data: {
          content: [pendingRequest, { ...pendingRequest, id: 'r0', status: 'REJECTED', decisionReason: 'Too low', decidedAt: '2026-11-10T09:00:00Z' }],
          page: 0,
          size: 20,
          totalElements: 2,
          totalPages: 1,
        },
      },
      'POST /vendor/price-change-requests/:id/cancel': { status: 200, data: { ...pendingRequest, status: 'CANCELLED' } },
    })
    const { router } = renderApp('/vendor/pricing')

    await user.click(await screen.findByRole('tab', { name: 'My requests' }))
    expect(router.state.location.search).toBe('?view=requests')
    const table = await screen.findByRole('table', { name: 'Your price-change requests' })
    expect(within(table).getByText('Too low')).toBeInTheDocument()
    expect(within(table).getAllByRole('button', { name: 'Cancel the price change for Tomato' })).toHaveLength(1)

    await user.click(within(table).getByRole('button', { name: 'Cancel the price change for Tomato' }))
    const confirm = await screen.findByRole('dialog', { name: 'Cancel the price change for Tomato?' })
    await user.click(within(confirm).getByRole('button', { name: 'Cancel request' }))
    expect(await screen.findByText('The price change for Tomato was cancelled.')).toBeInTheDocument()
    await waitFor(() => expect(api.callsTo('POST /vendor/price-change-requests/:id/cancel')).toHaveLength(1))
  })
})
