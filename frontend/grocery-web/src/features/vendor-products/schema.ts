import { z } from 'zod'
import { decimalField, emptyToNull, httpUrlField, numberToText, textField } from '../../shared/forms/validation'
import type { FoodType } from '../../shared/utils/foodType'
import { UNITS_OF_MEASURE, type UnitOfMeasure } from '../catalog/api'
import type { ProductRequest, ProductView } from './api'

/** Limits and patterns from the Phase 3 contract (§3 ProductRequest). */
export const PRODUCT_LIMITS = { name: 150, description: 2000, brand: 80, tags: 10, attributes: 20, images: 8 } as const
const SKU = /^[A-Za-z0-9._-]{1,40}$/
const BARCODE = /^[0-9]{8,14}$/
export const TAG_PATTERN = /^[a-z0-9 -]{2,30}$/

export const UNIT_LABELS: Record<UnitOfMeasure, string> = {
  G: 'g (grams)',
  KG: 'kg (kilograms)',
  ML: 'ml (millilitres)',
  L: 'L (litres)',
  PCS: 'pcs (pieces)',
  PACK: 'pack',
}

/** Tags are lower-case by contract; typing is forgiving. */
export function normalizeTag(raw: string): string {
  return raw.trim().toLowerCase().replace(/\s+/g, ' ')
}

export function tagError(tag: string): string | null {
  return TAG_PATTERN.test(tag) ? null : 'Tags use 2–30 lower-case letters, digits, spaces or dashes.'
}

const attributeRow = z
  .object({ name: z.string(), value: z.string() })
  .superRefine((row, ctx) => {
    const name = row.name.trim()
    const value = row.value.trim()
    if (!name && !value) return // empty rows are ignored
    if (!name) ctx.addIssue({ code: 'custom', message: 'Enter the attribute name.', path: ['name'] })
    else if (name.length > 50) ctx.addIssue({ code: 'custom', message: 'Use at most 50 characters.', path: ['name'] })
    if (!value) ctx.addIssue({ code: 'custom', message: 'Enter the value.', path: ['value'] })
    else if (value.length > 200) ctx.addIssue({ code: 'custom', message: 'Use at most 200 characters.', path: ['value'] })
  })

export const productSchema = z.object({
  name: textField({ required: true, requiredMessage: 'Enter the product name.', max: PRODUCT_LIMITS.name }),
  description: textField({ max: PRODUCT_LIMITS.description }),
  categoryId: z.string().min(1, 'Choose a category.'),
  brandName: textField({ max: PRODUCT_LIMITS.brand }),
  unitValue: decimalField({
    required: true,
    requiredMessage: 'Enter the pack size, e.g. 500.',
    min: 0,
    exclusiveMin: true,
    max: 100_000,
    decimals: 3,
  }),
  unitOfMeasure: z.string().refine((value) => (UNITS_OF_MEASURE as readonly string[]).includes(value), 'Choose a unit.'),
  sku: textField({ pattern: SKU, patternMessage: 'Use up to 40 letters, digits, dots, dashes or underscores.' }),
  barcode: textField({ pattern: BARCODE, patternMessage: 'Barcodes have 8 to 14 digits.' }),
  foodType: z.enum(['', 'VEG', 'NON_VEG', 'EGG']),
  tags: z
    .array(z.string().regex(TAG_PATTERN, 'Tags use 2–30 lower-case letters, digits, spaces or dashes.'))
    .max(PRODUCT_LIMITS.tags, `Use at most ${PRODUCT_LIMITS.tags} tags.`),
  attributes: z.array(attributeRow).max(PRODUCT_LIMITS.attributes, `Use at most ${PRODUCT_LIMITS.attributes} attributes.`),
  imageUrls: z
    .array(z.object({ value: httpUrlField(500) }))
    .max(PRODUCT_LIMITS.images, `Use at most ${PRODUCT_LIMITS.images} images.`),
})

export type ProductFormValues = z.infer<typeof productSchema>

export const EMPTY_PRODUCT_FORM: ProductFormValues = {
  name: '',
  description: '',
  categoryId: '',
  brandName: '',
  unitValue: '',
  unitOfMeasure: 'G',
  sku: '',
  barcode: '',
  foodType: '',
  tags: [],
  attributes: [],
  imageUrls: [],
}

export function productToFormValues(product: ProductView): ProductFormValues {
  return {
    name: product.name,
    description: product.description ?? '',
    categoryId: product.category?.id ?? '',
    brandName: product.brand?.name ?? '',
    unitValue: numberToText(product.unitValue),
    unitOfMeasure: product.unitOfMeasure,
    sku: product.sku ?? '',
    barcode: product.barcode ?? '',
    foodType: product.foodType ?? '',
    tags: product.tags,
    attributes: product.attributes.map((attribute) => ({ name: attribute.name, value: attribute.value })),
    imageUrls: product.imageUrls.map((value) => ({ value })),
  }
}

/** Indexes of the form rows that are sent (blank rows are dropped), to map server errors back. */
export interface KeptRows {
  attributes: number[]
  imageUrls: number[]
}

export function keptRows(values: ProductFormValues): KeptRows {
  return {
    attributes: values.attributes.flatMap((row, index) => (row.name.trim() || row.value.trim() ? [index] : [])),
    imageUrls: values.imageUrls.flatMap((row, index) => (row.value.trim() ? [index] : [])),
  }
}

export function productFormToRequest(values: ProductFormValues): ProductRequest {
  const kept = keptRows(values)
  return {
    name: values.name.trim(),
    description: emptyToNull(values.description),
    categoryId: values.categoryId,
    brandName: emptyToNull(values.brandName),
    unitValue: Number(values.unitValue.trim()),
    unitOfMeasure: values.unitOfMeasure as UnitOfMeasure,
    sku: emptyToNull(values.sku),
    barcode: emptyToNull(values.barcode),
    foodType: values.foodType === '' ? null : (values.foodType as FoodType),
    tags: values.tags,
    attributes: kept.attributes.map((index) => ({
      name: values.attributes[index]?.name.trim() ?? '',
      value: values.attributes[index]?.value.trim() ?? '',
    })),
    imageUrls: kept.imageUrls.map((index) => values.imageUrls[index]?.value.trim() ?? ''),
  }
}

/**
 * Server field names (`imageUrls[1]`, `attributes[0].name`) refer to the request, where blank rows are
 * already removed; these aliases point them at the matching form rows.
 */
export function serverFieldAliases(kept: KeptRows): Record<string, string> {
  const aliases: Record<string, string> = {}
  kept.imageUrls.forEach((formIndex, requestIndex) => {
    aliases[`imageUrls[${requestIndex}]`] = `imageUrls.${formIndex}.value`
    aliases[`imageUrls.${requestIndex}`] = `imageUrls.${formIndex}.value`
  })
  kept.attributes.forEach((formIndex, requestIndex) => {
    for (const part of ['name', 'value']) {
      aliases[`attributes[${requestIndex}].${part}`] = `attributes.${formIndex}.${part}`
      aliases[`attributes.${requestIndex}.${part}`] = `attributes.${formIndex}.${part}`
    }
    aliases[`attributes[${requestIndex}]`] = `attributes.${formIndex}.name`
  })
  return aliases
}
