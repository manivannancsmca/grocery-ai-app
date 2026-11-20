import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { fetchSalesReport, storeReportKeys } from './api'

/** @typedef {import('../store-orders/api').StoreKind} StoreKind */
/** @typedef {import('./api').ReportRange} ReportRange */

/**
 * The sales report of a valid range (`enabled` false while the chosen range is invalid). Live store-order events refresh it.
 * @param {StoreKind} kind
 * @param {ReportRange} range
 * @param {boolean} enabled
 */
export function useSalesReport(kind, range, enabled) {
  return useQuery({
    queryKey: storeReportKeys.sales(kind, range),
    queryFn: () => fetchSalesReport(kind, range),
    enabled,
    placeholderData: keepPreviousData,
  })
}
