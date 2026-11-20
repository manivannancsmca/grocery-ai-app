import { z } from 'zod'
import { decimalField, emptyToNull, numberToText, textField } from '../../shared/forms/validation'
import { type LivePrice, PRICE_LIMITS, type PriceRequest } from './api'

const MONEY = /^\d+(\.\d{1,2})?$/

/** Rupees typed as text → number, or null while it is not a valid amount (1.00–1,000,000.00, ≤ 2 decimals). */
export function parseMoney(raw: string): number | null {
  const value = raw.trim()
  if (!MONEY.test(value)) return null
  const amount = Number(value)
  return amount >= PRICE_LIMITS.min && amount <= PRICE_LIMITS.max ? amount : null
}

/** Contract §3: `round((mrp − sellingPrice) / mrp × 100)`, 0–99. */
export function discountPercent(mrp: number, sellingPrice: number): number {
  if (mrp <= 0 || sellingPrice >= mrp) return 0
  return Math.min(99, Math.max(0, Math.round(((mrp - sellingPrice) / mrp) * 100)))
}

/** Signed change of the selling price in percent, rounded to 2 decimals like the server (`-48.98`). */
export function changePercent(from: number, to: number): number {
  if (from <= 0) return 0
  return Math.round(((to - from) / from) * 100 * 100) / 100
}

/** A change applies immediately up to the threshold (exactly the threshold is allowed), in either direction. */
export function needsApproval(change: number, thresholdPercent: number): boolean {
  return Math.abs(change) > thresholdPercent
}

export const SELLING_ABOVE_MRP_MESSAGE = 'The selling price cannot be more than the MRP.'

function moneyField(label: string) {
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

export type PriceFormValues = z.infer<typeof priceSchema>

/** The live price as the starting values (empty for a product without a price). */
export function priceToForm(price: LivePrice | null): PriceFormValues {
  return {
    mrp: price ? numberToText(price.mrp) : '',
    sellingPrice: price ? numberToText(price.sellingPrice) : '',
    note: '',
  }
}

export function priceFormToRequest(values: PriceFormValues): PriceRequest {
  return { mrp: Number(values.mrp.trim()), sellingPrice: Number(values.sellingPrice.trim()), note: emptyToNull(values.note) }
}
