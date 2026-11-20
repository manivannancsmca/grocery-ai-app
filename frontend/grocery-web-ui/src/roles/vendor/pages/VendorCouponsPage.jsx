import { CouponsView } from '../../../features/coupon-admin/CouponsView'

/** `/vendor/coupons` (Phase 12 §2): the store's own coupons. */
export function VendorCouponsPage() {
  return <CouponsView owner="vendor" canManage />
}
