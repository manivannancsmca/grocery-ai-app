import { useQuery } from '@tanstack/react-query'
import type { PageResponse } from '../../shared/api/types'
import { type InventoryListParams, inventoryKeys, listInventory } from '../vendor-inventory/api'
import { listVendorPrices, vendorPriceKeys, type VendorPriceListParams } from '../vendor-pricing/api'

const OUT_OF_STOCK: InventoryListParams = { filter: 'OUT_OF_STOCK', page: 0, size: 100 }
const UNPRICED: VendorPriceListParams = { filter: 'UNPRICED', page: 0, size: 100 }

/** The products of one gap list: all of them, and the published ones (customers see them but cannot buy them). */
export interface ListingGap {
  ids: ReadonlySet<string>
  published: ReadonlySet<string>
}

function gapOf(page: PageResponse<{ productId: string; productStatus: string }>): ListingGap {
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
    blocked: blocked as ReadonlySet<string> | undefined,
  }
}
