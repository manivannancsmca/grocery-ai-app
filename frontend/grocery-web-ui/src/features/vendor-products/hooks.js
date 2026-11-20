import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  archiveVendorProduct,
  createVendorProduct,
  fetchBrandSuggestions,
  fetchVendorProduct,
  listVendorProducts,
  publishVendorProduct,
  unpublishVendorProduct,
  updateVendorProduct,
  vendorProductKeys,
} from './api'

/** @typedef {import('./api').ProductStatus} ProductStatus */
/** @typedef {import('./api').VendorProductListParams} VendorProductListParams */

/** @param {VendorProductListParams} params */
export function useVendorProducts(params) {
  return useQuery({
    queryKey: vendorProductKeys.list(params),
    queryFn: () => listVendorProducts(params),
    placeholderData: keepPreviousData,
  })
}

/**
 * Number of products in one status (a one-row page; only `totalElements` is used).
 * @param {VendorProductListParams['status']} status
 */
export function useVendorProductCount(status) {
  const params = { status, page: 0, size: 1 }
  return useQuery({
    queryKey: vendorProductKeys.list(params),
    queryFn: () => listVendorProducts(params),
    select: (page) => page.totalElements,
  })
}

/**
 * Products per status (undefined while loading or after an error); `ALL` is every status but archived.
 * @typedef {Record<ProductStatus | 'ALL', number | undefined>} ProductCounts
 */

/**
 * The four status counts (one-row pages, shared with the dashboard's product tile) and their sum.
 * @returns {ProductCounts}
 */
export function useVendorProductCounts() {
  const drafts = useVendorProductCount('DRAFT')
  const published = useVendorProductCount('PUBLISHED')
  const unpublished = useVendorProductCount('UNPUBLISHED')
  const archived = useVendorProductCount('ARCHIVED')
  const all =
    drafts.data !== undefined && published.data !== undefined && unpublished.data !== undefined
      ? drafts.data + published.data + unpublished.data
      : undefined
  return {
    ALL: all,
    DRAFT: drafts.data,
    PUBLISHED: published.data,
    UNPUBLISHED: unpublished.data,
    ARCHIVED: archived.data,
  }
}

/** @param {string | undefined} id */
export function useVendorProduct(id) {
  return useQuery({
    queryKey: vendorProductKeys.detail(id ?? ''),
    queryFn: () => fetchVendorProduct(id ?? ''),
    enabled: Boolean(id),
  })
}

/** @param {string} q */
export function useBrandSuggestions(q) {
  const term = q.trim()
  return useQuery({
    queryKey: vendorProductKeys.brands(term.toLowerCase()),
    queryFn: () => fetchBrandSuggestions(term),
    enabled: term.length >= 1,
    staleTime: 60_000,
    placeholderData: keepPreviousData,
  })
}

/** Create (no id) or update a product; refreshes the lists and caches the saved view. */
export function useSaveVendorProduct() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }) => (id ? updateVendorProduct(id, body) : createVendorProduct(body)),
    onSuccess: (view) => {
      queryClient.setQueryData(vendorProductKeys.detail(view.id), view)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: [...vendorProductKeys.all, 'list'] }),
  })
}

/** Publish / unpublish / archive. Resolves with the new view (null after archiving). */
export function useProductStatusAction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ action, product }) => {
      switch (action) {
        case 'publish':
          return publishVendorProduct(product.id)
        case 'unpublish':
          return unpublishVendorProduct(product.id)
        case 'archive':
          await archiveVendorProduct(product.id)
          return null
      }
    },
    onSuccess: (view, { product }) => {
      if (view) queryClient.setQueryData(vendorProductKeys.detail(view.id), view)
      else void queryClient.invalidateQueries({ queryKey: vendorProductKeys.detail(product.id) })
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: [...vendorProductKeys.all, 'list'] }),
  })
}
