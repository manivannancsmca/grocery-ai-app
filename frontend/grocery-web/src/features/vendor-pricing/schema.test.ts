import { describe, expect, it } from 'vitest'
import { changePercent, discountPercent, needsApproval, parseMoney, priceFormToRequest, priceSchema } from './schema'

describe('price math', () => {
  it('computes the discount like the contract (rounded, 0–99)', () => {
    expect(discountPercent(60, 49)).toBe(18)
    expect(discountPercent(60, 60)).toBe(0)
    expect(discountPercent(100, 1)).toBe(99)
  })

  it('computes the signed selling-price change with 2 decimals', () => {
    expect(changePercent(49, 25)).toBe(-48.98)
    expect(changePercent(49, 45)).toBe(-8.16)
    expect(changePercent(50, 60)).toBe(20)
  })

  it('allows exactly the threshold without approval, in either direction', () => {
    expect(needsApproval(changePercent(49, 39.2), 20)).toBe(false)
    expect(needsApproval(changePercent(50, 60), 20)).toBe(false)
    expect(needsApproval(20.01, 20)).toBe(true)
    expect(needsApproval(-20.01, 20)).toBe(true)
  })

  it('parses rupee amounts within the contract range', () => {
    expect(parseMoney('49.50')).toBe(49.5)
    expect(parseMoney('0.99')).toBeNull()
    expect(parseMoney('1.005')).toBeNull()
    expect(parseMoney('1000000.01')).toBeNull()
  })
})

describe('priceSchema', () => {
  it('keeps the selling price at most the MRP and builds the request', () => {
    const invalid = priceSchema.safeParse({ mrp: '50', sellingPrice: '60', note: '' })
    expect(invalid.success).toBe(false)
    expect(invalid.error?.issues[0]?.path).toEqual(['sellingPrice'])

    const valid = priceSchema.parse({ mrp: '60', sellingPrice: '49.5', note: '  Festival offer ' })
    expect(priceFormToRequest(valid)).toEqual({ mrp: 60, sellingPrice: 49.5, note: 'Festival offer' })
  })
})
