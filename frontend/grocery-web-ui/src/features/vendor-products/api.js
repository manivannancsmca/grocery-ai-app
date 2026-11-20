import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'
import { PATH_SEPARATOR } from '../catalog/categoryTree'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../../shared/utils/foodType').FoodType} FoodType */
/** @typedef {import('../catalog/api').IdName} IdName */
/** @typedef {import('../catalog/api').ProductAttribute} ProductAttribute */
/** @typedef {import('../catalog/api').UnitOfMeasure} UnitOfMeasure */

/** Product lifecycle: DRAFT → PUBLISHED ⇄ UNPUBLISHED, any of them → ARCHIVED (final). */
export const PRODUCT_STATUSES = ['DRAFT', 'PUBLISHED', 'UNPUBLISHED', 'ARCHIVED']
/** @typedef {(typeof PRODUCT_STATUSES)[number]} ProductStatus */

/** @type {Record<ProductStatus, string>} */
export const PRODUCT_STATUS_LABELS = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  UNPUBLISHED: 'Unpublished',
  ARCHIVED: 'Archived',
}

/** @type {Record<ProductStatus, BadgeTone>} */
export const PRODUCT_STATUS_TONES = {
  DRAFT: 'gray',
  PUBLISHED: 'green',
  UNPUBLISHED: 'amber',
  ARCHIVED: 'violet',
}

/**
 * @param {unknown} value
 * @returns {value is ProductStatus}
 */
export function isProductStatus(value) {
  return typeof value === 'string' && PRODUCT_STATUSES.includes(value)
}

/**
 * `ProductRequest` (contract §3).
 * @typedef {object} ProductRequest
 * @property {string} name
 * @property {string | null} description
 * @property {string} categoryId
 * @property {string | null} brandName
 * @property {number} unitValue
 * @property {UnitOfMeasure} unitOfMeasure
 * @property {string | null} sku
 * @property {string | null} barcode
 * @property {FoodType | null} foodType
 * @property {string[]} tags
 * @property {ProductAttribute[]} attributes
 * @property {string[]} imageUrls First = primary image.
 */

/**
 * @typedef {object} ProductCategoryRef
 * @property {string} id
 * @property {string} name
 * @property {string[]} path Names root → leaf.
 * @property {boolean} active
 */

/**
 * "Fruits & Vegetables › Fresh Vegetables", or a placeholder when the category no longer exists.
 * @param {ProductCategoryRef | null} category
 * @returns {string}
 */
export function categoryLabel(category) {
  if (!category) return 'Category removed'
  return category.path.join(PATH_SEPARATOR) || category.name
}

/**
 * `ProductView` — the admin view adds `vendorName`.
 * @typedef {object} ProductView
 * @property {string} id
 * @property {string} vendorId
 * @property {string} name
 * @property {string | null} description
 * @property {ProductCategoryRef | null} category null only for an archived product whose category was deleted afterwards.
 * @property {IdName | null} brand
 * @property {number} unitValue
 * @property {UnitOfMeasure} unitOfMeasure
 * @property {string} unitLabel
 * @property {string | null} sku
 * @property {string | null} barcode
 * @property {FoodType | null} foodType
 * @property {string[]} tags
 * @property {ProductAttribute[]} attributes
 * @property {string[]} imageUrls
 * @property {ProductStatus} status
 * @property {string | null} publishedAt
 * @property {string} createdAt
 * @property {string} updatedAt
 * @property {string} [vendorName]
 */

/**
 * @typedef {object} VendorProductListParams
 * @property {ProductStatus} [status] Archived products are listed only with `status=ARCHIVED`.
 * @property {string} [q]
 * @property {number} [page]
 * @property {number} [size]
 */

/** @typedef {'publish' | 'unpublish' | 'archive'} ProductStatusAction */

/**
 * The product page in the vendor workspace (edit, or view when archived).
 * @param {Pick<ProductView, 'id'>} product
 */
export const vendorProductPath = (product) => `/vendor/products/${encodeURIComponent(product.id)}`

export const VENDOR_NOT_ACTIVE = 'VENDOR_NOT_ACTIVE'
export const PRODUCT_NOT_FOUND = 'PRODUCT_NOT_FOUND'

export const vendorProductKeys = {
  all: ['vendor', 'products'],
  list: (params) => [...vendorProductKeys.all, 'list', params],
  detail: (id) => [...vendorProductKeys.all, 'detail', id],
  brands: (q) => ['vendor', 'brands', q],
}

const base = '/vendor/products'
/** @param {string} id */
const item = (id) => `${base}/${segment(id)}`

/**
 * @param {VendorProductListParams} params
 * @returns {Promise<PageResponse<ProductView>>}
 */
export async function listVendorProducts(params) {
  return (await httpClient.get(base, { params: cleanParams(params) })).data
}

/**
 * @param {string} id
 * @returns {Promise<ProductView>}
 */
export async function fetchVendorProduct(id) {
  return (await httpClient.get(item(id))).data
}

/**
 * @param {ProductRequest} body
 * @returns {Promise<ProductView>}
 */
export async function createVendorProduct(body) {
  return (await httpClient.post(base, body)).data
}

/**
 * @param {string} id
 * @param {ProductRequest} body
 * @returns {Promise<ProductView>}
 */
export async function updateVendorProduct(id, body) {
  return (await httpClient.put(item(id), body)).data
}

/**
 * @param {string} id
 * @returns {Promise<ProductView>}
 */
export async function publishVendorProduct(id) {
  return (await httpClient.post(`${item(id)}/publish`)).data
}

/**
 * @param {string} id
 * @returns {Promise<ProductView>}
 */
export async function unpublishVendorProduct(id) {
  return (await httpClient.post(`${item(id)}/unpublish`)).data
}

/**
 * `DELETE` archives the product (hidden everywhere, kept for order history).
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function archiveVendorProduct(id) {
  await httpClient.delete(item(id))
}

/**
 * ≤ 10 existing brands matching `q`, for the brand field's suggestions.
 * @param {string} q
 * @returns {Promise<IdName[]>}
 */
export async function fetchBrandSuggestions(q) {
  return (await httpClient.get('/vendor/brands', { params: cleanParams({ q }) })).data
}
