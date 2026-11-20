import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { BadgeTone } from '../../shared/components/ui'
import type { FoodType } from '../../shared/utils/foodType'
import type { IdName, ProductAttribute, UnitOfMeasure } from '../catalog/api'
import { PATH_SEPARATOR } from '../catalog/categoryTree'

/** Product lifecycle: DRAFT → PUBLISHED ⇄ UNPUBLISHED, any of them → ARCHIVED (final). */
export const PRODUCT_STATUSES = ['DRAFT', 'PUBLISHED', 'UNPUBLISHED', 'ARCHIVED'] as const
export type ProductStatus = (typeof PRODUCT_STATUSES)[number]

export const PRODUCT_STATUS_LABELS: Record<ProductStatus, string> = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  UNPUBLISHED: 'Unpublished',
  ARCHIVED: 'Archived',
}

export const PRODUCT_STATUS_TONES: Record<ProductStatus, BadgeTone> = {
  DRAFT: 'gray',
  PUBLISHED: 'green',
  UNPUBLISHED: 'amber',
  ARCHIVED: 'violet',
}

export function isProductStatus(value: unknown): value is ProductStatus {
  return typeof value === 'string' && (PRODUCT_STATUSES as readonly string[]).includes(value)
}

/** `ProductRequest` (contract §3). */
export interface ProductRequest {
  name: string
  description: string | null
  categoryId: string
  brandName: string | null
  unitValue: number
  unitOfMeasure: UnitOfMeasure
  sku: string | null
  barcode: string | null
  foodType: FoodType | null
  tags: string[]
  attributes: ProductAttribute[]
  /** First = primary image. */
  imageUrls: string[]
}

export interface ProductCategoryRef {
  id: string
  name: string
  /** Names root → leaf. */
  path: string[]
  active: boolean
}

/** "Fruits & Vegetables › Fresh Vegetables", or a placeholder when the category no longer exists. */
export function categoryLabel(category: ProductCategoryRef | null): string {
  if (!category) return 'Category removed'
  return category.path.join(PATH_SEPARATOR) || category.name
}

/** `ProductView` — the admin view adds `vendorName`. */
export interface ProductView {
  id: string
  vendorId: string
  name: string
  description: string | null
  /** null only for an archived product whose category was deleted afterwards. */
  category: ProductCategoryRef | null
  brand: IdName | null
  unitValue: number
  unitOfMeasure: UnitOfMeasure
  unitLabel: string
  sku: string | null
  barcode: string | null
  foodType: FoodType | null
  tags: string[]
  attributes: ProductAttribute[]
  imageUrls: string[]
  status: ProductStatus
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  vendorName?: string
}

export interface VendorProductListParams {
  /** Archived products are listed only with `status=ARCHIVED`. */
  status?: ProductStatus
  q?: string
  page?: number
  size?: number
}

export type ProductStatusAction = 'publish' | 'unpublish' | 'archive'

/** The product page in the vendor workspace (edit, or view when archived). */
export const vendorProductPath = (product: Pick<ProductView, 'id'>) => `/vendor/products/${encodeURIComponent(product.id)}`

export const VENDOR_NOT_ACTIVE = 'VENDOR_NOT_ACTIVE'
export const PRODUCT_NOT_FOUND = 'PRODUCT_NOT_FOUND'

export const vendorProductKeys = {
  all: ['vendor', 'products'] as const,
  list: (params: VendorProductListParams) => [...vendorProductKeys.all, 'list', params] as const,
  detail: (id: string) => [...vendorProductKeys.all, 'detail', id] as const,
  brands: (q: string) => ['vendor', 'brands', q] as const,
}

const base = '/vendor/products'
const item = (id: string) => `${base}/${segment(id)}`

export async function listVendorProducts(params: VendorProductListParams): Promise<PageResponse<ProductView>> {
  return (await httpClient.get<PageResponse<ProductView>>(base, { params: cleanParams(params) })).data
}

export async function fetchVendorProduct(id: string): Promise<ProductView> {
  return (await httpClient.get<ProductView>(item(id))).data
}

export async function createVendorProduct(body: ProductRequest): Promise<ProductView> {
  return (await httpClient.post<ProductView>(base, body)).data
}

export async function updateVendorProduct(id: string, body: ProductRequest): Promise<ProductView> {
  return (await httpClient.put<ProductView>(item(id), body)).data
}

export async function publishVendorProduct(id: string): Promise<ProductView> {
  return (await httpClient.post<ProductView>(`${item(id)}/publish`)).data
}

export async function unpublishVendorProduct(id: string): Promise<ProductView> {
  return (await httpClient.post<ProductView>(`${item(id)}/unpublish`)).data
}

/** `DELETE` archives the product (hidden everywhere, kept for order history). */
export async function archiveVendorProduct(id: string): Promise<void> {
  await httpClient.delete(item(id))
}

/** ≤ 10 existing brands matching `q`, for the brand field's suggestions. */
export async function fetchBrandSuggestions(q: string): Promise<IdName[]> {
  return (await httpClient.get<IdName[]>('/vendor/brands', { params: cleanParams({ q }) })).data
}
