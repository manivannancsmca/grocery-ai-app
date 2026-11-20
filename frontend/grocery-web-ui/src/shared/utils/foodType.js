/** Food-type marking of products and dishes (contract §2–§5). */
export const FOOD_TYPES = ['VEG', 'NON_VEG', 'EGG']
/** @typedef {(typeof FOOD_TYPES)[number]} FoodType */

/** @type {Record<FoodType, string>} */
export const FOOD_TYPE_LABELS = {
  VEG: 'Vegetarian',
  NON_VEG: 'Non-vegetarian',
  EGG: 'Contains egg',
}

/**
 * Short labels for chips and selects.
 * @type {Record<FoodType, string>}
 */
export const FOOD_TYPE_SHORT_LABELS = {
  VEG: 'Veg',
  NON_VEG: 'Non-veg',
  EGG: 'Egg',
}

/**
 * @param {unknown} value
 * @returns {value is FoodType}
 */
export function isFoodType(value) {
  return typeof value === 'string' && FOOD_TYPES.includes(value)
}
