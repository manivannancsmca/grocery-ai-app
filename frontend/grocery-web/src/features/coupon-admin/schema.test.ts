import { describe, expect, it } from 'vitest'
import { coupon } from '../../test/storeAdminFixtures'
import { type CouponFormValues, couponFormToRequest, couponSchema, couponToForm, fromLocalInput, newCouponForm } from './schema'

const NOW = Date.parse('2026-11-13T10:00:00Z')

function valid(overrides: Partial<CouponFormValues> = {}): CouponFormValues {
  return { ...newCouponForm('admin', NOW), code: 'save-10', name: 'Save 10', value: '10', ...overrides }
}

function errorsOf(owner: 'admin' | 'vendor', values: CouponFormValues): Record<string, string> {
  const result = couponSchema(owner).safeParse(values)
  if (result.success) return {}
  return Object.fromEntries(result.error.issues.map((issue) => [issue.path.join('.'), issue.message]))
}

describe('coupon form', () => {
  it('accepts a valid percent coupon and upper-cases the code in the request', () => {
    expect(errorsOf('admin', valid())).toEqual({})
    const request = couponFormToRequest('admin', valid({ maxDiscount: '75', minOrderSubtotal: '299', totalUsageLimit: '100', perCustomerLimit: '2' }))
    expect(request).toMatchObject({
      code: 'SAVE-10',
      scope: 'PLATFORM',
      storeType: 'ANY',
      storeId: null,
      discountType: 'PERCENT',
      value: 10,
      maxDiscount: 75,
      minOrderSubtotal: 299,
      totalUsageLimit: 100,
      perCustomerLimit: 2,
      public: true,
    })
    expect(request.validUntil).toBe(new Date(NOW + 30 * 86_400_000).toISOString().replace(/:\d{2}\.\d{3}Z$/, ':00.000Z'))
  })

  it('checks the code pattern: 4–20 letters, digits or dashes', () => {
    expect(errorsOf('admin', valid({ code: '' })).code).toBe('Enter a code.')
    expect(errorsOf('admin', valid({ code: 'AB1' })).code).toBe('Use 4–20 letters, digits or dashes (no spaces).')
    expect(errorsOf('admin', valid({ code: 'SAVE 10' })).code).toBe('Use 4–20 letters, digits or dashes (no spaces).')
    expect(errorsOf('admin', valid({ code: 'A'.repeat(21) })).code).toBeDefined()
    expect(errorsOf('admin', valid({ code: 'diwali-2026' })).code).toBeUndefined()
  })

  it('keeps percentages between 1 and 90 and amounts positive', () => {
    expect(errorsOf('admin', valid({ value: '0' })).value).toBe('Enter a percentage from 1 to 90.')
    expect(errorsOf('admin', valid({ value: '91' })).value).toBe('Enter a percentage from 1 to 90.')
    expect(errorsOf('admin', valid({ value: '90' })).value).toBeUndefined()
    expect(errorsOf('admin', valid({ value: '' })).value).toBe('Enter the percentage.')
    expect(errorsOf('admin', valid({ maxDiscount: '-5' })).maxDiscount).toBeDefined()
    expect(errorsOf('admin', valid({ discountType: 'FLAT', value: '0' })).value).toMatch(/above ₹0/)
    expect(errorsOf('admin', valid({ discountType: 'FLAT', value: '150', minOrderSubtotal: '100' })).value).toBe('The amount off cannot be more than the minimum order.')
    // Free delivery needs no value, and sends none.
    expect(errorsOf('admin', valid({ discountType: 'FREE_DELIVERY', value: '' }))).toEqual({})
    expect(couponFormToRequest('vendor', valid({ discountType: 'FREE_DELIVERY', value: '5', maxDiscount: '9' }))).toMatchObject({ value: null, maxDiscount: null })
  })

  it('needs both dates, the end after the start', () => {
    expect(errorsOf('admin', valid({ validFrom: '' })).validFrom).toBe('Choose when the coupon starts.')
    expect(errorsOf('admin', valid({ validUntil: '' })).validUntil).toBe('Choose when the coupon ends.')
    expect(errorsOf('admin', valid({ validFrom: '2026-12-01T10:00', validUntil: '2026-12-01T10:00' })).validUntil).toBe('The end must be after the start.')
    expect(errorsOf('admin', valid({ validFrom: '2026-12-01T10:00', validUntil: '2026-11-30T10:00' })).validUntil).toBe('The end must be after the start.')
    expect(fromLocalInput('2026-12-01T10:00')).toBe(new Date('2026-12-01T10:00').toISOString())
  })

  it('limits: per customer at least 1 and not above the total; an admin STORE coupon needs the store id', () => {
    expect(errorsOf('admin', valid({ perCustomerLimit: '0' })).perCustomerLimit).toBe('Enter a whole number from 1 to 1000.')
    expect(errorsOf('admin', valid({ perCustomerLimit: '5', totalUsageLimit: '3' })).perCustomerLimit).toBe('Cannot be more than the total limit.')
    expect(errorsOf('admin', valid({ totalUsageLimit: '1.5' })).totalUsageLimit).toBeDefined()
    expect(errorsOf('admin', valid({ scope: 'STORE', storeId: '' })).storeId).toBe('Enter the ID of the vendor or restaurant.')
    expect(couponFormToRequest('admin', valid({ scope: 'STORE', storeId: ' v1 ' }))).toMatchObject({ scope: 'STORE', storeId: 'v1', storeType: null })
    // A store's own coupon never sends a store id: the server forces it.
    expect(errorsOf('vendor', valid({ scope: 'STORE', storeId: '' }))).toEqual({})
    const vendorRequest = couponFormToRequest('vendor', valid())
    expect(vendorRequest.scope).toBe('STORE')
    expect('storeId' in vendorRequest).toBe(false)
  })

  it('round-trips an existing coupon through the form', () => {
    const existing = coupon({ code: 'FREESHIP', discountType: 'FREE_DELIVERY', value: null, maxDiscount: null, minOrderSubtotal: 0 })
    const form = couponToForm(existing)
    expect(form).toMatchObject({ code: 'FREESHIP', discountType: 'FREE_DELIVERY', value: '', minOrderSubtotal: '', perCustomerLimit: '1', public: true })
    expect(couponFormToRequest('admin', form).validFrom).toBe(existing.validFrom.replace('Z', '.000Z'))
  })
})
