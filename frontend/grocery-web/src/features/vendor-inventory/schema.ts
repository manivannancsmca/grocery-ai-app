import { z } from 'zod'
import { decimalField, emptyToNull, textField } from '../../shared/forms/validation'
import {
  ADJUSTMENT_REASONS,
  ADJUSTMENT_TYPES,
  type AdjustmentReason,
  type AdjustmentRequest,
  type AdjustmentType,
  BULK_MAX_ITEMS,
  type BulkAdjustmentItem,
  STOCK_LIMITS,
  type StockView,
} from './api'

const WHOLE_NUMBER = /^\d+$/

/** Quantity typed as text → whole number, or null while it is empty / not a whole number. */
export function parseQuantity(raw: string): number | null {
  const value = raw.trim()
  return WHOLE_NUMBER.test(value) ? Number(value) : null
}

/**
 * Contract §2: INCREASE / DECREASE take 1–100,000; SET takes the counted stock, 0–1,000,000.
 * Returns the message for an unacceptable quantity, or null when it is fine.
 */
export function quantityProblem(type: AdjustmentType, raw: string): string | null {
  if (raw.trim() === '') return type === 'SET' ? 'Enter the stock you counted.' : 'Enter a quantity.'
  const quantity = parseQuantity(raw)
  if (quantity === null) return 'Enter a whole number of units.'
  if (type === 'SET') {
    return quantity > STOCK_LIMITS.onHand ? `Enter at most ${STOCK_LIMITS.onHand.toLocaleString('en-IN')}.` : null
  }
  return quantity < 1 || quantity > STOCK_LIMITS.adjustQuantity
    ? `Enter a quantity from 1 to ${STOCK_LIMITS.adjustQuantity.toLocaleString('en-IN')}.`
    : null
}

export const adjustmentSchema = z
  .object({
    type: z.enum(ADJUSTMENT_TYPES),
    quantity: z.string(),
    reason: z.enum(ADJUSTMENT_REASONS),
    note: textField({ max: STOCK_LIMITS.note }),
  })
  .superRefine((values, ctx) => {
    const problem = quantityProblem(values.type, values.quantity)
    if (problem) ctx.addIssue({ code: 'custom', message: problem, path: ['quantity'] })
  })

export type AdjustmentFormValues = z.infer<typeof adjustmentSchema>

/** The reason most adjustments of each type have; the vendor can pick another. */
export const DEFAULT_REASON: Record<AdjustmentType, AdjustmentReason> = {
  INCREASE: 'RESTOCK',
  DECREASE: 'DAMAGED',
  SET: 'STOCK_COUNT',
}

export const EMPTY_ADJUSTMENT_FORM: AdjustmentFormValues = { type: 'INCREASE', quantity: '', reason: DEFAULT_REASON.INCREASE, note: '' }

export function adjustmentFormToRequest(values: AdjustmentFormValues): AdjustmentRequest {
  return { type: values.type, quantity: Number(values.quantity.trim()), reason: values.reason, note: emptyToNull(values.note) }
}

export interface StockPreview {
  onHand: number
  available: number
  /** Why the server will refuse it (stock may never go below what is reserved, nor above the limit). */
  problem: 'BELOW_RESERVED' | 'ABOVE_LIMIT' | null
}

/** Stock after the adjustment, computed from the row on screen; null until the quantity is a whole number. */
export function previewAdjustment(stock: Pick<StockView, 'onHand' | 'reserved'>, type: AdjustmentType, rawQuantity: string): StockPreview | null {
  const quantity = parseQuantity(rawQuantity)
  if (quantity === null) return null
  const onHand = type === 'INCREASE' ? stock.onHand + quantity : type === 'DECREASE' ? stock.onHand - quantity : quantity
  return {
    onHand,
    available: onHand - stock.reserved,
    problem: onHand < stock.reserved ? 'BELOW_RESERVED' : onHand > STOCK_LIMITS.onHand ? 'ABOVE_LIMIT' : null,
  }
}

