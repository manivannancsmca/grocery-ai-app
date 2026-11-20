import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { discoveryKeys, fetchRestaurantDetail, searchRestaurants } from './api'

/** @typedef {import('./api').RestaurantSearchParams} RestaurantSearchParams */

/**
 * @param {RestaurantSearchParams} params
 * @param {{ enabled?: boolean }} [options={}]
 */
export function useRestaurantSearch(params, options = {}) {
  return useQuery({
    queryKey: discoveryKeys.search(params),
    queryFn: () => searchRestaurants(params),
    placeholderData: keepPreviousData,
    enabled: options.enabled ?? true,
  })
}

/** @param {string} id */
export function useRestaurantDetail(id) {
  return useQuery({ queryKey: discoveryKeys.detail(id), queryFn: () => fetchRestaurantDetail(id) })
}
