import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { ProductTile } from './ProductTile'

function card(overrides = {}) {
  return {
    id: 'p1',
    name: 'Tomato',
    unitLabel: '500 g',
    foodType: 'VEG',
    primaryImageUrl: null,
    brand: null,
    category: { id: 'c-veg', name: 'Fresh Vegetables' },
    vendor: { id: 'v1', name: 'Green Basket' },
    price: { mrp: 60, sellingPrice: 49, currency: 'INR', discountPercent: 18 },
    inStock: true,
    ...overrides,
  }
}

function renderTile(product) {
  render(
    <MemoryRouter>
      <ProductTile product={product} />
    </MemoryRouter>,
  )
  return screen.getByRole('article')
}

describe('ProductTile', () => {
  it('shows the selling price, the struck-through MRP and the discount', () => {
    const tile = renderTile(card())

    expect(screen.getByText('₹49')).toBeInTheDocument()
    const mrp = screen.getByText('₹60')
    expect(mrp).toHaveClass('line-through')
    expect(mrp).toHaveTextContent('MRP ₹60')
    expect(screen.getByText('18% off')).toBeInTheDocument()
    expect(tile).not.toHaveTextContent('Out of stock')
    expect(screen.getByRole('link', { name: 'Tomato' })).toHaveAttribute('href', '/products/p1')
  })

  it('names the category the product belongs to', () => {
    const tile = renderTile(card({ name: 'Apple', category: { id: 'c-fruits', name: 'Fruits' } }))

    expect(tile).toHaveTextContent('Category: Fruits')
    expect(screen.getByText('Fruits')).toBeVisible()
  })

  it('leaves out MRP and discount when the product sells at MRP, with paise when needed', () => {
    renderTile(card({ price: { mrp: 49.5, sellingPrice: 49.5, currency: 'INR', discountPercent: 0 } }))

    expect(screen.getByText('₹49.50')).toBeInTheDocument()
    expect(screen.queryByText(/% off/)).not.toBeInTheDocument()
    expect(screen.queryByText(/MRP/)).not.toBeInTheDocument()
  })

  it('says "Price not available" without a price and keeps an out-of-stock product visible with a badge', () => {
    const tile = renderTile(card({ price: null, inStock: false }))

    expect(screen.getByText('Price not available')).toBeInTheDocument()
    expect(screen.getByText('Out of stock')).toBeInTheDocument()
    expect(tile).toHaveTextContent('Tomato')
    expect(tile).not.toHaveTextContent('₹')
  })
})
