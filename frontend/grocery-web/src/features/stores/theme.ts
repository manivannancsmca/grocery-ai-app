import type { CuisineTheme } from '../restaurant-discovery/theme'

/** Stores have no photos yet: a fresh-produce gradient cover, picked steadily from the store's id. */
const STORE_THEMES: readonly CuisineTheme[] = [
  { cover: 'from-emerald-200 via-lime-200 to-amber-200', chip: 'bg-emerald-50 text-emerald-700', icon: 'basket' },
  { cover: 'from-teal-200 via-emerald-200 to-lime-200', chip: 'bg-teal-50 text-teal-700', icon: 'store' },
  { cover: 'from-lime-200 via-yellow-200 to-orange-200', chip: 'bg-lime-50 text-lime-800', icon: 'apple' },
  { cover: 'from-sky-200 via-teal-200 to-emerald-200', chip: 'bg-sky-50 text-sky-700', icon: 'bag' },
]

export function storeTheme(id: string): CuisineTheme {
  let hash = 0
  for (let index = 0; index < id.length; index++) hash = (hash * 31 + id.charCodeAt(index)) | 0
  return STORE_THEMES[Math.abs(hash) % STORE_THEMES.length] as CuisineTheme
}
