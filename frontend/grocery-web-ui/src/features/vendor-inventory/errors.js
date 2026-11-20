import { errorMessage, hasErrorCode, problemNumber } from '../../shared/api/problemDetail'
import { formatCount } from '../../shared/utils/format'
import { INSUFFICIENT_STOCK, STOCK_BELOW_RESERVED, STOCK_LIMIT_EXCEEDED, STOCK_LIMITS } from './api'

/**
 * Message for the errors every stock or price change of a vendor can hit (Phase 4 contract §1).
 * @param {unknown} error
 * @returns {string}
 */
export function vendorProductErrorMessage(error) {
  if (hasErrorCode(error, 'PRODUCT_ARCHIVED')) return 'This product is archived and cannot be changed.'
  if (hasErrorCode(error, 'PRODUCT_NOT_FOUND')) {
    return 'This product is not known here yet. If you created it a moment ago, try again in a few seconds.'
  }
  if (hasErrorCode(error, 'VENDOR_NOT_ACTIVE'))
    return 'Your business is not active, so this cannot be changed right now.'
  return errorMessage(error)
}

/**
 * @param {number} count
 * @returns {string}
 */
export function units(count) {
  return `${formatCount(count)} unit${count === 1 ? '' : 's'}`
}

/**
 * Message for the stock rules (contract §2): INSUFFICIENT_STOCK / STOCK_BELOW_RESERVED carry the server's
 * `available` and `reserved` (the stock may have changed since the screen loaded, so they win over `fallback`).
 * Null for any other error.
 * @param {unknown} error
 * @param {{ available: number, reserved: number }} fallback
 * @returns {string | null}
 */
export function stockRuleMessage(error, fallback) {
  const available = problemNumber(error, 'available') ?? fallback.available
  const reserved = problemNumber(error, 'reserved') ?? fallback.reserved
  if (hasErrorCode(error, INSUFFICIENT_STOCK)) {
    return reserved > 0
      ? `Only ${units(available)} can be removed: ${units(reserved)} ${reserved === 1 ? 'is' : 'are'} reserved for orders.`
      : `Only ${units(available)} can be removed.`
  }
  if (hasErrorCode(error, STOCK_BELOW_RESERVED)) {
    return `The counted stock cannot be below the ${units(reserved)} reserved for orders.`
  }
  if (hasErrorCode(error, STOCK_LIMIT_EXCEEDED)) {
    return `Stock on hand cannot be more than ${formatCount(STOCK_LIMITS.onHand)} units.`
  }
  return null
}
