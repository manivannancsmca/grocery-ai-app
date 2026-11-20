import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { OrderType } from '../orders/api'

/**
 * Deliveries (delivery-service — Phase 9 contract §2–§3). The partner's own work: availability, live location,
 * offers, the active delivery and its steps, and history. Role DELIVERY_PARTNER; the partner id is the user id.
 * A partner who is not APPROVED gets 403 PARTNER_NOT_ACTIVE on everything here.
 */

export const DELIVERY_STATUSES = ['PENDING', 'SEARCHING', 'ASSIGNED', 'PICKED_UP', 'DELIVERED', 'FAILED', 'CANCELLED'] as const
export type DeliveryStatus = (typeof DELIVERY_STATUSES)[number]

export function isDeliveryStatus(value: unknown): value is DeliveryStatus {
  return typeof value === 'string' && (DELIVERY_STATUSES as readonly string[]).includes(value)
}

/** Why a partner could not deliver (`OTHER` needs a note). */
export const DELIVERY_FAIL_REASONS = ['CUSTOMER_UNREACHABLE', 'ADDRESS_NOT_FOUND', 'CUSTOMER_REFUSED', 'OTHER'] as const
export type DeliveryFailReason = (typeof DELIVERY_FAIL_REASONS)[number]

/** Where the server last saw the partner. */
export interface PartnerLocation {
  latitude: number
  longitude: number
  at: string
}

/** `PartnerStatusView` */
export interface PartnerStatusView {
  partnerId: string
  online: boolean
  location: PartnerLocation | null
  activeDeliveryId: string | null
  pendingOffers: number
}

export interface OfferStore {
  name: string
  city: string | null
  latitude: number | null
  longitude: number | null
}

/** `OfferView` — before accepting, the partner sees no customer details. */
export interface OfferView {
  offerId: string
  deliveryId: string
  orderNumber: string
  orderType: OrderType
  store: OfferStore
  /** Landmark / line 2 + city, like the store board. */
  dropArea: string | null
  /** Partner → store when the offer was made. */
  distanceKm: number | null
  /** Store → customer. */
  tripKm: number | null
  itemCount: number
  expiresAt: string
}

export interface DeliveryPickup {
  name: string
  city: string | null
  latitude: number | null
  longitude: number | null
}

export interface DeliveryDrop {
  recipientName: string
  phone: string | null
  line1: string
  line2: string | null
  landmark: string | null
  city: string
  postalCode: string | null
  latitude: number | null
  longitude: number | null
}

export interface DeliveryFailure {
  reason: DeliveryFailReason | (string & {})
  note: string | null
}

export interface DeliveryTimelineEntry {
  status: DeliveryStatus
  at: string
}

/** `DeliveryView` — the assigned partner (and admins) see the drop details. */
export interface DeliveryView {
  deliveryId: string
  orderId: string
  orderNumber: string
  orderType: OrderType
  status: DeliveryStatus
  /** The store marked the order ready (OrderReadyForPickup). */
  readyForPickup: boolean
  readyBy: string | null
  /** Null for an order confirmed before Phase 9 (no store location snapshot). */
  pickup: DeliveryPickup | null
  drop: DeliveryDrop
  /** The customer's delivery instructions. */
  note: string | null
  itemCount: number
  total: number
  currency: string
  assignedAt: string | null
  pickedUpAt: string | null
  deliveredAt: string | null
  failedAt: string | null
  failure: DeliveryFailure | null
  /** Oldest first. */
  timeline: DeliveryTimelineEntry[]
}

/** `PUT /api/delivery/partners/me/location`. */
export interface LocationReport {
  latitude: number
  longitude: number
  accuracyMeters?: number
  /** Degrees clockwise from north. */
  heading?: number
  speedKmh?: number
}

export interface FailDeliveryRequest {
  reason: DeliveryFailReason
  /** ≤ 300; required for OTHER. */
  note?: string
}

/** One finished delivery of the history (newest first). */
export interface DeliverySummary {
  deliveryId: string
  orderNumber: string
  status: Extract<DeliveryStatus, 'DELIVERED' | 'FAILED'> | (string & {})
  /** The contract names it "`store` name": a plain name, or `{ name }`. */
  store: string | { name: string } | null
  dropArea: string | null
  tripKm: number | null
  pickedUpAt: string | null
  finishedAt: string | null
}

/** India-time days; the week is the last 7 days. */
export interface DeliveryHistorySummary {
  deliveredToday: number
  deliveredThisWeek: number
  failedThisWeek: number
}

export interface HistoryParams {
  page?: number
  size?: number
  /** One outcome; absent = both. */
  status?: Extract<DeliveryStatus, 'DELIVERED' | 'FAILED'>
  /** India-time days the trip ended on, `YYYY-MM-DD`, both included; either may be absent. */
  from?: string
  to?: string
}

/** Limits from the contract. */
export const DELIVERY_LIMITS = { failNote: 300, reassignReason: 300 } as const

export const DELIVERY_ERRORS = {
  /** 403 on every partner endpoint while the partner is not APPROVED. */
  partnerNotActive: 'PARTNER_NOT_ACTIVE',
  /** Going offline with an active delivery. */
  activeDelivery: 'ACTIVE_DELIVERY',
  /** A location report while offline. */
  partnerOffline: 'PARTNER_OFFLINE',
  offerNotFound: 'OFFER_NOT_FOUND',
  /** Not PENDING any more, expired, or another partner took the delivery (`currentStatus` = the offer's status). */
  offerNotAvailable: 'OFFER_NOT_AVAILABLE',
  deliveryNotFound: 'DELIVERY_NOT_FOUND',
  /** "Picked up" before the store marked the order ready. */
  orderNotReady: 'ORDER_NOT_READY',
  invalidTransition: 'INVALID_DELIVERY_TRANSITION',
} as const

