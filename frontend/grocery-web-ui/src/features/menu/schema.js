import { z } from 'zod'
import { decimalField, emptyToNull, httpUrlField, numberToText, textField } from '../../shared/forms/validation'
/** @typedef {import('./api').MenuCategoryRequest} MenuCategoryRequest */
/** @typedef {import('./api').MenuCategoryView} MenuCategoryView */
/** @typedef {import('./api').MenuItemRequest} MenuItemRequest */
/** @typedef {import('./api').MenuItemView} MenuItemView */

/** Limits from the Phase 3 contract §5. */
const sortOrderField = decimalField({
  required: true,
  requiredMessage: 'Enter a sort order (0 shows first).',
  min: 0,
  max: 9999,
  decimals: 0,
  integer: true,
})

export const menuCategorySchema = z.object({
  name: textField({ required: true, requiredMessage: 'Enter a category name.', max: 80 }),
  description: textField({ max: 300 }),
  sortOrder: sortOrderField,
})

/** @typedef {z.infer<typeof menuCategorySchema>} MenuCategoryFormValues */

/**
 * @param {MenuCategoryView | null} category
 * @param [nextSortOrder=0]
 * @returns {MenuCategoryFormValues}
 */
export function categoryToFormValues(category, nextSortOrder = 0) {
  return {
    name: category?.name ?? '',
    description: category?.description ?? '',
    sortOrder: numberToText(category?.sortOrder ?? nextSortOrder),
  }
}

/**
 * @param {MenuCategoryFormValues} values
 * @returns {MenuCategoryRequest}
 */
export function categoryFormToRequest(values) {
  return {
    name: values.name.trim(),
    description: emptyToNull(values.description),
    sortOrder: Number(values.sortOrder.trim()),
  }
}

export const menuItemSchema = z.object({
  categoryId: z.string().min(1, 'Choose a category.'),
  name: textField({ required: true, requiredMessage: 'Enter the dish name.', max: 120 }),
  description: textField({ max: 500 }),
  price: decimalField({
    required: true,
    requiredMessage: 'Enter the price in rupees.',
    min: 1,
    max: 100_000,
    decimals: 2,
  }),
  foodType: z.enum(['', 'VEG', 'NON_VEG', 'EGG']).refine((value) => value.length > 0, 'Choose veg, non-veg or egg.'),
  imageUrl: httpUrlField(500),
  available: z.boolean(),
  sortOrder: sortOrderField,
})

/** @typedef {z.infer<typeof menuItemSchema>} MenuItemFormValues */

/**
 * @param {MenuItemView | null} item
 * @param {string} categoryId
 * @returns {MenuItemFormValues}
 */
export function itemToFormValues(item, categoryId) {
  return {
    categoryId: item?.categoryId ?? categoryId,
    name: item?.name ?? '',
    description: item?.description ?? '',
    price: item ? item.price.toFixed(2).replace(/\.00$/, '') : '',
    foodType: item?.foodType ?? '',
    imageUrl: item?.imageUrl ?? '',
    available: item?.available ?? true,
    sortOrder: numberToText(item?.sortOrder ?? 0),
  }
}

/**
 * @param {MenuItemFormValues} values
 * @returns {MenuItemRequest}
 */
export function itemFormToRequest(values) {
  return {
    categoryId: values.categoryId,
    name: values.name.trim(),
    description: emptyToNull(values.description),
    price: Number(values.price.trim()),
    foodType: values.foodType,
    imageUrl: emptyToNull(values.imageUrl),
    available: values.available,
    sortOrder: Number(values.sortOrder.trim()),
  }
}
