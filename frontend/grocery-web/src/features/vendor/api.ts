import { createStakeholderAdminApi, createStakeholderSelfApi } from '../stakeholders/api'
import type { BusinessAddress, StakeholderLifecycle, StakeholderListParams } from '../stakeholders/types'

/** `VendorUpdateRequest` — everything optional while editing (contract §5). */
export interface VendorUpdateRequest {
  businessName: string | null
  legalName: string | null
  gstin: string | null
  fssaiLicense: string | null
  contactEmail: string | null
  contactPhone: string | null
  address: BusinessAddress | null
}

/** `VendorView` */
export interface VendorView extends StakeholderLifecycle {
  ownerUserId: string
  businessName: string | null
  legalName: string | null
  gstin: string | null
  fssaiLicense: string | null
  contactEmail: string | null
  contactPhone: string | null
  address: BusinessAddress | null
}

export const VENDOR_NOT_FOUND = 'VENDOR_NOT_FOUND'

export const vendorApi = createStakeholderSelfApi<VendorView, VendorUpdateRequest>('/vendors')
export const vendorAdminApi = createStakeholderAdminApi<VendorView>('/admin/vendors')

export const vendorKeys = {
  mine: ['vendor', 'me'] as const,
  admin: ['admin', 'vendors'] as const,
  adminList: (params: StakeholderListParams) => [...vendorKeys.admin, 'list', params] as const,
}
