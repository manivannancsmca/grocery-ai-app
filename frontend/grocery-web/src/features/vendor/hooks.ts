import { useApprovalClaimSync, useStakeholderProfile } from '../stakeholders/hooks'
import { VENDOR_NOT_FOUND, vendorApi, vendorKeys, type VendorView } from './api'

/** The signed-in vendor's profile; on approval it also refreshes the token to pick up `vendor_id`. */
export function useVendorProfile() {
  const query = useStakeholderProfile<VendorView>({
    queryKey: vendorKeys.mine,
    queryFn: vendorApi.fetchMine,
    notFoundCode: VENDOR_NOT_FOUND,
  })
  useApprovalClaimSync(query.data, 'vendorId')
  return query
}
