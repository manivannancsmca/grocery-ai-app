import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  adminReportKeys,
  type DailySalesParams,
  fetchDailySales,
  fetchDeliveryPerformance,
  fetchOverview,
  fetchPromotions,
  fetchPurchasing,
  fetchRatings,
  fetchTopProducts,
  fetchTopStores,
  type ReportRange,
  type StoreMetric,
} from './api'

/** Every report query: only for a valid range, the previous answer stays while a new range loads. */
const shared = { placeholderData: keepPreviousData, staleTime: 60_000 } as const

export function useReportOverview(range: ReportRange, enabled: boolean) {
  return useQuery({ queryKey: adminReportKeys.report('overview', range), queryFn: () => fetchOverview(range), enabled, ...shared })
}

export function useDailySales(params: DailySalesParams, enabled: boolean) {
  return useQuery({ queryKey: adminReportKeys.report('daily', params), queryFn: () => fetchDailySales(params), enabled, ...shared })
}

export function useTopStores(params: ReportRange & { metric: StoreMetric; limit: number }, enabled: boolean) {
  return useQuery({ queryKey: adminReportKeys.report('topStores', params), queryFn: () => fetchTopStores(params), enabled, ...shared })
}

export function useTopProducts(params: ReportRange & { limit: number }, enabled: boolean) {
  return useQuery({ queryKey: adminReportKeys.report('topProducts', params), queryFn: () => fetchTopProducts(params), enabled, ...shared })
}

export function useDeliveryPerformance(range: ReportRange, enabled: boolean) {
  return useQuery({ queryKey: adminReportKeys.report('delivery', range), queryFn: () => fetchDeliveryPerformance(range), enabled, ...shared })
}

export function usePromotionsReport(range: ReportRange, enabled: boolean) {
  return useQuery({ queryKey: adminReportKeys.report('promotions', range), queryFn: () => fetchPromotions(range), enabled, ...shared })
}

export function useRatingsReport(params: ReportRange & { targetType?: string; limit: number }, enabled: boolean) {
  return useQuery({ queryKey: adminReportKeys.report('ratings', params), queryFn: () => fetchRatings(params), enabled, ...shared })
}

export function usePurchasingReport(params: ReportRange & { vendorId?: string }, enabled: boolean) {
  return useQuery({ queryKey: adminReportKeys.report('purchasing', params), queryFn: () => fetchPurchasing(params), enabled, ...shared })
}
