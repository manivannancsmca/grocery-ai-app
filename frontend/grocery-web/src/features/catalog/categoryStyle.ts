/**
 * Picture-free category styling: an icon kind picked from the category's name by keyword, and pastel tints that
 * change from tile to tile (full class names, so Tailwind sees them).
 */

export type IconKind =
  | 'produce'
  | 'dairy'
  | 'bakery'
  | 'grains'
  | 'pulses'
  | 'flour'
  | 'snacks'
  | 'beverages'
  | 'meat'
  | 'eggs'
  | 'spices'
  | 'oil'
  | 'frozen'
  | 'household'
  | 'care'
  | 'basket'

/** First match wins, so "Dairy & Bakery" is dairy and "Atta & Flours" is flour. */
const KEYWORDS: Array<[IconKind, RegExp]> = [
  ['produce', /vegetable|fruit|veggie|produce|greens|herb|organic|fresh/i],
  ['dairy', /dairy|milk|curd|paneer|cheese|butter|yogh?urt|cream/i],
  ['bakery', /bakery|bread|cake|bun|pastr/i],
  ['flour', /atta|flour|maida|besan|sooji|suji|semolina|rava/i],
  ['grains', /rice|grain|wheat|millet|cereal|oat|poha|staple/i],
  ['pulses', /pulse|dal|daal|lentil|bean|legume|chana|rajma|gram/i],
  ['snacks', /snack|namkeen|chip|biscuit|cookie|chocolate|sweet|candy|confection/i],
  ['beverages', /beverage|drink|juice|tea|coffee|water|soda|cola|sharbat/i],
  ['meat', /meat|chicken|mutton|fish|seafood|prawn/i],
  ['eggs', /\begg/i],
  ['spices', /spice|masala|salt|sugar|condiment|sauce|pickle|jam|honey|dry fruit|nut/i],
  ['oil', /oil|ghee/i],
  ['frozen', /frozen|ice cream|dessert/i],
  ['household', /household|clean|detergent|home|kitchen|laundry|dish/i],
  ['care', /personal|care|beauty|hygiene|bath|soap|baby|health/i],
]

/** The icon for a category name; a shopping basket when no keyword matches. */
export function iconKind(name: string): IconKind {
  return KEYWORDS.find(([, pattern]) => pattern.test(name))?.[0] ?? 'basket'
}

export interface CategoryTint {
  /** Tile background (gradient stops). */
  tile: string
  /** The round badge behind the icon. */
  badge: string
  /** Decorative blobs in the corners. */
  blob: string
  /** Hover border. */
  hover: string
}

const TINTS: readonly CategoryTint[] = [
  { tile: 'from-emerald-50 to-emerald-100/70', badge: 'bg-white ring-emerald-200/70', blob: 'bg-emerald-200/50', hover: 'hover:border-emerald-300' },
  { tile: 'from-sky-50 to-sky-100/70', badge: 'bg-white ring-sky-200/70', blob: 'bg-sky-200/50', hover: 'hover:border-sky-300' },
  { tile: 'from-amber-50 to-amber-100/70', badge: 'bg-white ring-amber-200/70', blob: 'bg-amber-200/50', hover: 'hover:border-amber-300' },
  { tile: 'from-rose-50 to-rose-100/70', badge: 'bg-white ring-rose-200/70', blob: 'bg-rose-200/50', hover: 'hover:border-rose-300' },
  { tile: 'from-lime-50 to-lime-100/70', badge: 'bg-white ring-lime-200/70', blob: 'bg-lime-200/50', hover: 'hover:border-lime-300' },
  { tile: 'from-violet-50 to-violet-100/70', badge: 'bg-white ring-violet-200/70', blob: 'bg-violet-200/50', hover: 'hover:border-violet-300' },
  { tile: 'from-orange-50 to-orange-100/70', badge: 'bg-white ring-orange-200/70', blob: 'bg-orange-200/50', hover: 'hover:border-orange-300' },
  { tile: 'from-teal-50 to-teal-100/70', badge: 'bg-white ring-teal-200/70', blob: 'bg-teal-200/50', hover: 'hover:border-teal-300' },
]

/** The tint for the tile at `index` (cycles, so neighbours differ). */
export function categoryTint(index: number): CategoryTint {
  return TINTS[((index % TINTS.length) + TINTS.length) % TINTS.length] as CategoryTint
}
