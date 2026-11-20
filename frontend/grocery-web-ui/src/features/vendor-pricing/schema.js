import { z } from 'zod'
import { decimalField, emptyToNull, numberToText, textField } from '../../shared/forms/validation'
import { PRICE_LIMITS } from './api'

/** @typedef {import('./api').LivePrice} LivePrice */
/** @typedef {import('./api').PriceRequest} PriceRequest */

const MONEY = /^\d+(\.\d{1,2})?$/

/**
 * Rupees typed as text → number, or null while it is not a valid amount (1.00–1,000,000.00, ≤ 2 decimals).
 * @param {string} raw
 * @returns {number | null}
 */
export function parseMoney(raw) {
  const value = raw.trim()
  if (!MONEY.test(value)) return null
  const amount = Number(value)
  return amount >= PRICE_LIMITS.min && amount <= PRICE_LIMITS.max ? amount : null
}

/**
 * Contract §3: `round((mrp − sellingPrice) / mrp × 100)`, 0–99.
 * @param {number} mrp
 * @param {number} sellingPrice
 * @returns {number}
 */
export function discountPercent(mrp, sellingPrice) {
  if (mrp <= 0 || sellingPrice >= mrp) return 0
  return Math.min(99, Math.max(0, Math.round(((mrp - sellingPrice) / mrp) * 100)))
}

/**
 * Signed change of the selling price in percent, rounded to 2 decimals like the server (`-48.98`).
 * @param {number} from
 * @param {number} to
 * @returns {number}
 */
export function changePercent(from, to) {
  if (from <= 0) return 0
  return Math.round(((to - from) / from) * 100 * 100) / 100
}

/**
 * A change applies immediately up to the threshold (exactly the threshold is allowed), in either direction.
 * @param {number} change
 * @param {number} thresholdPercent
 * @returns {boolean}
 */
export function needsApproval(change, thresholdPercent) {
  return Math.abs(change) > thresholdPercent
}

export const SELLING_ABOVE_MRP_MESSAGE = 'The selling price cannot be more than the MRP.'

/** @param {string} label */
function moneyField(label) {
  return decimalField({
    required: true,
    requiredMessage: `Enter the ${label}.`,
    min: PRICE_LIMITS.min,
    max: PRICE_LIMITS.max,
    decimals: PRICE_LIMITS.decimals,
  })
}

export const priceSchema = z
  .object({
    mrp: moneyField('MRP'),
    sellingPrice: moneyField('selling price'),
    note: textField({ max: PRICE_LIMITS.note }),
  })
  .superRefine((values, ctx) => {
    const mrp = parseMoney(values.mrp)
    const sellingPrice = parseMoney(values.sellingPrice)
    if (mrp !== null && sellingPrice !== null && sellingPrice > mrp) {
      ctx.addIssue({ code: 'custom', message: SELLING_ABOVE_MRP_MESSAGE, path: ['sellingPrice'] })
    }
  })

/** @typedef {z.infer<typeof priceSchema>} PriceFormValues */

/**
 * The live price as the starting values (empty for a product without a price).
 * @param {LivePrice | null} price
 * @returns {PriceFormValues}
 */
export function priceToForm(price) {
  return {
    mrp: price ? numberToText(price.mrp) : '',
    sellingPrice: price ? numberToText(price.sellingPrice) : '',
    note: '',
  }
}

/**
 * @param {PriceFormValues} values
 * @returns {PriceRequest}
 */
export function priceFormToRequest(values) {
  return {
    mrp: Number(values.mrp.trim()),
    sellingPrice: Number(values.sellingPrice.trim()),
    note: emptyToNull(values.note),
  }
}
