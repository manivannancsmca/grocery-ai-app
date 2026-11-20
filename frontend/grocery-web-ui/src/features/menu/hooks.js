import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { provisioningRetryDelay, retryWhileProvisioning } from '../../shared/api/retry'
import { restaurantKeys } from '../restaurant/api'
import {
  createMenuCategory,
  createMenuItem,
  deleteMenuCategory,
  deleteMenuItem,
  fetchAdminRestaurantMenu,
  fetchMyMenu,
  menuKeys,
  RESTAURANT_NOT_FOUND,
  setAcceptingOrders,
  setMenuItemAvailability,
  updateMenuCategory,
  updateMenuItem,
} from './api'

/** @typedef {import('./api').MenuView} MenuView */

/** The owner's menu. Right after registration the restaurant profile is still being created (404, retried). */
export function useMyMenu() {
  return useQuery({
    queryKey: menuKeys.mine,
    queryFn: fetchMyMenu,
    retry: retryWhileProvisioning(RESTAURANT_NOT_FOUND),
    retryDelay: provisioningRetryDelay,
  })
}

/**
 * Every menu change refetches the menu afterwards (sort order and counts are computed by the server).
 * @template TVariables
 * @template TResult
 * @param {(variables: TVariables) => Promise<TResult>} mutationFn
 */
function useMenuMutation(mutationFn) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSettled: () => queryClient.invalidateQueries({ queryKey: menuKeys.mine }),
  })
}

export function useSaveMenuCategory() {
  return useMenuMutation(({ id, body }) => (id ? updateMenuCategory(id, body) : createMenuCategory(body)))
}

export function useDeleteMenuCategory() {
  return useMenuMutation((id) => deleteMenuCategory(id))
}

export function useSaveMenuItem() {
  return useMenuMutation(({ id, body }) => (id ? updateMenuItem(id, body) : createMenuItem(body)))
}

export function useDeleteMenuItem() {
  return useMenuMutation((id) => deleteMenuItem(id))
}

/**
 * @param {MenuView} menu
 * @param {string} itemId
 * @param {boolean} available
 * @returns {MenuView}
 */
function withItemAvailability(menu, itemId, available) {
  return {
    ...menu,
    categories: menu.categories.map((category) => ({
      ...category,
      items: category.items.map((item) => (item.id === itemId ? { ...item, available } : item)),
    })),
  }
}

/** Sold-out toggle: flips the switch at once and rolls back if the request fails. */
export function useSetItemAvailability() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, available }) => setMenuItemAvailability(id, available),
    onMutate: async ({ id, available }) => {
      await queryClient.cancelQueries({ queryKey: menuKeys.mine })
      const previous = queryClient.getQueryData(menuKeys.mine)
      if (previous) queryClient.setQueryData(menuKeys.mine, withItemAvailability(previous, id, available))
      return { previous }
    },
    onError: (_error, _variables, context) => {
      if (context?.previous) queryClient.setQueryData(menuKeys.mine, context.previous)
    },
    onSuccess: (item) => {
      const current = queryClient.getQueryData(menuKeys.mine)
      if (current) queryClient.setQueryData(menuKeys.mine, withItemAvailability(current, item.id, item.available))
    },
  })
}

/** The "accepting orders" switch; keeps the menu and restaurant-profile caches in sync. */
export function useSetAcceptingOrders() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (acceptingOrders) => setAcceptingOrders(acceptingOrders),
    onSuccess: ({ acceptingOrders }) => {
      queryClient.setQueryData(menuKeys.mine, (menu) => (menu ? { ...menu, acceptingOrders } : menu))
      queryClient.setQueryData(restaurantKeys.mine, (restaurant) =>
        restaurant ? { ...restaurant, acceptingOrders } : restaurant,
      )
    },
  })
}

/** @param {string} restaurantId */
export function useAdminRestaurantMenu(restaurantId) {
  return useQuery({ queryKey: menuKeys.admin(restaurantId), queryFn: () => fetchAdminRestaurantMenu(restaurantId) })
}
