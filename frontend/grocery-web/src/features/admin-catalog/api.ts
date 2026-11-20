import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { ProductStatus, ProductView } from '../vendor-products/api'

/** `AdminCategoryNode` — the whole tree including inactive categories (Phase 3 contract §4). */
export interface AdminCategoryNode {
  id: string
  parentId: string | null
  name: string
  description: string | null
  imageUrl: string | null
  sortOrder: number
  /** Own flag; a category is hidden from customers when it or any parent is inactive. */
  active: boolean
  /** 1 = top level. */
  depth: number
  /** Non-archived products directly in this category. */
  productCount: number
  children: AdminCategoryNode[]
}

export interface CategoryRequest {
  name: string
  /** null = top level. */
  parentId: string | null
  description: string | null
  imageUrl: string | null
  sortOrder: number
}

export interface AdminProductListParams {
  vendorId?: string
  categoryId?: string
  status?: ProductStatus
  q?: string
  page?: number
  size?: number
}

export type AdminProductView = ProductView & { vendorName: string }

export interface SearchIndexStatus {
  alias: string
  index: string
  documents: number
  health: 'green' | 'yellow' | 'red' | (string & {})
}

/** Categories are at most this many levels deep (422 CATEGORY_TOO_DEEP). */
export const MAX_CATEGORY_DEPTH = 3

export const adminCatalogKeys = {
  all: ['admin', 'catalog'] as const,
  categories: () => [...adminCatalogKeys.all, 'categories'] as const,
  products: (params: AdminProductListParams) => [...adminCatalogKeys.all, 'products', params] as const,
  product: (id: string) => [...adminCatalogKeys.all, 'product', id] as const,
  indexes: () => ['admin', 'search', 'indexes'] as const,
}

const categories = '/admin/categories'
const category = (id: string) => `${categories}/${segment(id)}`

export async function fetchAdminCategories(): Promise<AdminCategoryNode[]> {
  return (await httpClient.get<AdminCategoryNode[]>(categories)).data
}

export async function createCategory(body: CategoryRequest): Promise<AdminCategoryNode> {
  return (await httpClient.post<AdminCategoryNode>(categories, body)).data
}

/** Can move the category (`parentId`): 409 CATEGORY_CYCLE, 422 CATEGORY_TOO_DEEP. */
export async function updateCategory(id: string, body: CategoryRequest): Promise<AdminCategoryNode> {
  return (await httpClient.put<AdminCategoryNode>(category(id), body)).data
}

export async function deactivateCategory(id: string): Promise<AdminCategoryNode> {
  return (await httpClient.post<AdminCategoryNode>(`${category(id)}/deactivate`)).data
}

export async function activateCategory(id: string): Promise<AdminCategoryNode> {
  return (await httpClient.post<AdminCategoryNode>(`${category(id)}/activate`)).data
}

/** 409 CATEGORY_IN_USE when it has sub-categories or non-archived products. */
export async function deleteCategory(id: string): Promise<void> {
  await httpClient.delete(category(id))
}

export async function listAdminProducts(params: AdminProductListParams): Promise<PageResponse<AdminProductView>> {
  return (await httpClient.get<PageResponse<AdminProductView>>('/admin/products', { params: cleanParams(params) })).data
}

export async function fetchAdminProduct(id: string): Promise<AdminProductView> {
  return (await httpClient.get<AdminProductView>(`/admin/products/${segment(id)}`)).data
}

export async function fetchSearchIndexes(): Promise<SearchIndexStatus[]> {
  return (await httpClient.get<SearchIndexStatus[]>('/admin/search/indexes')).data
}

/** Re-sends every category and product snapshot (rebuilds the search index). */
export async function republishCatalog(): Promise<{ categories: number; products: number }> {
  return (await httpClient.post<{ categories: number; products: number }>('/admin/catalog/republish')).data
}

/** Re-sends every restaurant listing to search. */
export async function republishRestaurantListings(): Promise<{ restaurants: number }> {
  return (await httpClient.post<{ restaurants: number }>('/admin/restaurants/listings/republish')).data
}

/** Phase 4: re-sends `PriceChanged` for every live price (rebuilds prices in search). SUPER_ADMIN / OPS_ADMIN. */
export async function republishPrices(): Promise<{ prices: number }> {
  return (await httpClient.post<{ prices: number }>('/admin/pricing/republish')).data
}

/** Phase 4: re-sends `ProductAvailabilityChanged` for every product (rebuilds stock in search). SUPER_ADMIN / OPS_ADMIN. */
export async function republishStock(): Promise<{ products: number }> {
  return (await httpClient.post<{ products: number }>('/admin/inventory/republish')).data
}
