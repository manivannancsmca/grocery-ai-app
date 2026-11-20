import { createStakeholderAdminApi, createStakeholderSelfApi } from '../stakeholders/api'
import type { StakeholderLifecycle, StakeholderListParams } from '../stakeholders/types'

export const VEHICLE_TYPES = ['BICYCLE', 'SCOOTER', 'MOTORCYCLE', 'CAR'] as const
export type VehicleType = (typeof VEHICLE_TYPES)[number]

/** `PartnerUpdateRequest` — everything optional while editing (contract §5). */
export interface PartnerUpdateRequest {
  fullName: string | null
  phone: string | null
  city: string | null
  vehicleType: VehicleType | null
  vehicleNumber: string | null
  drivingLicenseNumber: string | null
}

/** `PartnerView` — `id` is the partner's userId. */
export interface PartnerView extends StakeholderLifecycle {
  email: string
  fullName: string | null
  phone: string | null
  city: string | null
  vehicleType: VehicleType | null
  vehicleNumber: string | null
  drivingLicenseNumber: string | null
}

export const PARTNER_NOT_FOUND = 'PARTNER_NOT_FOUND'

export const partnerApi = createStakeholderSelfApi<PartnerView, PartnerUpdateRequest>('/delivery/partners')
export const partnerAdminApi = createStakeholderAdminApi<PartnerView>('/admin/delivery-partners')

export const partnerKeys = {
  mine: ['delivery-partner', 'me'] as const,
  admin: ['admin', 'delivery-partners'] as const,
  adminList: (params: StakeholderListParams) => [...partnerKeys.admin, 'list', params] as const,
}
