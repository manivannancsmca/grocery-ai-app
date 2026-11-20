import { CartPageView } from '../../../features/cart/CartPageView'

/**
 * `/cart` — the store and its items with live prices and stock, and an order summary (address, delivery fee, coupon,
 * total) with "Proceed to checkout". The header's cart drawer shows the same items in short.
 */
export function CartPage() {
  return <CartPageView />
}
