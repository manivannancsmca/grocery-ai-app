/** @typedef {import('../../shared/icons').IconName} IconName */

/**
 * Restaurants have no photos yet, so every card and banner gets a tinted gradient "cover" chosen from its cuisines
 * (or, failing that, steadily from its id) with a small cuisine icon.
 */

/**
 * @typedef {object} CuisineTheme
 * @property {string} cover Gradient classes of the cover (`bg-gradient-to-br` is added by the cover).
 * @property {string} chip Soft chip (background + text) in the same hue.
 * @property {IconName} icon The cuisine picture (on a white disc on covers, inside filter chips).
 */

const THEMES = {
  curry: { cover: 'from-amber-200 via-orange-300 to-rose-300', chip: 'bg-orange-50 text-orange-700', icon: 'curry' },
  south: { cover: 'from-lime-200 via-yellow-200 to-amber-300', chip: 'bg-lime-50 text-lime-800', icon: 'thali' },
  chinese: { cover: 'from-rose-200 via-red-300 to-orange-300', chip: 'bg-red-50 text-red-700', icon: 'noodles' },
  pizza: { cover: 'from-yellow-200 via-orange-300 to-red-300', chip: 'bg-orange-50 text-orange-700', icon: 'pizza' },
  burger: { cover: 'from-amber-100 via-amber-300 to-orange-300', chip: 'bg-amber-50 text-amber-800', icon: 'burger' },
  cafe: { cover: 'from-stone-200 via-amber-200 to-orange-200', chip: 'bg-stone-100 text-stone-700', icon: 'coffee' },
  dessert: { cover: 'from-pink-200 via-rose-200 to-fuchsia-300', chip: 'bg-pink-50 text-pink-700', icon: 'cupcake' },
  healthy: {
    cover: 'from-emerald-200 via-teal-200 to-lime-200',
    chip: 'bg-emerald-50 text-emerald-700',
    icon: 'salad',
  },
  indigo: { cover: 'from-sky-200 via-indigo-200 to-violet-300', chip: 'bg-indigo-50 text-indigo-700', icon: 'cloche' },
  teal: { cover: 'from-teal-200 via-cyan-200 to-sky-300', chip: 'bg-teal-50 text-teal-700', icon: 'cloche' },
  peach: { cover: 'from-orange-100 via-amber-200 to-rose-200', chip: 'bg-rose-50 text-rose-700', icon: 'cloche' },
}

/** @typedef {keyof typeof THEMES} ThemeName */

/** @type {Record<string, ThemeName>} */
const CUISINE_THEMES = {
  NORTH_INDIAN: 'curry',
  BIRYANI: 'curry',
  MUGHLAI: 'curry',
  KEBAB: 'curry',
  STREET_FOOD: 'curry',
  SOUTH_INDIAN: 'south',
  CHINESE: 'chinese',
  ASIAN: 'chinese',
  PIZZA: 'pizza',
  ITALIAN: 'pizza',
  BURGERS: 'burger',
  BURGER: 'burger',
  FAST_FOOD: 'burger',
  CONTINENTAL: 'burger',
  AMERICAN: 'burger',
  CAFE: 'cafe',
  BEVERAGES: 'cafe',
  BAKERY: 'dessert',
  DESSERTS: 'dessert',
  ICE_CREAM: 'dessert',
  HEALTHY: 'healthy',
  SALADS: 'healthy',
}

/** @type {ThemeName[]} */
const FALLBACK_THEMES = ['indigo', 'teal', 'peach']

/**
 * @param {string} value
 * @returns {number}
 */
function hash(value) {
  let result = 0
  for (const char of value) result = (result * 31 + char.charCodeAt(0)) >>> 0
  return result
}

/**
 * The theme of the first recognised cuisine; otherwise one picked steadily from the seed (the restaurant id).
 * @param {readonly string[]} cuisines
 * @param {string} seed
 * @returns {CuisineTheme}
 */
export function cuisineTheme(cuisines, seed) {
  const match = cuisines.map((cuisine) => CUISINE_THEMES[cuisine]).find(Boolean)
  const name = match ?? FALLBACK_THEMES[hash(seed) % FALLBACK_THEMES.length] ?? 'indigo'
  return THEMES[name]
}

/**
 * The icon of one cuisine (cuisine filter chips).
 * @param {string} cuisine
 * @returns {IconName}
 */
export function cuisineIcon(cuisine) {
  const name = CUISINE_THEMES[cuisine]
  return name ? THEMES[name].icon : 'cloche'
}
