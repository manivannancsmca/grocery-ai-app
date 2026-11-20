import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'
/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../../shared/utils/foodType').FoodType} FoodType */
/** @typedef {import('../restaurant/api').OpeningHours} OpeningHours */

/** Public restaurant reads (catalog-query, Phase 3 contract §2). No token needed. */
const PUBLIC = { skipAuth: true }

/**
 * @typedef {object} MatchingItem
 * @property {string} id
 * @property {string} name
 * @property {number} price
 * @property {FoodType} foodType
 */

/**
 * `RestaurantCard` — one restaurant search hit.
 * @typedef {object} RestaurantCard
 * @property {string} id
 * @property {string} name
 * @property {string | null} description
 * @property {string[]} cuisines
 * @property {string | null} city
 * @property {string | null} locality Address line 2 when present.
 * @property {number | null} distanceKm Null when no location was given.
 * @property {boolean} openNow
 * @property {boolean} acceptingOrders
 * @property {boolean} pureVeg
 * @property {MatchingItem[]} matchingItems ≤ 3 dishes matching `q` (only with `q`).
 * @property {number | null} [rating] Phase 12: average of the published ratings, absent / null when unrated.
 * @property {number | null} [ratingCount] Phase 12: number of published ratings.
 */

/** @typedef {PageResponse<RestaurantCard> & { facets: { cuisines: Array<{ value: string; count: number; }>; } }} RestaurantSearchResponse */

/**
 * @typedef {object} RestaurantSearchParams
 * @property {number} [lat] Both or neither.
 * @property {number} [lng]
 * @property {number} [radiusKm] Default 10, max 30; only with a location.
 * @property {string} [q]
 * @property {string[]} [cuisine]
 * @property {boolean} [pureVeg]
 * @property {boolean} [openNow]
 * @property {RestaurantSort} [sort] Phase 12: `rating` = highest rated first (unrated last); absent = the default order.
 * @property {number} [minRating] Phase 12: only restaurants rated at least this (1–5).
 * @property {number} [page]
 * @property {number} [size]
 */

/** @typedef {'rating'} RestaurantSort */
/** The "Rated 4.0+" filter. */
export const TOP_RATED_MIN = 4

/**
 * @typedef {object} PublicMenuItem
 * @property {string} id
 * @property {string} name
 * @property {string | null} description
 * @property {number} price
 * @property {FoodType} foodType
 * @property {boolean} available
 * @property {string | null} imageUrl
 */

/**
 * @typedef {object} PublicMenuCategory
 * @property {string} id
 * @property {string} name
 * @property {string | null} description
 * @property {PublicMenuItem[]} items Unavailable items are included with `available: false`.
 */

/**
 * @typedef {object} RestaurantAddress
 * @property {string | null} line1
 * @property {string | null} line2
 * @property {string | null} city
 * @property {string | null} state
 * @property {string | null} postalCode
 * @property {number | null} latitude
 * @property {number | null} longitude
 */

/**
 * `RestaurantDetail` — the public restaurant page (menu categories without items are omitted).
 * @typedef {object} RestaurantDetail
 * @property {string} id
 * @property {string} name
 * @property {string | null} description
 * @property {string[]} cuisines
 * @property {boolean} openNow
 * @property {boolean} acceptingOrders
 * @property {boolean} pureVeg
 * @property {RestaurantAddress | null} address
 * @property {OpeningHours[]} openingHours
 * @property {PublicMenuCategory[]} menu
 * @property {number | null} [rating] Phase 12: average of the published ratings, absent / null when unrated.
 * @property {number | null} [ratingCount]
 */

export const RESTAURANT_NOT_FOUND = 'RESTAURANT_NOT_FOUND'
export const RESTAURANT_PAGE_SIZE = 20
export const DEFAULT_RADIUS_KM = 10
export const RADIUS_OPTIONS_KM = [3, 5, 10, 20, 30]

export const discoveryKeys = {
  all: ['restaurants', 'public'],
  /** Every restaurant search — the prefix of `search(params)`. */
  searches: () => [...discoveryKeys.all, 'search'],
  search: (params) => [...discoveryKeys.searches(), params],
  detail: (id) => [...discoveryKeys.all, 'detail', id],
}

/**
 * @param {RestaurantSearchParams} params
 * @returns {Promise<RestaurantSearchResponse>}
 */
export async function searchRestaurants(params) {
  const query = cleanParams({
    ...params,
    // Flags are sent only when on; `cuisine` repeats (`cuisine=A&cuisine=B`).
    pureVeg: params.pureVeg ? true : undefined,
    openNow: params.openNow ? true : undefined,
    cuisine: params.cuisine && params.cuisine.length > 0 ? params.cuisine : undefined,
    radiusKm: params.lat !== undefined && params.lng !== undefined ? params.radiusKm : undefined,
  })
  const response = await httpClient.get('/search/restaurants', {
    ...PUBLIC,
    params: query,
    paramsSerializer: { indexes: null },
  })
  return response.data
}

/**
 * @param {string} id
 * @returns {Promise<RestaurantDetail>}
 */
export async function fetchRestaurantDetail(id) {
  return (await httpClient.get(`/catalog/restaurants/${segment(id)}`, PUBLIC)).data
}
