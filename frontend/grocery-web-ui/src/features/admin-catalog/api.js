import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../vendor-products/api').ProductStatus} ProductStatus */
/** @typedef {import('../vendor-products/api').ProductView} ProductView */

/**
 * `AdminCategoryNode` — the whole tree including inactive categories (Phase 3 contract §4).
 * @typedef {object} AdminCategoryNode
 * @property {string} id
 * @property {string | null} parentId
 * @property {string} name
 * @property {string | null} description
 * @property {string | null} imageUrl
 * @property {number} sortOrder
 * @property {boolean} active Own flag; a category is hidden from customers when it or any parent is inactive.
 * @property {number} depth 1 = top level.
 * @property {number} productCount Non-archived products directly in this category.
 * @property {AdminCategoryNode[]} children
 */

/**
 * @typedef {object} CategoryRequest
 * @property {string} name
 * @property {string | null} parentId null = top level.
 * @property {string | null} description
 * @property {string | null} imageUrl
 * @property {number} sortOrder
 */

/**
 * @typedef {object} AdminProductListParams
 * @property {string} [vendorId]
 * @property {string} [categoryId]
 * @property {ProductStatus} [status]
 * @property {string} [q]
 * @property {number} [page]
 * @property {number} [size]
 */

/** @typedef {ProductView & { vendorName: string }} AdminProductView */

/**
 * @typedef {object} SearchIndexStatus
 * @property {string} alias
 * @property {string} index
 * @property {number} documents
 * @property {'green' | 'yellow' | 'red' | (string & {})} health
 */

/** Categories are at most this many levels deep (422 CATEGORY_TOO_DEEP). */
export const MAX_CATEGORY_DEPTH = 3

export const adminCatalogKeys = {
  all: ['admin', 'catalog'],
  categories: () => [...adminCatalogKeys.all, 'categories'],
  products: (params) => [...adminCatalogKeys.all, 'products', params],
  product: (id) => [...adminCatalogKeys.all, 'product', id],
  indexes: () => ['admin', 'search', 'indexes'],
}

const categories = '/admin/categories'
/** @param {string} id */
const category = (id) => `${categories}/${segment(id)}`

/** @returns {Promise<AdminCategoryNode[]>} */
export async function fetchAdminCategories() {
  return (await httpClient.get(categories)).data
}

/**
 * @param {CategoryRequest} body
 * @returns {Promise<AdminCategoryNode>}
 */
export async function createCategory(body) {
  return (await httpClient.post(categories, body)).data
}

/**
 * Can move the category (`parentId`): 409 CATEGORY_CYCLE, 422 CATEGORY_TOO_DEEP.
 * @param {string} id
 * @param {CategoryRequest} body
 * @returns {Promise<AdminCategoryNode>}
 */
export async function updateCategory(id, body) {
  return (await httpClient.put(category(id), body)).data
}

/**
 * @param {string} id
 * @returns {Promise<AdminCategoryNode>}
 */
export async function deactivateCategory(id) {
  return (await httpClient.post(`${category(id)}/deactivate`)).data
}

/**
 * @param {string} id
 * @returns {Promise<AdminCategoryNode>}
 */
export async function activateCategory(id) {
  return (await httpClient.post(`${category(id)}/activate`)).data
}

/**
 * 409 CATEGORY_IN_USE when it has sub-categories or non-archived products.
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function deleteCategory(id) {
  await httpClient.delete(category(id))
}

/**
 * @param {AdminProductListParams} params
 * @returns {Promise<PageResponse<AdminProductView>>}
 */
export async function listAdminProducts(params) {
  return (await httpClient.get('/admin/products', { params: cleanParams(params) })).data
}

/**
 * @param {string} id
 * @returns {Promise<AdminProductView>}
 */
export async function fetchAdminProduct(id) {
  return (await httpClient.get(`/admin/products/${segment(id)}`)).data
}

/** @returns {Promise<SearchIndexStatus[]>} */
export async function fetchSearchIndexes() {
  return (await httpClient.get('/admin/search/indexes')).data
}

/**
 * Re-sends every category and product snapshot (rebuilds the search index).
 * @returns {Promise<{ categories: number, products: number }>}
 */
export async function republishCatalog() {
  return (await httpClient.post('/admin/catalog/republish')).data
}

/**
 * Re-sends every restaurant listing to search.
 * @returns {Promise<{ restaurants: number }>}
 */
export async function republishRestaurantListings() {
  return (await httpClient.post('/admin/restaurants/listings/republish')).data
}

/**
 * Phase 4: re-sends `PriceChanged` for every live price (rebuilds prices in search). SUPER_ADMIN / OPS_ADMIN.
 * @returns {Promise<{ prices: number }>}
 */
export async function republishPrices() {
  return (await httpClient.post('/admin/pricing/republish')).data
}

/**
 * Phase 4: re-sends `ProductAvailabilityChanged` for every product (rebuilds stock in search). SUPER_ADMIN / OPS_ADMIN.
 * @returns {Promise<{ products: number }>}
 */
export async function republishStock() {
  return (await httpClient.post('/admin/inventory/republish')).data
}
