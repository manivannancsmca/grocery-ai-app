import { z } from 'zod'
import { isIsoDay } from '../../shared/utils/indiaDate'
import {
  decimalField,
  emailField,
  emptyToNull,
  MESSAGES,
  numberToText,
  PATTERNS,
  phoneField,
  textField,
  toNumberOrNull,
} from '../../shared/forms/validation'
import { PURCHASING_LIMITS } from './api'

/** @typedef {import('./api').PurchaseOrderRequest} PurchaseOrderRequest */
/** @typedef {import('./api').PurchaseOrderView} PurchaseOrderView */
/** @typedef {import('./api').SupplierRequest} SupplierRequest */
/** @typedef {import('./api').SupplierView} SupplierView */

export const supplierSchema = z.object({
  name: textField({
    required: true,
    requiredMessage: 'Enter the supplier’s name.',
    max: PURCHASING_LIMITS.supplierName,
  }),
  contactName: textField({ max: 120 }),
  phone: phoneField(),
  email: emailField(),
  gstin: textField({
    pattern: PATTERNS.gstin,
    patternMessage: MESSAGES.gstin,
    normalize: (value) => value.toUpperCase(),
  }),
  address: textField({ max: PURCHASING_LIMITS.supplierAddress }),
  leadTimeDays: decimalField({ min: 0, max: PURCHASING_LIMITS.leadTimeDaysMax, decimals: 0, integer: true }),
  notes: textField({ max: PURCHASING_LIMITS.supplierNotes }),
})

/** @typedef {z.infer<typeof supplierSchema>} SupplierFormValues */

/** @type {SupplierFormValues} */
export const EMPTY_SUPPLIER = {
  name: '',
  contactName: '',
  phone: '',
  email: '',
  gstin: '',
  address: '',
  leadTimeDays: '',
  notes: '',
}

/**
 * @param {SupplierView | null} supplier
 * @returns {SupplierFormValues}
 */
export function supplierToForm(supplier) {
  if (!supplier) return EMPTY_SUPPLIER
  return {
    name: supplier.name,
    contactName: supplier.contactName ?? '',
    phone: supplier.phone ?? '',
    email: supplier.email ?? '',
    gstin: supplier.gstin ?? '',
    address: supplier.address ?? '',
    leadTimeDays: numberToText(supplier.leadTimeDays),
    notes: supplier.notes ?? '',
  }
}

/**
 * @param {SupplierFormValues} values
 * @returns {SupplierRequest}
 */
export function supplierFormToRequest(values) {
  const phone = emptyToNull(values.phone)
  const gstin = emptyToNull(values.gstin)
  return {
    name: values.name.trim(),
    contactName: emptyToNull(values.contactName),
    phone: phone ? phone.replace(/[\s()-]/g, '') : null,
    email: emptyToNull(values.email),
    gstin: gstin ? gstin.toUpperCase() : null,
    address: emptyToNull(values.address),
    leadTimeDays: toNumberOrNull(values.leadTimeDays),
    notes: emptyToNull(values.notes),
  }
}

const poLineSchema = z.object({
  productId: z.string().min(1),
  productName: z.string(),
  orderedQuantity: decimalField({
    required: true,
    requiredMessage: 'Enter a quantity.',
    min: 1,
    max: PURCHASING_LIMITS.quantityMax,
    decimals: 0,
    integer: true,
  }),
  unitCost: decimalField({
    required: true,
    requiredMessage: 'Enter the unit cost.',
    min: 0,
    max: 10_000_000,
    decimals: 2,
  }),
})

export const purchaseOrderSchema = z.object({
  supplierId: z.string().min(1, 'Choose a supplier.'),
  expectedAt: z.string().refine((value) => value === '' || isIsoDay(value), 'Enter a date, or leave it empty.'),
  notes: textField({ max: PURCHASING_LIMITS.poNotes }),
  lines: z
    .array(poLineSchema)
    .min(1, 'Add at least one product.')
    .max(PURCHASING_LIMITS.linesMax, `Use at most ${PURCHASING_LIMITS.linesMax} lines.`),
})

/** @typedef {z.infer<typeof purchaseOrderSchema>} PurchaseOrderFormValues */
/** @typedef {PurchaseOrderFormValues['lines'][number]} PurchaseOrderLineValues */

/**
 * @param {PurchaseOrderView | null} order
 * @param [supplierId='']
 * @returns {PurchaseOrderFormValues}
 */
export function purchaseOrderToForm(order, supplierId = '') {
  if (!order) return { supplierId, expectedAt: '', notes: '', lines: [] }
  return {
    supplierId: order.supplierId,
    expectedAt: order.expectedAt ? order.expectedAt.slice(0, 10) : '',
    notes: order.notes ?? '',
    lines: order.lines.map((line) => ({
      productId: line.productId,
      productName: line.productName,
      orderedQuantity: String(line.orderedQuantity),
      unitCost: String(line.unitCost),
    })),
  }
}

/**
 * @param {PurchaseOrderFormValues} values
 * @returns {PurchaseOrderRequest}
 */
export function purchaseOrderFormToRequest(values) {
  return {
    supplierId: values.supplierId,
    expectedAt: values.expectedAt || null,
    notes: emptyToNull(values.notes),
    lines: values.lines.map((line) => ({
      productId: line.productId,
      orderedQuantity: Number(line.orderedQuantity),
      unitCost: Number(line.unitCost),
    })),
  }
}

/**
 * Total of the form's lines (rupees, 2 decimals); invalid lines count as zero.
 * @param {readonly Pick<PurchaseOrderLineValues, 'orderedQuantity' | 'unitCost'>[]} lines
 * @returns {number}
 */
export function formTotal(lines) {
  const total = lines.reduce((sum, line) => {
    const quantity = Number(line.orderedQuantity)
    const cost = Number(line.unitCost)
    return Number.isFinite(quantity) && Number.isFinite(cost) ? sum + quantity * cost : sum
  }, 0)
  return Math.round(total * 100) / 100
}

/**
 * Receiving form: per open line, how many units came now (0 = none of it).
 * @param {Readonly<Record<string, number>>} openByProduct
 */
export function receiveSchema(openByProduct) {
  return z
    .object({
      note: textField({ max: PURCHASING_LIMITS.receiptNote }),
      lines: z.array(
        z.object({
          productId: z.string(),
          quantity: z.string(),
        }),
      ),
    })
    .superRefine((values, ctx) => {
      let received = 0
      let invalid = 0
      values.lines.forEach((line, index) => {
        const raw = line.quantity.trim()
        if (raw === '') return
        if (!/^\d+$/.test(raw)) {
          invalid++
          ctx.addIssue({ code: 'custom', path: ['lines', index, 'quantity'], message: 'Enter a whole number.' })
          return
        }
        const quantity = Number(raw)
        const open = openByProduct[line.productId] ?? 0
        if (quantity > open) {
          invalid++
          ctx.addIssue({
            code: 'custom',
            path: ['lines', index, 'quantity'],
            message: `At most ${open} — that is what is still open.`,
          })
          return
        }
        if (quantity > 0) received++
      })
      if (received === 0 && invalid === 0) {
        ctx.addIssue({
          code: 'custom',
          path: ['lines'],
          message: 'Enter the quantity received for at least one product.',
        })
      }
    })
}

/** @typedef {z.infer<ReturnType<typeof receiveSchema>>} ReceiveFormValues */
