import { errorMessage, hasErrorCode, problemNumber } from '../../shared/api/problemDetail'
import { MAX_CART_LINES, MAX_LINE_QUANTITY } from './api'

/**
 * @typedef {object} CartErrorContext
 * @property {string} [itemName] Product or dish the request was about.
 * @property {string} [storeName] Store or restaurant, when known.
 */

/**
 * Friendly message for a refused cart write (contract §2 "Rules when adding or changing quantities").
 * @param {unknown} error
 * @param {CartErrorContext} [context={}]
 * @returns {string}
 */
export function cartErrorMessage(error, context = {}) {
  const item = context.itemName ?? 'This item'
  const store = context.storeName ?? 'The restaurant'

  if (hasErrorCode(error, 'ITEM_UNAVAILABLE')) return `${item} is not available right now.`
  if (hasErrorCode(error, 'OUT_OF_STOCK')) return `${item} is out of stock right now.`
  if (hasErrorCode(error, 'INSUFFICIENT_STOCK')) {
    const available = problemNumber(error, 'available')
    if (available === null) return `There is not enough stock of ${item}.`
    if (available <= 0) return `${item} is out of stock right now.`
    return `Only ${available} of ${item} ${available === 1 ? 'is' : 'are'} left in stock.`
  }
  if (hasErrorCode(error, 'STORE_CLOSED')) return `${store} is closed right now. You can order when it opens again.`
  if (hasErrorCode(error, 'NOT_ACCEPTING_ORDERS')) return `${store} is not accepting orders right now.`
  if (hasErrorCode(error, 'STORE_UNAVAILABLE'))
    return `${context.storeName ?? 'This store'} is not available right now.`
  if (hasErrorCode(error, 'QUANTITY_LIMIT')) return `You can add at most ${MAX_LINE_QUANTITY} of one item.`
  if (hasErrorCode(error, 'CART_LIMIT_REACHED')) {
    return `Your cart is full — it holds up to ${MAX_CART_LINES} different items. Remove something to add more.`
  }
  if (hasErrorCode(error, 'CART_ITEM_NOT_FOUND')) return `${item} is no longer in your cart.`
  if (hasErrorCode(error, 'CONCURRENT_MODIFICATION')) {
    return 'Your cart was changed somewhere else at the same time. Please try again.'
  }
  if (hasErrorCode(error, 'DEPENDENCY_UNAVAILABLE')) {
    return 'We could not check prices and stock just now. Please try again in a moment.'
  }
  if (hasErrorCode(error, 'CART_UNAVAILABLE'))
    return 'Your cart is temporarily unavailable. Please try again in a moment.'
  return errorMessage(error)
}
