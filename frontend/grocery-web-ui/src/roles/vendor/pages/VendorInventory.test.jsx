import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { mockApi, problem } from '../../../test/mockApi'
import { renderApp } from '../../../test/renderApp'
import { signInAs } from '../../../test/session'

function stockRow(productId, productName, overrides = {}) {
  return {
    productId,
    productName,
    unitLabel: '500 g',
    sku: null,
    productStatus: 'PUBLISHED',
    onHand: 0,
    reserved: 0,
    available: 0,
    lowStockThreshold: 5,
    lowStock: false,
    inStock: false,
    updatedAt: '2026-11-11T10:15:30Z',
    ...overrides,
  }
}

const tomato = stockRow('p1', 'Tomato', { sku: 'TOM-500', onHand: 12, reserved: 2, available: 10, inStock: true })
const onion = stockRow('p2', 'Onion', {
  sku: 'ONI-1KG',
  unitLabel: '1 kg',
  onHand: 4,
  reserved: 4,
  available: 0,
  lowStock: true,
})
const garlic = stockRow('p3', 'Garlic', {
  sku: 'GAR-100',
  productStatus: 'DRAFT',
  onHand: 3,
  available: 3,
  lowStock: true,
  inStock: true,
})
const rows = [garlic, onion, tomato]

function listStock(request) {
  const filter = request.params.filter
  const content = rows.filter((row) =>
    filter === 'LOW_STOCK'
      ? row.lowStock && row.inStock
      : filter === 'OUT_OF_STOCK'
        ? !row.inStock
        : filter === 'IN_STOCK'
          ? row.inStock
          : true,
  )
  return {
    status: 200,
    data: { content, page: 0, size: Number(request.params.size ?? 20), totalElements: content.length, totalPages: 1 },
  }
}

function inventoryApi(overrides = {}) {
  return mockApi({
    'GET /vendor/inventory': listStock,
    'GET /vendor/inventory/summary': { status: 200, data: { products: 42, inStock: 30, lowStock: 5, outOfStock: 12 } },
    ...overrides,
  })
}

function stockProblem(status, errorCode, properties) {
  const base = problem(status, errorCode)
  return { status, data: { ...base.data, ...properties } }
}

