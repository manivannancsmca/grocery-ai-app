import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { FoodType } from '../../shared/utils/foodType'
import type { OpeningHours } from '../restaurant/api'

/** Public restaurant reads (catalog-query, Phase 3 contract §2). No token needed. */
const PUBLIC = { skipAuth: true } as const

export interface MatchingItem {
  id: string
  name: string
  price: number
  foodType: FoodType
}

/** `RestaurantCard` — one restaurant search hit. */
export interface RestaurantCard {
  id: string
  name: string
  description: string | null
  cuisines: string[]
  city: string | null
  /** Address line 2 when present. */
  locality: string | null
  /** Null when no location was given. */
  distanceKm: number | null
  openNow: boolean
  acceptingOrders: boolean
  pureVeg: boolean
  /** ≤ 3 dishes matching `q` (only with `q`). */
  matchingItems: MatchingItem[]
  /** Phase 12: average of the published ratings, absent / null when unrated. */
  rating?: number | null
  /** Phase 12: number of published ratings. */
  ratingCount?: number | null
}

export interface RestaurantSearchResponse extends PageResponse<RestaurantCard> {
  facets: { cuisines: Array<{ value: string; count: number }> }
}

export interface RestaurantSearchParams {
  /** Both or neither. */
  lat?: number
  lng?: number
  /** Default 10, max 30; only with a location. */
  radiusKm?: number
  q?: string
  cuisine?: string[]
  pureVeg?: boolean
  openNow?: boolean
  /** Phase 12: `rating` = highest rated first (unrated last); absent = the default order. */
  sort?: RestaurantSort
  /** Phase 12: only restaurants rated at least this (1–5). */
  minRating?: number
  page?: number
  size?: number
}

export type RestaurantSort = 'rating'
/** The "Rated 4.0+" filter. */
export const TOP_RATED_MIN = 4

export interface PublicMenuItem {
  id: string
  name: string
  description: string | null
  price: number
  foodType: FoodType
  available: boolean
  imageUrl: string | null
}

export interface PublicMenuCategory {
  id: string
  name: string
  description: string | null
  /** Unavailable items are included with `available: false`. */
  items: PublicMenuItem[]
}

export interface RestaurantAddress {
  line1: string | null
  line2: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  latitude: number | null
  longitude: number | null
}

/** `RestaurantDetail` — the public restaurant page (menu categories without items are omitted). */
export interface RestaurantDetail {
  id: string
  name: string
  description: string | null
  cuisines: string[]
  openNow: boolean
  acceptingOrders: boolean
  pureVeg: boolean
  address: RestaurantAddress | null
  openingHours: OpeningHours[]
  menu: PublicMenuCategory[]
  /** Phase 12: average of the published ratings, absent / null when unrated. */
  rating?: number | null
  ratingCount?: number | null
}

export const RESTAURANT_NOT_FOUND = 'RESTAURANT_NOT_FOUND'
export const RESTAURANT_PAGE_SIZE = 20
export const DEFAULT_RADIUS_KM = 10
export const RADIUS_OPTIONS_KM = [3, 5, 10, 20, 30] as const

export const discoveryKeys = {
  all: ['restaurants', 'public'] as const,
  /** Every restaurant search — the prefix of `search(params)`. */
  searches: () => [...discoveryKeys.all, 'search'] as const,
  search: (params: RestaurantSearchParams) => [...discoveryKeys.searches(), params] as const,
  detail: (id: string) => [...discoveryKeys.all, 'detail', id] as const,
}

export async function searchRestaurants(params: RestaurantSearchParams): Promise<RestaurantSearchResponse> {
  const query = cleanParams({
    ...params,
    // Flags are sent only when on; `cuisine` repeats (`cuisine=A&cuisine=B`).
    pureVeg: params.pureVeg ? true : undefined,
    openNow: params.openNow ? true : undefined,
    cuisine: params.cuisine && params.cuisine.length > 0 ? params.cuisine : undefined,
    radiusKm: params.lat !== undefined && params.lng !== undefined ? params.radiusKm : undefined,
  })
  const response = await httpClient.get<RestaurantSearchResponse>('/search/restaurants', {
    ...PUBLIC,
    params: query,
    paramsSerializer: { indexes: null },
  })
  return response.data
}

export async function fetchRestaurantDetail(id: string): Promise<RestaurantDetail> {
  return (await httpClient.get<RestaurantDetail>(`/catalog/restaurants/${segment(id)}`, PUBLIC)).data
}
