import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  adminReportKeys,
  fetchDailySales,
  fetchDeliveryPerformance,
  fetchOverview,
  fetchPromotions,
  fetchPurchasing,
  fetchRatings,
  fetchTopProducts,
  fetchTopStores,
} from './api'

/** @typedef {import('./api').DailySalesParams} DailySalesParams */
/** @typedef {import('./api').ReportRange} ReportRange */
/** @typedef {import('./api').StoreMetric} StoreMetric */

/** Every report query: only for a valid range, the previous answer stays while a new range loads. */
const shared = { placeholderData: keepPreviousData, staleTime: 60_000 }

/**
 * @param {ReportRange} range
 * @param {boolean} enabled
 */
export function useReportOverview(range, enabled) {
  return useQuery({
    queryKey: adminReportKeys.report('overview', range),
    queryFn: () => fetchOverview(range),
    enabled,
    ...shared,
  })
}

/**
 * @param {DailySalesParams} params
 * @param {boolean} enabled
 */
export function useDailySales(params, enabled) {
  return useQuery({
    queryKey: adminReportKeys.report('daily', params),
    queryFn: () => fetchDailySales(params),
    enabled,
    ...shared,
  })
}

/**
 * @param {ReportRange & { metric: StoreMetric, limit: number }} params
 * @param {boolean} enabled
 */
export function useTopStores(params, enabled) {
  return useQuery({
    queryKey: adminReportKeys.report('topStores', params),
    queryFn: () => fetchTopStores(params),
    enabled,
    ...shared,
  })
}

/**
 * @param {ReportRange & { limit: number }} params
 * @param {boolean} enabled
 */
export function useTopProducts(params, enabled) {
  return useQuery({
    queryKey: adminReportKeys.report('topProducts', params),
    queryFn: () => fetchTopProducts(params),
    enabled,
    ...shared,
  })
}

/**
 * @param {ReportRange} range
 * @param {boolean} enabled
 */
export function useDeliveryPerformance(range, enabled) {
  return useQuery({
    queryKey: adminReportKeys.report('delivery', range),
    queryFn: () => fetchDeliveryPerformance(range),
    enabled,
    ...shared,
  })
}

/**
 * @param {ReportRange} range
 * @param {boolean} enabled
 */
export function usePromotionsReport(range, enabled) {
  return useQuery({
    queryKey: adminReportKeys.report('promotions', range),
    queryFn: () => fetchPromotions(range),
    enabled,
    ...shared,
  })
}

/**
 * @param {ReportRange & { targetType?: string, limit: number }} params
 * @param {boolean} enabled
 */
export function useRatingsReport(params, enabled) {
  return useQuery({
    queryKey: adminReportKeys.report('ratings', params),
    queryFn: () => fetchRatings(params),
    enabled,
    ...shared,
  })
}

/**
 * @param {ReportRange & { vendorId?: string }} params
 * @param {boolean} enabled
 */
export function usePurchasingReport(params, enabled) {
  return useQuery({
    queryKey: adminReportKeys.report('purchasing', params),
    queryFn: () => fetchPurchasing(params),
    enabled,
    ...shared,
  })
}
