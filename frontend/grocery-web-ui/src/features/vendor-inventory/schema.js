import { z } from 'zod'
import { decimalField, emptyToNull, textField } from '../../shared/forms/validation'
import { ADJUSTMENT_REASONS, ADJUSTMENT_TYPES, BULK_MAX_ITEMS, STOCK_LIMITS } from './api'

/** @typedef {import('./api').AdjustmentReason} AdjustmentReason */
/** @typedef {import('./api').AdjustmentRequest} AdjustmentRequest */
/** @typedef {import('./api').AdjustmentType} AdjustmentType */
/** @typedef {import('./api').BulkAdjustmentItem} BulkAdjustmentItem */
/** @typedef {import('./api').StockView} StockView */

const WHOLE_NUMBER = /^\d+$/

/**
 * Quantity typed as text → whole number, or null while it is empty / not a whole number.
 * @param {string} raw
 * @returns {number | null}
 */
export function parseQuantity(raw) {
  const value = raw.trim()
  return WHOLE_NUMBER.test(value) ? Number(value) : null
}

/**
 * Contract §2: INCREASE / DECREASE take 1–100,000; SET takes the counted stock, 0–1,000,000.
 * Returns the message for an unacceptable quantity, or null when it is fine.
 * @param {AdjustmentType} type
 * @param {string} raw
 * @returns {string | null}
 */
export function quantityProblem(type, raw) {
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

/** @typedef {z.infer<typeof adjustmentSchema>} AdjustmentFormValues */

/**
 * The reason most adjustments of each type have; the vendor can pick another.
 * @type {Record<AdjustmentType, AdjustmentReason>}
 */
export const DEFAULT_REASON = {
  INCREASE: 'RESTOCK',
  DECREASE: 'DAMAGED',
  SET: 'STOCK_COUNT',
}

/** @type {AdjustmentFormValues} */
export const EMPTY_ADJUSTMENT_FORM = { type: 'INCREASE', quantity: '', reason: DEFAULT_REASON.INCREASE, note: '' }

/**
 * @param {AdjustmentFormValues} values
 * @returns {AdjustmentRequest}
 */
export function adjustmentFormToRequest(values) {
  return {
    type: values.type,
    quantity: Number(values.quantity.trim()),
    reason: values.reason,
    note: emptyToNull(values.note),
  }
}

/**
 * @typedef {object} StockPreview
 * @property {number} onHand
 * @property {number} available
 * @property {'BELOW_RESERVED' | 'ABOVE_LIMIT' | null} problem Why the server will refuse it (stock may never go below what is reserved, nor above the limit).
 */

/**
 * Stock after the adjustment, computed from the row on screen; null until the quantity is a whole number.
 * @param {Pick<StockView, 'onHand' | 'reserved'>} stock
 * @param {AdjustmentType} type
 * @param {string} rawQuantity
 * @returns {StockPreview | null}
 */
export function previewAdjustment(stock, type, rawQuantity) {
  const quantity = parseQuantity(rawQuantity)
  if (quantity === null) return null
  const onHand =
    type === 'INCREASE' ? stock.onHand + quantity : type === 'DECREASE' ? stock.onHand - quantity : quantity
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

/** @typedef {z.infer<typeof thresholdSchema>} ThresholdFormValues */

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
    const seen = new Set()
    values.items.forEach((row, index) => {
      if (row.productId === '') {
        ctx.addIssue({ code: 'custom', message: 'Choose a product.', path: ['items', index, 'productId'] })
      } else if (seen.has(row.productId)) {
        ctx.addIssue({
          code: 'custom',
          message: 'This product is already in another row.',
          path: ['items', index, 'productId'],
        })
      }
      seen.add(row.productId)
      const problem = quantityProblem(row.type, row.quantity)
      if (problem) ctx.addIssue({ code: 'custom', message: problem, path: ['items', index, 'quantity'] })
    })
  })

/** @typedef {z.infer<typeof bulkSchema>} BulkFormValues */
/** @typedef {BulkFormValues['items'][number]} BulkRowValues */

/** @returns {BulkRowValues} */
export function emptyBulkRow() {
  return { productId: '', type: 'INCREASE', quantity: '', reason: DEFAULT_REASON.INCREASE }
}

/**
 * @param {BulkFormValues} values
 * @returns {BulkAdjustmentItem[]}
 */
export function bulkFormToItems(values) {
  const note = emptyToNull(values.note)
  return values.items.map((row) => ({
    productId: row.productId,
    type: row.type,
    quantity: Number(row.quantity.trim()),
    reason: row.reason,
    note,
  }))
}

/**
 * @typedef {object} PastedStockLine
 * @property {number} line 1-based line number in the pasted text.
 * @property {string} sku
 * @property {number} quantity
 */

/**
 * @typedef {object} PastedLineError
 * @property {number | null} line 1-based line number, or null for a problem with the paste as a whole.
 * @property {string} message
 */

/**
 * @typedef {object} PastedStockLines
 * @property {PastedStockLine[]} lines
 * @property {PastedLineError[]} errors
 */

/**
 * Reads pasted `SKU,quantity` lines (a spreadsheet copy with tabs or semicolons works too); each becomes a
 * "set counted stock" row. Blank lines and a `sku,quantity` header are skipped; a SKU given twice is an error.
 * @param {string} text
 * @returns {PastedStockLines}
 */
export function parseStockLines(text) {
  const lines = []
  const errors = []
  const seen = new Map()
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