describe('Vendor inventory', () => {
  it('shows the summary and stock badges, and puts the filter tab in the URL', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = inventoryApi()
    const { router } = renderApp('/vendor/inventory')

    const table = await screen.findByRole('table', { name: 'Stock per product' })
    const summary = screen.getByRole('region', { name: 'Stock summary' })
    expect(within(summary).getByText('42')).toBeInTheDocument()
    expect(within(summary).getByText('12')).toHaveClass('text-red-700')

    expect(within(table).getByText('Onion').closest('tr')).toHaveTextContent('Out of stock')
    expect(within(table).getByText('Garlic').closest('tr')).toHaveTextContent('Low stock')
    expect(within(table).getByText('Garlic').closest('tr')).toHaveTextContent('Draft')
    expect(within(table).getByText('Tomato').closest('tr')).toHaveTextContent('In stock')
    expect(within(table).getByText('SKU TOM-500')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Low stock' }))
    expect(router.state.location.search).toBe('?filter=LOW_STOCK')
    await waitFor(() =>
      expect(api.callsTo('GET /vendor/inventory').at(-1)?.params).toMatchObject({ filter: 'LOW_STOCK' }),
    )
    await waitFor(() =>
      expect(
        within(screen.getByRole('table', { name: 'Stock per product' })).queryByText('Tomato'),
      ).not.toBeInTheDocument(),
    )
    expect(within(screen.getByRole('table', { name: 'Stock per product' })).getByText('Garlic')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Out of stock' }))
    expect(router.state.location.search).toBe('?filter=OUT_OF_STOCK')
  })

  it('adjusts stock and explains INSUFFICIENT_STOCK with the numbers the server returned', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = inventoryApi({
      'POST /vendor/inventory/:id/adjustments': (request) => {
        const body = request.body
        return body.quantity > 9
          ? stockProblem(409, 'INSUFFICIENT_STOCK', { available: 9, reserved: 3 })
          : { status: 200, data: { ...tomato, onHand: 7, available: 5 } }
      },
    })
    renderApp('/vendor/inventory')

    await user.click(await screen.findByRole('button', { name: 'Adjust stock of Tomato' }))
    const dialog = await screen.findByRole('dialog', { name: 'Adjust stock of Tomato' })
    await user.selectOptions(within(dialog).getByLabelText(/^Adjustment type/), 'DECREASE')
    expect(within(dialog).getByLabelText(/^Reason/)).toHaveValue('DAMAGED')
    await user.type(within(dialog).getByLabelText(/^Units to remove/), '11')
    expect(within(dialog).getByText(/stock cannot go below what is reserved/i)).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Save adjustment' }))
    expect(
      await within(dialog).findByText('Only 9 units can be removed: 3 units are reserved for orders.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/^Units to remove/)).toHaveAttribute('aria-invalid', 'true')

    await user.clear(within(dialog).getByLabelText(/^Units to remove/))
    await user.type(within(dialog).getByLabelText(/^Units to remove/), '5')
    await user.click(within(dialog).getByRole('button', { name: 'Save adjustment' }))

    expect(await screen.findByText('Tomato now has 7 units on hand.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const calls = api.callsTo('POST /vendor/inventory/:id/adjustments')
    expect(calls).toHaveLength(2)
    expect(calls[1]?.pathParams.id).toBe('p1')
    expect(calls[1]?.body).toEqual({ type: 'DECREASE', quantity: 5, reason: 'DAMAGED', note: null })
  })

  it('bulk-updates from pasted SKU lines and names the product that made the whole request fail', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = inventoryApi({
      'POST /vendor/inventory/adjustments/bulk': stockProblem(409, 'STOCK_BELOW_RESERVED', {
        productId: 'p2',
        available: 0,
        reserved: 4,
      }),
    })
    renderApp('/vendor/inventory')

    await user.click(await screen.findByRole('button', { name: 'Bulk update' }))
    const dialog = await screen.findByRole('dialog', { name: 'Bulk stock update' })
    await waitFor(() =>
      expect(within(dialog).getAllByRole('option', { name: /Tomato · 500 g · TOM-500/ }).length).toBeGreaterThan(0),
    )

    const paste = within(dialog).getByLabelText('SKU,quantity lines')
    await user.click(paste)
    await user.paste('sku,quantity\nTOM-500,24\nONI-1KG,3\nXYZ-9,5')
    await user.click(within(dialog).getByRole('button', { name: 'Add lines' }))

    expect(await within(dialog).findByText('Line 4: none of your products has SKU XYZ-9.')).toBeInTheDocument()
    expect(within(dialog).getByText('Added 2 rows that set the counted stock.')).toBeInTheDocument()
    expect(paste).toHaveValue('XYZ-9,5')
    expect(within(dialog).getByLabelText(/Product \(row 1\)/)).toHaveValue('p1')
    expect(within(dialog).getByLabelText(/Change \(row 1\)/)).toHaveValue('SET')
    expect(within(dialog).getByLabelText(/Product \(row 2\)/)).toHaveValue('p2')
    expect(within(dialog).getByLabelText(/Quantity \(row 2\)/)).toHaveValue('3')
    expect(within(dialog).queryByLabelText(/Product \(row 3\)/)).not.toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Save all changes' }))

    expect(
      await within(dialog).findByText(
        /nothing was saved\. onion \(row 2\): the counted stock cannot be below the 4 units reserved/i,
      ),
    ).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/Quantity \(row 2\)/)).toHaveAttribute('aria-invalid', 'true')
    expect(within(dialog).getByLabelText(/Quantity \(row 1\)/)).not.toHaveAttribute('aria-invalid')
    expect(api.callsTo('POST /vendor/inventory/adjustments/bulk')[0]?.body).toEqual({
      items: [
        { productId: 'p1', type: 'SET', quantity: 24, reason: 'STOCK_COUNT', note: null },
        { productId: 'p2', type: 'SET', quantity: 3, reason: 'STOCK_COUNT', note: null },
      ],
    })
    expect(within(dialog).getByRole('heading', { name: 'Bulk stock update' })).toBeInTheDocument()
  })

  it('validates bulk rows before sending: a product is required and appears only once', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const api = inventoryApi({ 'POST /vendor/inventory/adjustments/bulk': { status: 200, data: [tomato] } })
    renderApp('/vendor/inventory')

    await user.click(await screen.findByRole('button', { name: 'Bulk update' }))
    const dialog = await screen.findByRole('dialog', { name: 'Bulk stock update' })
    await waitFor(() => expect(within(dialog).getAllByRole('option', { name: /Onion/ }).length).toBeGreaterThan(0))

    await user.click(within(dialog).getByRole('button', { name: 'Add row' }))
    await user.selectOptions(within(dialog).getByLabelText(/Product \(row 1\)/), 'p1')
    await user.type(within(dialog).getByLabelText(/Quantity \(row 1\)/), '6')
    await user.selectOptions(within(dialog).getByLabelText(/Product \(row 2\)/), 'p1')
    await user.click(within(dialog).getByRole('button', { name: 'Save all changes' }))

    expect(await within(dialog).findByText('This product is already in another row.')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a quantity.')).toBeInTheDocument()
    expect(api.callsTo('POST /vendor/inventory/adjustments/bulk')).toHaveLength(0)

    await user.click(within(dialog).getByRole('button', { name: 'Remove row 2' }))
    await user.click(within(dialog).getByRole('button', { name: 'Save all changes' }))
    expect(await screen.findByText('Stock updated for 1 product.')).toBeInTheDocument()
    expect(api.callsTo('POST /vendor/inventory/adjustments/bulk')[0]?.body).toEqual({
      items: [{ productId: 'p1', type: 'INCREASE', quantity: 6, reason: 'RESTOCK', note: null }],
    })
  })

  it('shows the approval notice for a vendor that is not active (403 VENDOR_NOT_ACTIVE)', async () => {
    signInAs(['VENDOR'])
    mockApi({
      'GET /vendor/inventory': problem(403, 'VENDOR_NOT_ACTIVE'),
      'GET /vendor/inventory/summary': problem(403, 'VENDOR_NOT_ACTIVE'),
    })
    renderApp('/vendor/inventory')

    expect(await screen.findByText('Inventory opens up once your business is approved')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Bulk update' })).not.toBeInTheDocument()
  })

  it('shows the stock ledger of a product, newest first, page by page', async () => {
    const user = userEvent.setup()
    signInAs(['VENDOR'], { vendorId: 'v1' })
    const movement = {
      id: 'm1',
      type: 'INCREASE',
      quantityDelta: 10,
      onHandAfter: 12,
      reservedAfter: 2,
      reason: 'RESTOCK',
      note: 'Morning delivery',
      reference: null,
      actorUserId: 'user-1',
      createdAt: '2026-11-11T10:15:30Z',
    }
    const api = inventoryApi({
      'GET /vendor/inventory/:id/movements': (request) => ({
        status: 200,
        data: {
          content:
            request.params.page === 1
              ? [
                  {
                    ...movement,
                    id: 'm0',
                    type: 'RESERVE',
                    quantityDelta: 0,
                    reason: null,
                    note: null,
                    reference: 'order-7',
                  },
                ]
              : [movement],
          page: Number(request.params.page),
          size: 10,
          totalElements: 11,
          totalPages: 2,
        },
      }),
    })
    renderApp('/vendor/inventory')

    await user.click(await screen.findByRole('button', { name: 'Stock history of Tomato' }))
    const drawer = await screen.findByRole('dialog', { name: 'Stock history of Tomato' })
    const ledger = await within(drawer).findByRole('list', { name: 'Stock changes' })
    expect(ledger).toHaveTextContent('Increase · Restock (new delivery)')
    expect(ledger).toHaveTextContent('+10')
    expect(ledger).toHaveTextContent('“Morning delivery”')

    await user.click(within(drawer).getByRole('button', { name: 'Next' }))
    expect(await within(drawer).findByText('Order order-7')).toBeInTheDocument()
    expect(within(drawer).getByText('Reserved for an order')).toBeInTheDocument()
    expect(api.callsTo('GET /vendor/inventory/:id/movements').at(-1)?.params).toEqual({ page: 1, size: 10 })
  })
})
