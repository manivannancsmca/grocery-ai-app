import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../../shared/utils/foodType').FoodType} FoodType */

/**
 * Public catalog reads served by catalog-query (Phase 3 contract §2). No token is needed, so the calls
 * skip the auth interceptor: an expired token can never turn a public page into a 401 / refresh loop.
 */
const PUBLIC = { skipAuth: true }

/**
 * @typedef {object} IdName
 * @property {string} id
 * @property {string} name
 */

export const UNITS_OF_MEASURE = ['G', 'KG', 'ML', 'L', 'PCS', 'PACK']
/** @typedef {(typeof UNITS_OF_MEASURE)[number]} UnitOfMeasure */

/**
 * Price of a search document (Phase 4 contract §6): selling price, MRP and the discount on it.
 * @typedef {object} ProductPrice
 * @property {number} mrp
 * @property {number} sellingPrice
 * @property {string} currency
 * @property {number} discountPercent `round((mrp − sellingPrice) / mrp × 100)`, 0–99.
 */

/**
 * `ProductCard` — one search hit.
 * @typedef {object} ProductCard
 * @property {string} id
 * @property {string} name
 * @property {string} unitLabel
 * @property {FoodType | null} foodType
 * @property {string | null} primaryImageUrl
 * @property {IdName | null} brand
 * @property {IdName} category
 * @property {IdName} vendor
 * @property {ProductPrice | null} [price] Phase 4 (pricing-service). null = not priced yet ("Price not available").
 * @property {boolean | null} [inStock] Phase 4 (inventory-service). false until stock is added ("Out of stock"); the product stays visible.
 * @property {number | null} [rating] Phase 12 (review-service's RatingSummaryUpdated): average of the published ratings, absent / null when unrated.
 * @property {number | null} [ratingCount] Phase 12: number of published ratings (0 / absent when unrated).
 */

/**
 * @typedef {object} ProductAttribute
 * @property {string} name
 * @property {string} value
 */

/**
 * `ProductDetail` = ProductCard + description, unit, codes, tags, attributes, images and the category path.
 * @typedef {ProductCard & { description: string | null, unitValue: number, unitOfMeasure: UnitOfMeasure, sku: string | null, barcode: string | null, tags: string[], attributes: ProductAttribute[], imageUrls: string[], categoryPath: IdName[], publishedAt: string | null, updatedAt: string }} ProductDetail
 * - `categoryPath`: Root → leaf.
 */

/**
 * @typedef {object} CountFacet
 * @property {string} id
 * @property {string} name
 * @property {number} count
 */

/**
 * @typedef {object} FoodTypeFacet
 * @property {FoodType} value
 * @property {number} count
 */

/** @typedef {PageResponse<ProductCard> & { facets: { categories: CountFacet[]; brands: CountFacet[]; foodTypes: FoodTypeFacet[]; } }} ProductSearchResponse */

/** `price_asc` / `price_desc` (Phase 4) list products without a price last; `rating` (Phase 12) is highest first, unrated last. */
export const PRODUCT_SORTS = ['relevance', 'newest', 'name_asc', 'price_asc', 'price_desc', 'rating']
/** @typedef {(typeof PRODUCT_SORTS)[number]} ProductSort */

/** @type {Record<ProductSort, string>} */
export const PRODUCT_SORT_LABELS = {
  relevance: 'Relevance',
  newest: 'Newest first',
  name_asc: 'Name (A–Z)',
  price_asc: 'Price: low to high',
  price_desc: 'Price: high to low',
  rating: 'Customer rating',
}

/**
 * @typedef {object} ProductSearchParams
 * @property {string} [q]
 * @property {string} [categoryId]
 * @property {string} [brandId]
 * @property {string | string[]} [vendorId] One store, or several (the stores that deliver to the shopper; at most 50, sent as `vendorId=a&vendorId=b`).
 * @property {FoodType} [foodType]
 * @property {true} [inStock] Phase 4: only products in stock (`true`; never sent as `false`).
 * @property {number} [minPrice] Phase 4: selling price range, ≥ 0 and `minPrice ≤ maxPrice`; products without a price never match.
 * @property {number} [maxPrice]
 * @property {ProductSort} [sort] `relevance` is the default with `q`, otherwise `newest`.
 * @property {number} [page]
 * @property {number} [size] Default 20, max 50.
 */

/**
 * @typedef {object} ProductSuggestion
 * @property {string} id
 * @property {string} name
 * @property {string | null} brandName
 * @property {string} unitLabel
 * @property {string | null} primaryImageUrl
 */

/**
 * @typedef {object} CategorySuggestion
 * @property {string} id
 * @property {string} name
 * @property {string[]} path Names root → this category.
 */

/**
 * ≤ 8 products and ≤ 4 categories.
 * @typedef {object} SuggestResponse
 * @property {ProductSuggestion[]} products
 * @property {CategorySuggestion[]} categories
 */

/**
 * Active categories as a tree (sorted by sortOrder, then name), with visible product counts.
 * @typedef {object} CategoryNode
 * @property {string} id
 * @property {string} name
 * @property {string | null} description
 * @property {string | null} imageUrl
 * @property {number} productCount
 * @property {CategoryNode[]} children
 */

/** Suggestions need at least this many characters. */
export const MIN_SUGGEST_LENGTH = 2
export const SEARCH_PAGE_SIZE = 24
/** `page × size` may not exceed this (400 INVALID_PARAMETER beyond). */
export const MAX_SEARCH_RESULTS = 1_000
export const PRODUCT_NOT_FOUND = 'PRODUCT_NOT_FOUND'

export const catalogKeys = {
  all: ['catalog'],
  /** Every product search (search page, storefront rails, store pages) — the prefix of `search(params)`. */
  searches: () => [...catalogKeys.all, 'search'],
  search: (params) => [...catalogKeys.searches(), params],
  suggest: (q) => [...catalogKeys.all, 'suggest', q],
  categories: () => [...catalogKeys.all, 'categories'],
  product: (id) => [...catalogKeys.all, 'product', id],
}

/**
 * @param {ProductSearchParams} params
 * @returns {Promise<ProductSearchResponse>}
 */
export async function searchProducts(params) {
  const { vendorId, ...rest } = params
  // An empty list would mean "no filter" to the API, so it is dropped like any other empty value.
  const vendorIds =
    vendorId === undefined
      ? undefined
      : Array.isArray(vendorId)
        ? vendorId.length > 0
          ? vendorId
          : undefined
        : vendorId
  return (
    await httpClient.get('/search/products', {
      ...PUBLIC,
      params: cleanParams({ ...rest, vendorId: vendorIds }),
      // Repeated keys (`vendorId=a&vendorId=b`), which Spring binds to a list; axios' default adds `[]`.
      paramsSerializer: { indexes: null },
    })
  ).data
}

/**
 * @param {string} q
 * @param {AbortSignal} [signal]
 * @returns {Promise<SuggestResponse>}
 */
export async function fetchSuggestions(q, signal) {
  return (await httpClient.get('/search/suggest', { ...PUBLIC, params: { q }, signal })).data
}

/** @returns {Promise<CategoryNode[]>} */
export async function fetchCategoryTree() {
  return (await httpClient.get('/catalog/categories', PUBLIC)).data
}

/**
 * @param {string} id
 * @returns {Promise<ProductDetail>}
 */
export async function fetchProduct(id) {
  return (await httpClient.get(`/catalog/products/${segment(id)}`, PUBLIC)).data
}
