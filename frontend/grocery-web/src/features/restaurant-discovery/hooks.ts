import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { discoveryKeys, fetchRestaurantDetail, type RestaurantSearchParams, searchRestaurants } from './api'

export function useRestaurantSearch(params: RestaurantSearchParams, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: discoveryKeys.search(params),
    queryFn: () => searchRestaurants(params),
    placeholderData: keepPreviousData,
    enabled: options.enabled ?? true,
  })
}

export function useRestaurantDetail(id: string) {
  return useQuery({ queryKey: discoveryKeys.detail(id), queryFn: () => fetchRestaurantDetail(id) })
}
