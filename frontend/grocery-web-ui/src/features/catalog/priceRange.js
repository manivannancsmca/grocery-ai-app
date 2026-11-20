import { formatPrice } from '../../shared/utils/format'

const AMOUNT = /^\d+(\.\d{1,2})?$/

/**
 * @typedef {object} PriceRange
 * @property {number} [minPrice]
 * @property {number} [maxPrice]
 */

/**
 * `?minPrice=` / `?maxPrice=` value → rupees, or undefined when absent or not a plain amount.
 * @param {string | null | undefined} value
 * @returns {number | undefined}
 */
export function parsePriceParam(value) {
  const text = value?.trim() ?? ''
  return AMOUNT.test(text) ? Number(text) : undefined
}

/**
 * The range in the URL. A range with min above max is dropped as a whole (the API would answer 400).
 * @param {string | null} min
 * @param {string | null} max
 * @returns {PriceRange}
 */
export function priceRangeFromParams(min, max) {
  const minPrice = parsePriceParam(min)
  const maxPrice = parsePriceParam(max)
  if (minPrice !== undefined && maxPrice !== undefined && minPrice > maxPrice) return {}
  return { minPrice, maxPrice }
}

/**
 * @typedef {object} PriceRangeCheck
 * @property {PriceRange} range
 * @property {{ min?: string, max?: string }} errors
 */

const AMOUNT_MESSAGE = 'Enter an amount like 50 or 49.50.'

/**
 * Validates the two inputs of the price filter (both optional; amounts ≥ 0 with ≤ 2 decimals; min ≤ max).
 * @param {string} min
 * @param {string} max
 * @returns {PriceRangeCheck}
 */
export function checkPriceRange(min, max) {
  const errors = {}
  const minText = min.trim()
  const maxText = max.trim()
  const minPrice = minText === '' ? undefined : parsePriceParam(minText)
  const maxPrice = maxText === '' ? undefined : parsePriceParam(maxText)
  if (minText !== '' && minPrice === undefined) errors.min = AMOUNT_MESSAGE
  if (maxText !== '' && maxPrice === undefined) errors.max = AMOUNT_MESSAGE
  if (minPrice !== undefined && maxPrice !== undefined && minPrice > maxPrice)
    errors.max = 'The maximum must be at least the minimum.'
  return { range: { minPrice, maxPrice }, errors }
}

/**
 * `₹10 – ₹50`, `From ₹10`, `Up to ₹50`, or null without a range.
 * @param {PriceRange} options
 * @returns {string | null}
 */
export function priceRangeLabel({ minPrice, maxPrice }) {
  if (minPrice !== undefined && maxPrice !== undefined) return `${formatPrice(minPrice)} – ${formatPrice(maxPrice)}`
  if (minPrice !== undefined) return `From ${formatPrice(minPrice)}`
  if (maxPrice !== undefined) return `Up to ${formatPrice(maxPrice)}`
  return null
}

/**
 * Range → query-string values (`null` removes the key).
 * @param {PriceRange} options
 * @returns {{ minPrice: string | null, maxPrice: string | null }}
 */
export function priceRangeToParams({ minPrice, maxPrice }) {
  return {
    minPrice: minPrice === undefined ? null : String(minPrice),
    maxPrice: maxPrice === undefined ? null : String(maxPrice),
  }
}
