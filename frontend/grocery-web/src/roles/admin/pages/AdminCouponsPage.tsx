import { CouponsView } from '../../../features/coupon-admin/CouponsView'
import { useAuth } from '../../../shared/auth/useAuth'

/** `/admin/coupons` (Phase 12 §2): every admin reads; super, operations and finance admins manage. */
export function AdminCouponsPage() {
  const { can } = useAuth()
  return <CouponsView owner="admin" canManage={can('coupons.manage')} />
}