export const deliveryKeys = {
  all: ['partner-deliveries'] as const,
  status: () => [...deliveryKeys.all, 'status'] as const,
  offers: () => [...deliveryKeys.all, 'offers'] as const,
  active: () => [...deliveryKeys.all, 'active'] as const,
  /** Prefix of the history list pages and the summary tiles. */
  history: () => [...deliveryKeys.all, 'history'] as const,
  historyList: (params: HistoryParams) => [...deliveryKeys.history(), 'list', params] as const,
  historySummary: () => [...deliveryKeys.history(), 'summary'] as const,
  /** Under `history`, so finishing a delivery refreshes the dashboard's charts too. */
  stats: (range: StatsRange) => [...deliveryKeys.history(), 'stats', range] as const,
}

const deliveryPath = (id: string) => `/delivery/deliveries/${segment(id)}`
const offerPath = (id: string) => `/delivery/offers/${segment(id)}`

export async function fetchPartnerStatus(): Promise<PartnerStatusView> {
  return (await httpClient.get<PartnerStatusView>('/delivery/partners/me/status')).data
}

/** Going offline with an active delivery → 409 ACTIVE_DELIVERY. */
export async function setAvailability(online: boolean): Promise<PartnerStatusView> {
  return (await httpClient.put<PartnerStatusView>('/delivery/partners/me/availability', { online })).data
}

/** 204. Offline → 409 PARTNER_OFFLINE. */
export async function reportLocation(report: LocationReport): Promise<void> {
  await httpClient.put('/delivery/partners/me/location', report)
}

/** The partner's PENDING offers. */
export async function listOffers(): Promise<OfferView[]> {
  return (await httpClient.get<OfferView[]>('/delivery/offers')).data
}

/** → the delivery (ASSIGNED). 404 OFFER_NOT_FOUND; 409 OFFER_NOT_AVAILABLE. */
export async function acceptOffer(offerId: string): Promise<DeliveryView> {
  return (await httpClient.post<DeliveryView>(`${offerPath(offerId)}/accept`)).data
}

/** 204, idempotent. */
export async function rejectOffer(offerId: string): Promise<void> {
  await httpClient.post(`${offerPath(offerId)}/reject`)
}

/** The partner's active delivery, or null (204). */
export async function fetchActiveDelivery(): Promise<DeliveryView | null> {
  const response = await httpClient.get<DeliveryView | ''>('/delivery/deliveries/active')
  return response.status === 204 || !response.data ? null : response.data
}

/** → PICKED_UP. 409 ORDER_NOT_READY / INVALID_DELIVERY_TRANSITION. */
export async function pickUpDelivery(id: string): Promise<DeliveryView> {
  return (await httpClient.post<DeliveryView>(`${deliveryPath(id)}/pickup`)).data
}

/** → DELIVERED (from PICKED_UP). */
export async function completeDelivery(id: string): Promise<DeliveryView> {
  return (await httpClient.post<DeliveryView>(`${deliveryPath(id)}/deliver`)).data
}

/** → FAILED (from PICKED_UP). */
export async function failDelivery(id: string, body: FailDeliveryRequest): Promise<DeliveryView> {
  return (await httpClient.post<DeliveryView>(`${deliveryPath(id)}/fail`, body)).data
}

/** Newest first; `status`, `from` and `to` filter it (the export takes the same filters). */
export async function listDeliveryHistory(params: HistoryParams): Promise<PageResponse<DeliverySummary>> {
  return (await httpClient.get<PageResponse<DeliverySummary>>('/delivery/history', { params: cleanParams(params) })).data
}

export async function fetchDeliveryHistorySummary(): Promise<DeliveryHistorySummary> {
  return (await httpClient.get<DeliveryHistorySummary>('/delivery/history/summary')).data
}

/** India-time days, both included. */
export interface StatsRange {
  from: string
  to: string
}

/** The server answers at most this many days per request. */
export const MAX_STATS_DAYS = 92

/**
 * `GET /api/delivery/stats` — the partner's own numbers over the range. Counts go by when things happened (assigned,
 * ended, cancelled, offer sent); `inProgress` and `offersPending` are right now. Rates are 0–1 or null when there is
 * nothing to compare; averages are minutes or null.
 */
export interface PartnerStatsTotals {
  assigned: number
  delivered: number
  failed: number
  cancelled: number
  inProgress: number
  offersPending: number
  offersReceived: number
  offersAccepted: number
  offersRejected: number
  offersExpired: number
  successRate: number | null
  acceptanceRate: number | null
  distanceKm: number
  /** Estimated with `payRate`. */
  earnings: number
  avgPickupMinutes: number | null
  avgRideMinutes: number | null
  avgTotalMinutes: number | null
  onlineMinutes: number
}

export interface PartnerStatsDay {
  /** `YYYY-MM-DD` */
  date: string
  delivered: number
  failed: number
  cancelled: number
  earnings: number
  distanceKm: number
  onlineMinutes: number
}

export interface PartnerStatsView {
  from: string
  to: string
  timezone: string
  totals: PartnerStatsTotals
  /** Every day of the range, oldest first. */
  days: PartnerStatsDay[]
  /** Per delivered order: base + perKm × trip km. */
  payRate: { basePerDelivery: number; perKm: number; currency: string }
}

export async function fetchPartnerStats(range: StatsRange): Promise<PartnerStatsView> {
  return (await httpClient.get<PartnerStatsView>('/delivery/stats', { params: range })).data
}
