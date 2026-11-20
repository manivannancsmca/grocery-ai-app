/** Food-type marking of products and dishes (contract §2–§5). */
export const FOOD_TYPES = ['VEG', 'NON_VEG', 'EGG'] as const
export type FoodType = (typeof FOOD_TYPES)[number]

export const FOOD_TYPE_LABELS: Record<FoodType, string> = {
  VEG: 'Vegetarian',
  NON_VEG: 'Non-vegetarian',
  EGG: 'Contains egg',
}

/** Short labels for chips and selects. */
export const FOOD_TYPE_SHORT_LABELS: Record<FoodType, string> = {
  VEG: 'Veg',
  NON_VEG: 'Non-veg',
  EGG: 'Egg',
}

export function isFoodType(value: unknown): value is FoodType {
  return typeof value === 'string' && (FOOD_TYPES as readonly string[]).includes(value)
}
