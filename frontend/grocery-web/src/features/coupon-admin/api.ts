import { httpClient } from '../../shared/api/httpClient'
import { toPage } from '../../shared/api/pages'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'

/**
 * Promotions & coupons (pricing-service — Phase 12 contract §2). Admins manage PLATFORM coupons (funded by the
 * platform; optionally for one store type) and may create STORE coupons for a store; a vendor or restaurant owner
 * manages its own STORE coupons (funded by the store). One coupon per cart.
 */

/** Who manages: the admin area, a vendor or a restaurant owner — each under its own path. */
export type CouponOwner = 'admin' | 'vendor' | 'restaurant'

export const COUPON_SCOPES = ['PLATFORM', 'STORE'] as const
export type CouponScope = (typeof COUPON_SCOPES)[number]

export const DISCOUNT_TYPES = ['PERCENT', 'FLAT', 'FREE_DELIVERY'] as const
export type DiscountType = (typeof DISCOUNT_TYPES)[number]

export const COUPON_STORE_TYPES = ['ANY', 'GROCERY', 'RESTAURANT'] as const
export type CouponStoreType = (typeof COUPON_STORE_TYPES)[number]

export const COUPON_CODE_PATTERN = /^[A-Z0-9-]{4,20}$/

/** Limits from the contract. */
export const COUPON_LIMITS = {
  name: 80,
  description: 300,
  percentMin: 1,
  percentMax: 90,
  /** A sane cap for flat amounts and limits (the contract only says `value ≤ subtotal` at use). */
  amountMax: 100_000,
  usageMax: 1_000_000,
} as const

export const COUPON_ERRORS = { codeTaken: 'COUPON_CODE_TAKEN' } as const

/** `CouponView`. Times are instants (UTC). */
export interface CouponView {
  id: string
  code: string
  name: string
  description: string | null
  scope: CouponScope | (string & {})
  /** STORE coupons: the vendor or restaurant. */
  storeId: string | null
  storeName?: string | null
  /** PLATFORM coupons: which stores (ANY when not limited). */
  storeType: CouponStoreType | (string & {}) | null
  discountType: DiscountType | (string & {})
  /** PERCENT: 1–90; FLAT: rupees; FREE_DELIVERY: null / 0. */
  value: number | null
  /** PERCENT only. */
  maxDiscount: number | null
  minOrderSubtotal: number
  validFrom: string
  validUntil: string
  totalUsageLimit: number | null
  perCustomerLimit: number
  /** Paused coupons are not usable. */
  active: boolean
  /** Listed to customers as an offer. */
  public: boolean
  /** RESERVED and CONFIRMED uses. */
  usedCount: number
  /** Discount (and delivery-fee discount) given so far, rupees. */
  discountGiven: number
  createdAt?: string
  updatedAt?: string
}

/** `CouponRequest`. Store owners' `scope` / `storeId` are forced by the server. */
export interface CouponRequest {
  code: string
  name: string
  description: string | null
  scope: CouponScope
  storeId?: string | null
  storeType?: CouponStoreType | null
  discountType: DiscountType
  value: number | null
  maxDiscount: number | null
  minOrderSubtotal: number
  validFrom: string
  validUntil: string
  totalUsageLimit: number | null
  perCustomerLimit: number
  public: boolean
}

export const REDEMPTION_STATUSES = ['RESERVED', 'CONFIRMED', 'RELEASED'] as const

/** One use of a coupon (one order). */
export interface CouponRedemptionView {
  id?: string
  orderId: string
  orderNumber?: string | null
  customerId?: string | null
  discount: number
  deliveryFeeDiscount: number
  status: (typeof REDEMPTION_STATUSES)[number] | (string & {})
  createdAt?: string | null
}

/** `GET …/coupons/{id}/usage`, normalised: the totals (when sent) and the uses. */
export interface CouponUsage {
  usedCount: number | null
  discountGiven: number | null
  redemptions: PageResponse<CouponRedemptionView>
}

export interface CouponListParams {
  q?: string
  page?: number
  size?: number
}

export const couponAdminKeys = {
  all: ['coupon-admin'] as const,
  owner: (owner: CouponOwner) => [...couponAdminKeys.all, owner] as const,
  list: (owner: CouponOwner, params: CouponListParams) => [...couponAdminKeys.owner(owner), 'list', params] as const,
  usage: (owner: CouponOwner, id: string, page: number) => [...couponAdminKeys.owner(owner), 'usage', id, page] as const,
}

const BASES: Record<CouponOwner, string> = { admin: '/admin/coupons', vendor: '/vendor/coupons', restaurant: '/restaurant/coupons' }
const item = (owner: CouponOwner, id: string) => `${BASES[owner]}/${segment(id)}`

export async function listCoupons(owner: CouponOwner, params: CouponListParams): Promise<PageResponse<CouponView>> {
  return toPage((await httpClient.get<PageResponse<CouponView> | CouponView[]>(BASES[owner], { params: cleanParams(params) })).data)
}

/** 409 COUPON_CODE_TAKEN. */
export async function createCoupon(owner: CouponOwner, body: CouponRequest): Promise<CouponView> {
  return (await httpClient.post<CouponView>(BASES[owner], body)).data
}

/** 409 COUPON_CODE_TAKEN. */
export async function updateCoupon(owner: CouponOwner, id: string, body: CouponRequest): Promise<CouponView> {
  return (await httpClient.put<CouponView>(item(owner, id), body)).data
}

export async function setCouponActive(owner: CouponOwner, id: string, active: boolean): Promise<CouponView> {
  return (await httpClient.post<CouponView>(`${item(owner, id)}/${active ? 'resume' : 'pause'}`)).data
}

type UsageAnswer =
  | PageResponse<CouponRedemptionView>
  | CouponRedemptionView[]
  | { usedCount?: number; discountGiven?: number; redemptions?: PageResponse<CouponRedemptionView> | CouponRedemptionView[] }

export async function fetchCouponUsage(owner: CouponOwner, id: string, page: number): Promise<CouponUsage> {
  const data = (await httpClient.get<UsageAnswer>(`${item(owner, id)}/usage`, { params: { page, size: 20 } })).data
  if (Array.isArray(data) || (data && 'content' in data)) {
    return { usedCount: null, discountGiven: null, redemptions: toPage(data as PageResponse<CouponRedemptionView> | CouponRedemptionView[]) }
  }
  const summary = (data ?? {}) as { usedCount?: number; discountGiven?: number; redemptions?: PageResponse<CouponRedemptionView> | CouponRedemptionView[] }
  return { usedCount: summary.usedCount ?? null, discountGiven: summary.discountGiven ?? null, redemptions: toPage(summary.redemptions) }
}
