import type { IconName } from '../../shared/icons'
import type { MenuItemView } from './api'

/** The owner's "show" filter above the dishes. */
export type AvailabilityFilter = 'ALL' | 'AVAILABLE' | 'SOLD_OUT'

/** True when the dish matches the search text (name or description) and the availability filter. */
export function dishMatches(item: MenuItemView, query: string, availability: AvailabilityFilter): boolean {
  if (availability === 'AVAILABLE' && !item.available) return false
  if (availability === 'SOLD_OUT' && item.available) return false
  const text = query.trim().toLowerCase()
  if (!text) return true
  return item.name.toLowerCase().includes(text) || (item.description ?? '').toLowerCase().includes(text)
}

export function countSoldOut(items: readonly MenuItemView[]): number {
  return items.filter((item) => !item.available).length
}

/** "1 dish", "4 dishes". */
export function dishesLabel(count: number): string {
  return `${count} dish${count === 1 ? '' : 'es'}`
}

/**
 * Words in a category or dish name and the picture shown for them. The first match wins, so the more specific
 * words come first ("Egg Kothu Parotta" is an egg dish, "Masala Dosa" a tiffin item, not a curry).
 */
const NAME_ICONS: ReadonlyArray<readonly [RegExp, IconName]> = [
  [/coffee|\btea\b|chai/, 'coffee'],
  [/milk|lassi|shake|smoothie/, 'milk'],
  [/juice|soda|drink|beverage|lime|mojito/, 'juice'],
  [/ice ?cream|kulfi|sundae/, 'iceCream'],
  [/sweet|dessert|kesari|halwa|payasam|jamun|cake|ladoo|laddu/, 'cupcake'],
  [/noodle|chinese|hakka|chow/, 'noodles'],
  [/pizza/, 'pizza'],
  [/burger|sandwich|wrap|\broll/, 'burger'],
  [/salad/, 'salad'],
  [/egg|omelet/, 'egg'],
  [/fish|prawn|crab|seafood/, 'fish'],
  [/dosa|idli|vada|uttapam|appam|upma|tiffin|breakfast/, 'cooking'],
  [/parotta|paratha|naan|roti|chapat|kulcha|bread/, 'bread'],
  [/biryani|rice|meal|thali|pongal|pulao/, 'thali'],
  [/curry|gravy|masala|kurma|korma|sambar|\bdal/, 'curry'],
  [/starter|snack|chaat|tikka|kebab|spicy/, 'chilli'],
]

/** A picture for a category or a dish without a photo, from words in its name (a plate and cutlery otherwise). */
export function menuIconFor(name: string): IconName {
  const text = name.toLowerCase()
  return NAME_ICONS.find(([pattern]) => pattern.test(text))?.[1] ?? 'cutlery'
}
