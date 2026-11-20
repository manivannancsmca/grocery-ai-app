import { StoreOrderBoard } from '../../../features/store-orders/StoreOrderBoard'

/** `/restaurant/orders` — the live kitchen board (Phase 8): accept with a preparation time, preparing, ready. */
export function RestaurantOrdersPage() {
  return <StoreOrderBoard kind="RESTAURANT" />
}
