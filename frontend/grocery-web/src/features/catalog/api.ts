import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { FoodType } from '../../shared/utils/foodType'

/**
 * Public catalog reads served by catalog-query (Phase 3 contract §2). No token is needed, so the calls
 * skip the auth interceptor: an expired token can never turn a public page into a 401 / refresh loop.
 */
const PUBLIC = { skipAuth: true } as const

export interface IdName {
  id: string
  name: string
}

export const UNITS_OF_MEASURE = ['G', 'KG', 'ML', 'L', 'PCS', 'PACK'] as const
export type UnitOfMeasure = (typeof UNITS_OF_MEASURE)[number]

/** Price of a search document (Phase 4 contract §6): selling price, MRP and the discount on it. */
export interface ProductPrice {
  mrp: number
  sellingPrice: number
  currency: string
  /** `round((mrp − sellingPrice) / mrp × 100)`, 0–99. */
  discountPercent: number
}

/** `ProductCard` — one search hit. */
export interface ProductCard {
  id: string
  name: string
  unitLabel: string
  foodType: FoodType | null
  primaryImageUrl: string | null
  brand: IdName | null
  category: IdName
  vendor: IdName
  /** Phase 4 (pricing-service). null = not priced yet ("Price not available"). */
  price?: ProductPrice | null
  /** Phase 4 (inventory-service). false until stock is added ("Out of stock"); the product stays visible. */
  inStock?: boolean | null
  /** Phase 12 (review-service's RatingSummaryUpdated): average of the published ratings, absent / null when unrated. */
  rating?: number | null
  /** Phase 12: number of published ratings (0 / absent when unrated). */
  ratingCount?: number | null
  /** The description, at most 300 characters (cut at a word, ending in "…"); null when there is none. */
  shortDescription?: string | null
}

export interface ProductAttribute {
  name: string
  value: string
}

/** `ProductDetail` = ProductCard + description, unit, codes, tags, attributes, images and the category path. */
export interface ProductDetail extends ProductCard {
  description: string | null
  unitValue: number
  unitOfMeasure: UnitOfMeasure
  sku: string | null
  barcode: string | null
  tags: string[]
  attributes: ProductAttribute[]
  imageUrls: string[]
  /** Root → leaf. */
  categoryPath: IdName[]
  publishedAt: string | null
  updatedAt: string
}

export interface CountFacet {
  id: string
  name: string
  count: number
}

export interface FoodTypeFacet {
  value: FoodType
  count: number
}

export interface ProductSearchResponse extends PageResponse<ProductCard> {
  facets: {
    /** Leaf categories of the hits. */
    categories: CountFacet[]
    brands: CountFacet[]
    foodTypes: FoodTypeFacet[]
  }
}

/** `price_asc` / `price_desc` (Phase 4) list products without a price last; `rating` (Phase 12) is highest first, unrated last. */
export const PRODUCT_SORTS = ['relevance', 'newest', 'name_asc', 'price_asc', 'price_desc', 'rating'] as const
export type ProductSort = (typeof PRODUCT_SORTS)[number]

export const PRODUCT_SORT_LABELS: Record<ProductSort, string> = {
  relevance: 'Relevance',
  newest: 'Newest first',
  name_asc: 'Name (A–Z)',
  price_asc: 'Price: low to high',
  price_desc: 'Price: high to low',
  rating: 'Customer rating',
}

export interface ProductSearchParams {
  q?: string
  categoryId?: string
  brandId?: string
  /** One store, or several (the stores that deliver to the shopper; at most 50, sent as `vendorId=a&vendorId=b`). */
  vendorId?: string | string[]
  foodType?: FoodType
  /** Phase 4: only products in stock (`true`; never sent as `false`). */
  inStock?: true
  /** Phase 4: selling price range, ≥ 0 and `minPrice ≤ maxPrice`; products without a price never match. */
  minPrice?: number
  maxPrice?: number
  /** `relevance` is the default with `q`, otherwise `newest`. */
  sort?: ProductSort
  page?: number
  /** Default 20, max 50. */
  size?: number
}

export interface ProductSuggestion {
  id: string
  name: string
  brandName: string | null
  unitLabel: string
  primaryImageUrl: string | null
}

export interface CategorySuggestion {
  id: string
  name: string
  /** Names root → this category. */
  path: string[]
}

/** ≤ 8 products and ≤ 4 categories. */
export interface SuggestResponse {
  products: ProductSuggestion[]
  categories: CategorySuggestion[]
}

/** Active categories as a tree (sorted by sortOrder, then name), with visible product counts. */
export interface CategoryNode {
  id: string
  name: string
  description: string | null
  imageUrl: string | null
  productCount: number
  children: CategoryNode[]
}

/** Suggestions need at least this many characters. */
export const MIN_SUGGEST_LENGTH = 2
export const SEARCH_PAGE_SIZE = 24
/** `page × size` may not exceed this (400 INVALID_PARAMETER beyond). */
export const MAX_SEARCH_RESULTS = 1_000
export const PRODUCT_NOT_FOUND = 'PRODUCT_NOT_FOUND'

export const catalogKeys = {
  all: ['catalog'] as const,
  /** Every product search (search page, storefront rails, store pages) — the prefix of `search(params)`. */
  searches: () => [...catalogKeys.all, 'search'] as const,
  search: (params: ProductSearchParams) => [...catalogKeys.searches(), params] as const,
  suggest: (q: string) => [...catalogKeys.all, 'suggest', q] as const,
  categories: () => [...catalogKeys.all, 'categories'] as const,
  product: (id: string) => [...catalogKeys.all, 'product', id] as const,
}

export async function searchProducts(params: ProductSearchParams): Promise<ProductSearchResponse> {
  const { vendorId, ...rest } = params
  // An empty list would mean "no filter" to the API, so it is dropped like any other empty value.
  const vendorIds = vendorId === undefined ? undefined : Array.isArray(vendorId) ? (vendorId.length > 0 ? vendorId : undefined) : vendorId
  return (
    await httpClient.get<ProductSearchResponse>('/search/products', {
      ...PUBLIC,
      params: cleanParams({ ...rest, vendorId: vendorIds }),
      // Repeated keys (`vendorId=a&vendorId=b`), which Spring binds to a list; axios' default adds `[]`.
      paramsSerializer: { indexes: null },
    })
  ).data
}

export async function fetchSuggestions(q: string, signal?: AbortSignal): Promise<SuggestResponse> {
  return (await httpClient.get<SuggestResponse>('/search/suggest', { ...PUBLIC, params: { q }, signal })).data
}

export async function fetchCategoryTree(): Promise<CategoryNode[]> {
  return (await httpClient.get<CategoryNode[]>('/catalog/categories', PUBLIC)).data
}

export async function fetchProduct(id: string): Promise<ProductDetail> {
  return (await httpClient.get<ProductDetail>(`/catalog/products/${segment(id)}`, PUBLIC)).data
}
