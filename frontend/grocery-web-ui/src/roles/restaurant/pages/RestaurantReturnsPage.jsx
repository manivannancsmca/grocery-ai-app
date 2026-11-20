import { StoreReturnsView } from '../../../features/store-returns/StoreReturnsView'

/** `/restaurant/returns` (Phase 12 §3): reported problems — approve (never restocked) or reject. */
export function RestaurantReturnsPage() {
  return <StoreReturnsView kind="RESTAURANT" />
}
