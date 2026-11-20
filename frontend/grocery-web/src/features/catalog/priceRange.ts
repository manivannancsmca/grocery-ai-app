import { formatPrice } from '../../shared/utils/format'

const AMOUNT = /^\d+(\.\d{1,2})?$/

export interface PriceRange {
  minPrice?: number
  maxPrice?: number
}

/** `?minPrice=` / `?maxPrice=` value → rupees, or undefined when absent or not a plain amount. */
export function parsePriceParam(value: string | null | undefined): number | undefined {
  const text = value?.trim() ?? ''
  return AMOUNT.test(text) ? Number(text) : undefined
}

/** The range in the URL. A range with min above max is dropped as a whole (the API would answer 400). */
export function priceRangeFromParams(min: string | null, max: string | null): PriceRange {
  const minPrice = parsePriceParam(min)
  const maxPrice = parsePriceParam(max)
  if (minPrice !== undefined && maxPrice !== undefined && minPrice > maxPrice) return {}
  return { minPrice, maxPrice }
}

export interface PriceRangeCheck {
  range: PriceRange
  errors: { min?: string; max?: string }
}

const AMOUNT_MESSAGE = 'Enter an amount like 50 or 49.50.'

/** Validates the two inputs of the price filter (both optional; amounts ≥ 0 with ≤ 2 decimals; min ≤ max). */
export function checkPriceRange(min: string, max: string): PriceRangeCheck {
  const errors: PriceRangeCheck['errors'] = {}
  const minText = min.trim()
  const maxText = max.trim()
  const minPrice = minText === '' ? undefined : parsePriceParam(minText)
  const maxPrice = maxText === '' ? undefined : parsePriceParam(maxText)
  if (minText !== '' && minPrice === undefined) errors.min = AMOUNT_MESSAGE
  if (maxText !== '' && maxPrice === undefined) errors.max = AMOUNT_MESSAGE
  if (minPrice !== undefined && maxPrice !== undefined && minPrice > maxPrice) errors.max = 'The maximum must be at least the minimum.'
  return { range: { minPrice, maxPrice }, errors }
}

/** `₹10 – ₹50`, `From ₹10`, `Up to ₹50`, or null without a range. */
export function priceRangeLabel({ minPrice, maxPrice }: PriceRange): string | null {
  if (minPrice !== undefined && maxPrice !== undefined) return `${formatPrice(minPrice)} – ${formatPrice(maxPrice)}`
  if (minPrice !== undefined) return `From ${formatPrice(minPrice)}`
  if (maxPrice !== undefined) return `Up to ${formatPrice(maxPrice)}`
  return null
}

/** Range → query-string values (`null` removes the key). */
export function priceRangeToParams({ minPrice, maxPrice }: PriceRange): { minPrice: string | null; maxPrice: string | null } {
  return {
    minPrice: minPrice === undefined ? null : String(minPrice),
    maxPrice: maxPrice === undefined ? null : String(maxPrice),
  }
}
