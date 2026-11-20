import { describe, expect, it } from 'vitest'
import { parseStockLines, previewAdjustment, quantityProblem } from './schema'

describe('quantityProblem', () => {
  it('applies the contract ranges per adjustment type', () => {
    expect(quantityProblem('INCREASE', '')).toBe('Enter a quantity.')
    expect(quantityProblem('INCREASE', '0')).toMatch(/from 1 to 1,00,000/)
    expect(quantityProblem('DECREASE', '2.5')).toBe('Enter a whole number of units.')
    expect(quantityProblem('DECREASE', '100000')).toBeNull()
    expect(quantityProblem('SET', '0')).toBeNull()
    expect(quantityProblem('SET', '1000001')).toMatch(/at most 10,00,000/)
  })
})

describe('previewAdjustment', () => {
  it('flags a result below the reserved stock', () => {
    expect(previewAdjustment({ onHand: 12, reserved: 2 }, 'DECREASE', '11')).toEqual({
      onHand: 1,
      available: -1,
      problem: 'BELOW_RESERVED',
    })
    expect(previewAdjustment({ onHand: 12, reserved: 2 }, 'SET', '20')).toEqual({
      onHand: 20,
      available: 18,
      problem: null,
    })
    expect(previewAdjustment({ onHand: 12, reserved: 2 }, 'INCREASE', 'x')).toBeNull()
  })
})

describe('parseStockLines', () => {
  it('reads SKU,quantity lines with comma, tab or semicolon and skips a header and blank lines', () => {
    expect(parseStockLines('sku,quantity\nTOM-500,24\n\nONI-1KG\t3\nGAR; 0\n')).toEqual({
      lines: [
        { line: 2, sku: 'TOM-500', quantity: 24 },
        { line: 4, sku: 'ONI-1KG', quantity: 3 },
        { line: 5, sku: 'GAR', quantity: 0 },
      ],
      errors: [],
    })
  })

  it('reports malformed lines, bad quantities and repeated SKUs by line number', () => {
    const { lines, errors } = parseStockLines('TOM-500,24\nonly-a-sku\nONI-1KG,-1\ntom-500,5')
    expect(lines).toEqual([{ line: 1, sku: 'TOM-500', quantity: 24 }])
    expect(errors).toEqual([
      { line: 2, message: 'Line 2: write it as SKU,quantity.' },
      { line: 3, message: 'Line 3 (ONI-1KG): Enter a whole number of units.' },
      { line: 4, message: 'Line 4: SKU tom-500 is already on line 1.' },
    ])
  })
})
