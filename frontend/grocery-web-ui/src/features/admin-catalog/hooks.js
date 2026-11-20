import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { catalogKeys } from '../catalog/api'
import { vendorAdminApi, vendorKeys } from '../vendor/api'
import {
  activateCategory,
  adminCatalogKeys,
  createCategory,
  deactivateCategory,
  deleteCategory,
  fetchAdminCategories,
  fetchAdminProduct,
  fetchSearchIndexes,
  listAdminProducts,
  republishCatalog,
  republishPrices,
  republishRestaurantListings,
  republishStock,
  updateCategory,
} from './api'

/** @typedef {import('./api').AdminProductListParams} AdminProductListParams */

export function useAdminCategories() {
  return useQuery({ queryKey: adminCatalogKeys.categories(), queryFn: fetchAdminCategories })
}

/**
 * Category changes refetch the admin tree and the public tree (which follows within seconds).
 * @template TVariables
 * @template TResult
 * @param {(variables: TVariables) => Promise<TResult>} mutationFn
 */
function useCategoryMutation(mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: adminCatalogKeys.categories() })
      void queryClient.invalidateQueries({ queryKey: catalogKeys.categories() })
    },
  })
}

export function useSaveCategory() {
  return useCategoryMutation(({ id, body }) => (id ? updateCategory(id, body) : createCategory(body)))
}

/** @typedef {'activate' | 'deactivate' | 'delete'} CategoryAction */

export function useCategoryAction() {
  return useCategoryMutation(async ({ action, id }) => {
    if (action === 'activate') await activateCategory(id)
    else if (action === 'deactivate') await deactivateCategory(id)
    else await deleteCategory(id)
  })
}

/** @param {AdminProductListParams} params */
export function useAdminProducts(params) {
  return useQuery({
    queryKey: adminCatalogKeys.products(params),
    queryFn: () => listAdminProducts(params),
    placeholderData: keepPreviousData,
  })
}

/** @param {string} id */
export function useAdminProduct(id) {
  return useQuery({ queryKey: adminCatalogKeys.product(id), queryFn: () => fetchAdminProduct(id) })
}

/** Vendors for the product filter (first 100, any status). */
export function useVendorOptions() {
  const params = { page: 0, size: 100 }
  return useQuery({
    queryKey: vendorKeys.adminList(params),
    queryFn: () => vendorAdminApi.list(params),
    select: (page) =>
      page.content
        .map((vendor) => ({ id: vendor.id, name: vendor.businessName ?? 'Unnamed vendor' }))
        .sort((a, b) => a.name.localeCompare(b.name)),
    staleTime: 60_000,
  })
}

export function useSearchIndexes() {
  return useQuery({ queryKey: adminCatalogKeys.indexes(), queryFn: fetchSearchIndexes, refetchInterval: 30_000 })
}

/**
 * @template TResult
 * @param {() => Promise<TResult>} mutationFn
 */
function useRepublishMutation(mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSettled: () => queryClient.invalidateQueries({ queryKey: adminCatalogKeys.indexes() }),
  })
}

export function useRepublishCatalog() {
  return useRepublishMutation(republishCatalog)
}

export function useRepublishListings() {
  return useRepublishMutation(republishRestaurantListings)
}

export function useRepublishPrices() {
  return useRepublishMutation(republishPrices)
}

export function useRepublishStock() {
  return useRepublishMutation(republishStock)
}
