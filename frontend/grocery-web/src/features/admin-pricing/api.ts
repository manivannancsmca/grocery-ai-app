import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { PriceChangeRequestView, PriceChangeStatus } from '../vendor-pricing/api'

export interface AdminPriceRequestListParams {
  /** The server defaults to PENDING (oldest first); other statuses are newest first. */
  status?: PriceChangeStatus
  vendorId?: string
  page?: number
  size?: number
}

export const adminPriceKeys = {
  all: ['admin', 'price-change-requests'] as const,
  list: (params: AdminPriceRequestListParams) => [...adminPriceKeys.all, 'list', params] as const,
}

const base = '/admin/price-change-requests'
const item = (id: string) => `${base}/${segment(id)}`

export async function listAdminPriceRequests(params: AdminPriceRequestListParams): Promise<PageResponse<PriceChangeRequestView>> {
  return (await httpClient.get<PageResponse<PriceChangeRequestView>>(base, { params: cleanParams(params) })).data
}

/** The requested price becomes the live price. 409 PRICE_CHANGE_NOT_PENDING / PRODUCT_ARCHIVED. */
export async function approvePriceRequest(id: string, note: string | null): Promise<PriceChangeRequestView> {
  return (await httpClient.post<PriceChangeRequestView>(`${item(id)}/approve`, note ? { note } : {})).data
}

/** `reason` is required (≤ 300). 409 PRICE_CHANGE_NOT_PENDING. */
export async function rejectPriceRequest(id: string, reason: string): Promise<PriceChangeRequestView> {
  return (await httpClient.post<PriceChangeRequestView>(`${item(id)}/reject`, { reason })).data
}
