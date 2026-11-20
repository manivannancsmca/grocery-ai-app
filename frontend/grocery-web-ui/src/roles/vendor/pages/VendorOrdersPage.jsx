import { StoreOrderBoard } from '../../../features/store-orders/StoreOrderBoard'

/** `/vendor/orders` — the grocery order board (Phase 8): accept, pack, ready for pickup. */
export function VendorOrdersPage() {
  return <StoreOrderBoard kind="VENDOR" />
}
