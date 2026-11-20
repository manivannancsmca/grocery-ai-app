import { CouponsView } from '../../../features/coupon-admin/CouponsView'

/** `/restaurant/coupons` (Phase 12 §2): the restaurant's own coupons. */
export function RestaurantCouponsPage() {
  return <CouponsView owner="restaurant" canManage />
}