export const thresholdSchema = z.object({
  lowStockThreshold: decimalField({
    required: true,
    requiredMessage: 'Enter a level, or 0 to turn alerts off.',
    min: 0,
    max: STOCK_LIMITS.threshold,
    decimals: 0,
    integer: true,
  }),
})

export type ThresholdFormValues = z.infer<typeof thresholdSchema>

/**
 * Bulk update form. The array is called `items` like the request body, so server field errors such as
 * `items[2].quantity` land on the matching row.
 */
export const bulkSchema = z
  .object({
    items: z.array(
      z.object({
        productId: z.string(),
        type: z.enum(ADJUSTMENT_TYPES),
        quantity: z.string(),
        reason: z.enum(ADJUSTMENT_REASONS),
      }),
    ),
    note: textField({ max: STOCK_LIMITS.note }),
  })
  .superRefine((values, ctx) => {
    const seen = new Set<string>()
    values.items.forEach((row, index) => {
      if (row.productId === '') {
        ctx.addIssue({ code: 'custom', message: 'Choose a product.', path: ['items', index, 'productId'] })
      } else if (seen.has(row.productId)) {
        ctx.addIssue({ code: 'custom', message: 'This product is already in another row.', path: ['items', index, 'productId'] })
      }
      seen.add(row.productId)
      const problem = quantityProblem(row.type, row.quantity)
      if (problem) ctx.addIssue({ code: 'custom', message: problem, path: ['items', index, 'quantity'] })
    })
  })

export type BulkFormValues = z.infer<typeof bulkSchema>
export type BulkRowValues = BulkFormValues['items'][number]

export function emptyBulkRow(): BulkRowValues {
  return { productId: '', type: 'INCREASE', quantity: '', reason: DEFAULT_REASON.INCREASE }
}

export function bulkFormToItems(values: BulkFormValues): BulkAdjustmentItem[] {
  const note = emptyToNull(values.note)
  return values.items.map((row) => ({
    productId: row.productId,
    type: row.type,
    quantity: Number(row.quantity.trim()),
    reason: row.reason,
    note,
  }))
}

export interface PastedStockLine {
  /** 1-based line number in the pasted text. */
  line: number
  sku: string
  quantity: number
}

export interface PastedLineError {
  /** 1-based line number, or null for a problem with the paste as a whole. */
  line: number | null
  message: string
}

export interface PastedStockLines {
  lines: PastedStockLine[]
  errors: PastedLineError[]
}

/**
 * Reads pasted `SKU,quantity` lines (a spreadsheet copy with tabs or semicolons works too); each becomes a
 * "set counted stock" row. Blank lines and a `sku,quantity` header are skipped; a SKU given twice is an error.
 */
export function parseStockLines(text: string): PastedStockLines {
  const lines: PastedStockLine[] = []
  const errors: PastedLineError[] = []
  const seen = new Map<string, number>()
  let first = true
  text.split(/\r?\n/).forEach((raw, index) => {
    const line = index + 1
    const value = raw.trim()
    if (value === '') return
    const isFirst = first
    first = false
    const parts = value.split(/[,;\t]/).map((part) => part.trim())
    const [sku = '', rawQuantity = ''] = parts
    if (isFirst && sku.toLowerCase() === 'sku' && parseQuantity(rawQuantity) === null) return
    if (parts.length !== 2 || sku === '') {
      errors.push({ line, message: `Line ${line}: write it as SKU,quantity.` })
      return
    }
    const problem = quantityProblem('SET', rawQuantity)
    if (problem) {
      errors.push({ line, message: `Line ${line} (${sku}): ${problem}` })
      return
    }
    const key = sku.toLowerCase()
    const firstLine = seen.get(key)
    if (firstLine !== undefined) {
      errors.push({ line, message: `Line ${line}: SKU ${sku} is already on line ${firstLine}.` })
      return
    }
    seen.set(key, line)
    lines.push({ line, sku, quantity: Number(rawQuantity) })
  })
  if (lines.length > BULK_MAX_ITEMS) {
    return { lines: [], errors: [{ line: null, message: `Paste at most ${BULK_MAX_ITEMS} lines at a time.` }] }
  }
  return { lines, errors }
}
