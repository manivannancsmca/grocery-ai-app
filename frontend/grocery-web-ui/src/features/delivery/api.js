import { createStakeholderAdminApi, createStakeholderSelfApi } from '../stakeholders/api'

/** @typedef {import('../stakeholders/types').StakeholderLifecycle} StakeholderLifecycle */

export const VEHICLE_TYPES = ['BICYCLE', 'SCOOTER', 'MOTORCYCLE', 'CAR']
/** @typedef {(typeof VEHICLE_TYPES)[number]} VehicleType */

/**
 * `PartnerUpdateRequest` — everything optional while editing (contract §5).
 * @typedef {object} PartnerUpdateRequest
 * @property {string | null} fullName
 * @property {string | null} phone
 * @property {string | null} city
 * @property {VehicleType | null} vehicleType
 * @property {string | null} vehicleNumber
 * @property {string | null} drivingLicenseNumber
 */

/**
 * `PartnerView` — `id` is the partner's userId.
 * @typedef {StakeholderLifecycle & { email: string, fullName: string | null, phone: string | null, city: string | null, vehicleType: VehicleType | null, vehicleNumber: string | null, drivingLicenseNumber: string | null }} PartnerView
 */

export const PARTNER_NOT_FOUND = 'PARTNER_NOT_FOUND'

export const partnerApi = createStakeholderSelfApi('/delivery/partners')
export const partnerAdminApi = createStakeholderAdminApi('/admin/delivery-partners')

export const partnerKeys = {
  mine: ['delivery-partner', 'me'],
  admin: ['admin', 'delivery-partners'],
  adminList: (params) => [...partnerKeys.admin, 'list', params],
}
