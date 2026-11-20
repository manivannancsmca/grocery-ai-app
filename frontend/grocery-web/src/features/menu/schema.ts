import { z } from 'zod'
import { decimalField, emptyToNull, httpUrlField, numberToText, textField } from '../../shared/forms/validation'
import type { FoodType } from '../../shared/utils/foodType'
import type { MenuCategoryRequest, MenuCategoryView, MenuItemRequest, MenuItemView } from './api'

/** Limits from the Phase 3 contract §5. */
const sortOrderField = decimalField({ required: true, requiredMessage: 'Enter a sort order (0 shows first).', min: 0, max: 9999, decimals: 0, integer: true })

export const menuCategorySchema = z.object({
  name: textField({ required: true, requiredMessage: 'Enter a category name.', max: 80 }),
  description: textField({ max: 300 }),
  sortOrder: sortOrderField,
})

export type MenuCategoryFormValues = z.infer<typeof menuCategorySchema>

export function categoryToFormValues(category: MenuCategoryView | null, nextSortOrder = 0): MenuCategoryFormValues {
  return {
    name: category?.name ?? '',
    description: category?.description ?? '',
    sortOrder: numberToText(category?.sortOrder ?? nextSortOrder),
  }
}

export function categoryFormToRequest(values: MenuCategoryFormValues): MenuCategoryRequest {
  return { name: values.name.trim(), description: emptyToNull(values.description), sortOrder: Number(values.sortOrder.trim()) }
}

export const menuItemSchema = z.object({
  categoryId: z.string().min(1, 'Choose a category.'),
  name: textField({ required: true, requiredMessage: 'Enter the dish name.', max: 120 }),
  description: textField({ max: 500 }),
  price: decimalField({ required: true, requiredMessage: 'Enter the price in rupees.', min: 1, max: 100_000, decimals: 2 }),
  foodType: z.enum(['', 'VEG', 'NON_VEG', 'EGG']).refine((value) => value.length > 0, 'Choose veg, non-veg or egg.'),
  imageUrl: httpUrlField(500),
  available: z.boolean(),
  sortOrder: sortOrderField,
})

export type MenuItemFormValues = z.infer<typeof menuItemSchema>

export function itemToFormValues(item: MenuItemView | null, categoryId: string): MenuItemFormValues {
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

export function itemFormToRequest(values: MenuItemFormValues): MenuItemRequest {
  return {
    categoryId: values.categoryId,
    name: values.name.trim(),
    description: emptyToNull(values.description),
    price: Number(values.price.trim()),
    foodType: values.foodType as FoodType,
    imageUrl: emptyToNull(values.imageUrl),
    available: values.available,
    sortOrder: Number(values.sortOrder.trim()),
  }
}
