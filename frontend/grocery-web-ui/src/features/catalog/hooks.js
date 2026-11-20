import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import {
  catalogKeys,
  fetchCategoryTree,
  fetchProduct,
  fetchSuggestions,
  MIN_SUGGEST_LENGTH,
  searchProducts,
} from './api'

/** @typedef {import('./api').ProductSearchParams} ProductSearchParams */

/**
 * Product search; the previous page stays on screen (dimmed) while the next one loads.
 * @param {ProductSearchParams} params
 * @param {{ enabled?: boolean }} [options={}]
 */
export function useProductSearch(params, options = {}) {
  return useQuery({
    queryKey: catalogKeys.search(params),
    queryFn: () => searchProducts(params),
    placeholderData: keepPreviousData,
    enabled: options.enabled ?? true,
  })
}

/**
 * The storefront's live product grid. Stock and price changes arrive as public `STOCK_CHANGED` /
 * `PRICE_CHANGED` signals, which refetch every product search. Newly published products have no signal, so a
 * slow poll picks them up while the stream is live, and a faster one stands in for it while it is down (guests
 * have no stream at all). Polling pauses while the tab is in the background.
 */
export const LIVE_CATALOG_POLL_MS = 60_000
export const LIVE_CATALOG_FALLBACK_POLL_MS = 30_000

/**
 * @param {ProductSearchParams} params
 * @param {{ enabled?: boolean }} [options={}]
 */
export function useLiveProductCatalog(params, options = {}) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: catalogKeys.search(params),
    queryFn: () => searchProducts(params),
    placeholderData: keepPreviousData,
    refetchInterval: live ? LIVE_CATALOG_POLL_MS : LIVE_CATALOG_FALLBACK_POLL_MS,
    enabled: options.enabled ?? true,
  })
}

/**
 * Autocomplete for the search box; idle below two characters. Stale answers are cancelled.
 * @param {string} q
 */
export function useSuggestions(q) {
  const term = q.trim()
  return useQuery({
    queryKey: catalogKeys.suggest(term.toLowerCase()),
    queryFn: ({ signal }) => fetchSuggestions(term, signal),
    enabled: term.length >= MIN_SUGGEST_LENGTH,
    staleTime: 60_000,
    placeholderData: keepPreviousData,
  })
}

/** The active category tree. It changes rarely, so it is cached for a few minutes. */
export function useCategoryTree() {
  return useQuery({ queryKey: catalogKeys.categories(), queryFn: fetchCategoryTree, staleTime: 5 * 60_000 })
}

/** @param {string} id */
export function useProduct(id) {
  return useQuery({ queryKey: catalogKeys.product(id), queryFn: () => fetchProduct(id) })
}
