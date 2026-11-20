import { createStakeholderAdminApi, createStakeholderSelfApi } from '../stakeholders/api'

/** @typedef {import('../stakeholders/types').BusinessAddress} BusinessAddress */
/** @typedef {import('../stakeholders/types').StakeholderLifecycle} StakeholderLifecycle */

/**
 * `VendorUpdateRequest` — everything optional while editing (contract §5).
 * @typedef {object} VendorUpdateRequest
 * @property {string | null} businessName
 * @property {string | null} legalName
 * @property {string | null} gstin
 * @property {string | null} fssaiLicense
 * @property {string | null} contactEmail
 * @property {string | null} contactPhone
 * @property {BusinessAddress | null} address
 */

/**
 * `VendorView`
 * @typedef {StakeholderLifecycle & { ownerUserId: string, businessName: string | null, legalName: string | null, gstin: string | null, fssaiLicense: string | null, contactEmail: string | null, contactPhone: string | null, address: BusinessAddress | null }} VendorView
 */

export const VENDOR_NOT_FOUND = 'VENDOR_NOT_FOUND'

export const vendorApi = createStakeholderSelfApi('/vendors')
export const vendorAdminApi = createStakeholderAdminApi('/admin/vendors')

export const vendorKeys = {
  mine: ['vendor', 'me'],
  admin: ['admin', 'vendors'],
  adminList: (params) => [...vendorKeys.admin, 'list', params],
}
