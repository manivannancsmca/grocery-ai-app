import { StoreReturnsView } from '../../../features/store-returns/StoreReturnsView'

/** `/vendor/returns` (Phase 12 §3): approve with or without restocking, or reject. */
export function VendorReturnsPage() {
  return <StoreReturnsView kind="VENDOR" />
}
