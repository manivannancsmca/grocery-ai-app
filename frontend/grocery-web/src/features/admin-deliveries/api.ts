import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { VehicleType } from '../delivery/api'
import type { DeliveryStatus, DeliveryView } from '../deliveries/api'

/**
 * Admin delivery monitor (delivery-service — Phase 9 contract §5). Reads: any admin role. Reassign: SUPER_ADMIN,
 * OPS_ADMIN, DELIVERY_MANAGER.
 */

/** `AdminDeliveryView` — DeliveryView + the partner, whether the search found nobody, and how many offers were sent. */
export interface AdminDeliveryView extends DeliveryView {
  partnerId: string | null
  partnerName: string | null
  /** SEARCHING, but nobody accepted within the largest radius (a fresh search runs every minute). */
  assignmentFailed: boolean
  /** The contract says "offers sent"; the field name is assumed. */
  offersSent?: number
}

export const OFFER_STATUSES = ['PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'WITHDRAWN'] as const
export type OfferStatus = (typeof OFFER_STATUSES)[number]

/** One offer of a delivery, with its outcome. */
export interface DeliveryOfferRecord {
  offerId: string
  partnerId: string
  partnerName: string | null
  distanceKm: number | null
  status: OfferStatus | (string & {})
  sentAt: string
  closedAt: string | null
}

export interface AdminDeliveryDetail extends AdminDeliveryView {
  offers: DeliveryOfferRecord[]
}

/** `GET /api/admin/delivery-partners/online` — one online partner. */
export interface OnlinePartner {
  partnerId: string
  name: string | null
  vehicleType: VehicleType | (string & {}) | null
  latitude: number | null
  longitude: number | null
  locationAt: string | null
  /** Has an active delivery (cannot be assigned). */
  busy: boolean
  activeDeliveryId: string | null
}

export interface AdminDeliveryListParams {
  status?: DeliveryStatus
  /** true → only deliveries for which nobody was found. */
  assignmentFailed?: boolean
  partnerId?: string
  /** Order number. */
  q?: string
  page?: number
  size?: number
}

export interface ReassignRequest {
  /** Absent: drop the current partner and search again (excluding them). */
  partnerId?: string
  /** ≤ 300. */
  reason?: string
}

export const ADMIN_DELIVERY_ERRORS = {
  notFound: 'DELIVERY_NOT_FOUND',
  /** The chosen partner is not APPROVED, online and free. */
  partnerUnavailable: 'PARTNER_UNAVAILABLE',
  /** Reassign is only possible while SEARCHING or ASSIGNED. */
  invalidTransition: 'INVALID_DELIVERY_TRANSITION',
} as const

/** Reassign applies only to these statuses. */
export const REASSIGNABLE_STATUSES: readonly DeliveryStatus[] = ['SEARCHING', 'ASSIGNED']

export const adminDeliveryKeys = {
  all: ['admin', 'deliveries'] as const,
  lists: () => [...adminDeliveryKeys.all, 'list'] as const,
  list: (params: AdminDeliveryListParams) => [...adminDeliveryKeys.lists(), params] as const,
  detail: (id: string) => [...adminDeliveryKeys.all, 'detail', id] as const,
  onlinePartners: () => [...adminDeliveryKeys.all, 'online-partners'] as const,
}

const base = '/admin/deliveries'

/** Newest first. */
export async function listAdminDeliveries(params: AdminDeliveryListParams): Promise<PageResponse<AdminDeliveryView>> {
  return (await httpClient.get<PageResponse<AdminDeliveryView>>(base, { params: cleanParams(params) })).data
}

export async function fetchAdminDelivery(id: string): Promise<AdminDeliveryDetail> {
  return (await httpClient.get<AdminDeliveryDetail>(`${base}/${segment(id)}`)).data
}

/** 409 PARTNER_UNAVAILABLE / INVALID_DELIVERY_TRANSITION. */
export async function reassignDelivery(id: string, body: ReassignRequest): Promise<AdminDeliveryView> {
  return (await httpClient.post<AdminDeliveryView>(`${base}/${segment(id)}/reassign`, body)).data
}

export async function listOnlinePartners(): Promise<OnlinePartner[]> {
  return (await httpClient.get<OnlinePartner[]>('/admin/delivery-partners/online')).data
}
