/** @typedef {import('../catalog/api').ProductCard} ProductCard */
/** @typedef {import('../restaurant-discovery/api').PublicMenuItem} PublicMenuItem */
/** @typedef {import('../restaurant-discovery/api').RestaurantDetail} RestaurantDetail */

/**
 * Why a product cannot be added right now (Phase 4 §6: no price or no stock → not addable), or null.
 * Worded differently from the visible "Out of stock" / "Price not available" labels: it is read out as the
 * Add button's description.
 * @param {Pick<ProductCard, 'price' | 'inStock'>} product
 * @returns {string | null}
 */
export function productUnavailableReason(product) {
  if (!product.price) return 'This product has no price yet, so it cannot be added to the cart.'
  if (product.inStock === false) return 'This product is out of stock, so it cannot be added to the cart.'
  return null
}

/**
 * Why a dish cannot be added right now (sold out, restaurant closed or paused), or null.
 * @param {Pick<RestaurantDetail, 'openNow' | 'acceptingOrders'>} restaurant
 * @param {Pick<PublicMenuItem, 'available'>} item
 * @returns {string | null}
 */
export function menuItemUnavailableReason(restaurant, item) {
  if (!item.available) return 'This dish is sold out right now.'
  if (!restaurant.openNow) return 'The restaurant is closed right now.'
  if (!restaurant.acceptingOrders) return 'The restaurant has paused new orders.'
  return null
}
