import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { provisioningRetryDelay, retryWhileProvisioning } from '../../shared/api/retry'
import { restaurantKeys, type RestaurantView } from '../restaurant/api'
import {
  createMenuCategory,
  createMenuItem,
  deleteMenuCategory,
  deleteMenuItem,
  fetchAdminRestaurantMenu,
  fetchMyMenu,
  type MenuCategoryRequest,
  type MenuItemRequest,
  type MenuView,
  menuKeys,
  RESTAURANT_NOT_FOUND,
  setAcceptingOrders,
  setMenuItemAvailability,
  updateMenuCategory,
  updateMenuItem,
} from './api'

/** The owner's menu. Right after registration the restaurant profile is still being created (404, retried). */
export function useMyMenu() {
  return useQuery({
    queryKey: menuKeys.mine,
    queryFn: fetchMyMenu,
    retry: retryWhileProvisioning(RESTAURANT_NOT_FOUND),
    retryDelay: provisioningRetryDelay,
  })
}

/** Every menu change refetches the menu afterwards (sort order and counts are computed by the server). */
function useMenuMutation<TVariables, TResult>(mutationFn: (variables: TVariables) => Promise<TResult>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSettled: () => queryClient.invalidateQueries({ queryKey: menuKeys.mine }),
  })
}

export function useSaveMenuCategory() {
  return useMenuMutation(({ id, body }: { id?: string; body: MenuCategoryRequest }) =>
    id ? updateMenuCategory(id, body) : createMenuCategory(body),
  )
}

export function useDeleteMenuCategory() {
  return useMenuMutation((id: string) => deleteMenuCategory(id))
}

export function useSaveMenuItem() {
  return useMenuMutation(({ id, body }: { id?: string; body: MenuItemRequest }) => (id ? updateMenuItem(id, body) : createMenuItem(body)))
}

export function useDeleteMenuItem() {
  return useMenuMutation((id: string) => deleteMenuItem(id))
}

function withItemAvailability(menu: MenuView, itemId: string, available: boolean): MenuView {
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
    mutationFn: ({ id, available }: { id: string; available: boolean }) => setMenuItemAvailability(id, available),
    onMutate: async ({ id, available }) => {
      await queryClient.cancelQueries({ queryKey: menuKeys.mine })
      const previous = queryClient.getQueryData<MenuView>(menuKeys.mine)
      if (previous) queryClient.setQueryData(menuKeys.mine, withItemAvailability(previous, id, available))
      return { previous }
    },
    onError: (_error, _variables, context) => {
      if (context?.previous) queryClient.setQueryData(menuKeys.mine, context.previous)
    },
    onSuccess: (item) => {
      const current = queryClient.getQueryData<MenuView>(menuKeys.mine)
      if (current) queryClient.setQueryData(menuKeys.mine, withItemAvailability(current, item.id, item.available))
    },
  })
}

/** The "accepting orders" switch; keeps the menu and restaurant-profile caches in sync. */
export function useSetAcceptingOrders() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (acceptingOrders: boolean) => setAcceptingOrders(acceptingOrders),
    onSuccess: ({ acceptingOrders }) => {
      queryClient.setQueryData<MenuView>(menuKeys.mine, (menu) => (menu ? { ...menu, acceptingOrders } : menu))
      queryClient.setQueryData<RestaurantView>(restaurantKeys.mine, (restaurant) =>
        restaurant ? { ...restaurant, acceptingOrders } : restaurant,
      )
    },
  })
}

export function useAdminRestaurantMenu(restaurantId: string) {
  return useQuery({ queryKey: menuKeys.admin(restaurantId), queryFn: () => fetchAdminRestaurantMenu(restaurantId) })
}
