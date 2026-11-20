import { useQuery } from '@tanstack/react-query'
import { inventoryKeys, listInventory } from '../vendor-inventory/api'
import { listVendorPrices, vendorPriceKeys } from '../vendor-pricing/api'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../vendor-inventory/api').InventoryListParams} InventoryListParams */
/** @typedef {import('../vendor-pricing/api').VendorPriceListParams} VendorPriceListParams */

/** @type {InventoryListParams} */
const OUT_OF_STOCK = { filter: 'OUT_OF_STOCK', page: 0, size: 100 }
/** @type {VendorPriceListParams} */
const UNPRICED = { filter: 'UNPRICED', page: 0, size: 100 }

/**
 * The products of one gap list: all of them, and the published ones (customers see them but cannot buy them).
 * @typedef {object} ListingGap
 * @property {ReadonlySet<string>} ids
 * @property {ReadonlySet<string>} published
 */

/**
 * @param {PageResponse<{ productId: string, productStatus: string }>} page
 * @returns {ListingGap}
 */
function gapOf(page) {
  return {
    ids: new Set(page.content.map((row) => row.productId)),
    published: new Set(page.content.filter((row) => row.productStatus === 'PUBLISHED').map((row) => row.productId)),
  }
}

/**
 * Products customers cannot buy yet (no stock, or no price), for hints on the products list. Two list calls
 * (the first 100 of each), never one call per product; failures just leave the hints out.
 */
export function useListingGaps() {
  const outOfStock = useQuery({
    queryKey: inventoryKeys.list(OUT_OF_STOCK),
    queryFn: () => listInventory(OUT_OF_STOCK),
    select: gapOf,
    retry: false,
  })
  const unpriced = useQuery({
    queryKey: vendorPriceKeys.list(UNPRICED),
    queryFn: () => listVendorPrices(UNPRICED),
    select: gapOf,
    retry: false,
  })
  // Published products with either gap: listed for customers, but they cannot be bought. Unknown until both lists load.
  const blocked =
    outOfStock.data && unpriced.data ? new Set([...outOfStock.data.published, ...unpriced.data.published]) : undefined
  return {
    outOfStock: outOfStock.data?.ids,
    unpriced: unpriced.data?.ids,
    publishedOutOfStock: outOfStock.data?.published.size,
    publishedUnpriced: unpriced.data?.published.size,
    blocked: blocked,
  }
}
