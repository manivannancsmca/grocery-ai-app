import { keepPreviousData, useQuery } from '@tanstack/react-query'
import type { StoreKind } from '../store-orders/api'
import { fetchSalesReport, type ReportRange, storeReportKeys } from './api'

/** The sales report of a valid range (`enabled` false while the chosen range is invalid). Live store-order events refresh it. */
export function useSalesReport(kind: StoreKind, range: ReportRange, enabled: boolean) {
  return useQuery({
    queryKey: storeReportKeys.sales(kind, range),
    queryFn: () => fetchSalesReport(kind, range),
    enabled,
    placeholderData: keepPreviousData,
  })
}
