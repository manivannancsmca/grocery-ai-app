import { describe, expect, it } from 'vitest'
import { checkPriceRange, priceRangeFromParams, priceRangeLabel, priceRangeToParams } from './priceRange'

describe('price range filter', () => {
  it('reads valid bounds from the URL and drops malformed or inverted ones', () => {
    expect(priceRangeFromParams('10', '50.5')).toEqual({ minPrice: 10, maxPrice: 50.5 })
    expect(priceRangeFromParams('abc', '50')).toEqual({ minPrice: undefined, maxPrice: 50 })
    expect(priceRangeFromParams('-5', null)).toEqual({ minPrice: undefined, maxPrice: undefined })
    expect(priceRangeFromParams('80', '20')).toEqual({})
  })

  it('validates the inputs before they go into the URL', () => {
    expect(checkPriceRange('', '')).toEqual({ range: { minPrice: undefined, maxPrice: undefined }, errors: {} })
    expect(checkPriceRange('1.234', 'x').errors).toEqual({ min: expect.any(String), max: expect.any(String) })
    expect(checkPriceRange('100', '50').errors.max).toMatch(/at least the minimum/)
    expect(checkPriceRange(' 0 ', '50').range).toEqual({ minPrice: 0, maxPrice: 50 })
  })

  it('labels the range and writes it back as query values', () => {
    expect(priceRangeLabel({ minPrice: 10.5, maxPrice: 50 })).toBe('₹10.50 – ₹50')
    expect(priceRangeLabel({ minPrice: 10 })).toBe('From ₹10')
    expect(priceRangeLabel({ maxPrice: 50 })).toBe('Up to ₹50')
    expect(priceRangeLabel({})).toBeNull()
    expect(priceRangeToParams({ maxPrice: 50 })).toEqual({ minPrice: null, maxPrice: '50' })
  })
})
