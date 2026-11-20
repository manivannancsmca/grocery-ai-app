import { RequireRole } from '../../shared/auth/guards'

/** @typedef {import('react-router').RouteObject} RouteObject */

/** @type {RouteObject} */
export const restaurantRoutes = {
  path: 'restaurant',
  element: <RequireRole roles={['RESTAURANT_OWNER']} />,
  children: [
    {
      // Phase 8: new-order alerts (sound, flashing tab title) on every restaurant page.
      lazy: async () => {
        const { StoreArea } = await import('../../features/store-orders/StoreArea')
        return { Component: StoreArea }
      },
      children: [
        {
          index: true,
          lazy: async () => {
            const { RestaurantDashboardPage } = await import('./pages/RestaurantDashboardPage')
            return { Component: RestaurantDashboardPage }
          },
        },
        {
          path: 'profile',
          lazy: async () => {
            const { RestaurantProfilePage } = await import('./pages/RestaurantProfilePage')
            return { Component: RestaurantProfilePage }
          },
        },
        {
          path: 'menu',
          lazy: async () => {
            const { MenuPage } = await import('./pages/MenuPage')
            return { Component: MenuPage }
          },
        },
        // Phase 8: the live order board and the sales report.
        {
          path: 'orders',
          lazy: async () => {
            const { RestaurantOrdersPage } = await import('./pages/RestaurantOrdersPage')
            return { Component: RestaurantOrdersPage }
          },
        },
        {
          path: 'reports',
          lazy: async () => {
            const { RestaurantReportsPage } = await import('./pages/RestaurantReportsPage')
            return { Component: RestaurantReportsPage }
          },
        },
        // Phase 12: reviews, returns ("report a problem"), coupons and the restaurant assistant's actions.
        {
          path: 'reviews',
          lazy: async () => {
            const { RestaurantReviewsPage } = await import('./pages/RestaurantReviewsPage')
            return { Component: RestaurantReviewsPage }
          },
        },
        {
          path: 'returns',
          lazy: async () => {
            const { RestaurantReturnsPage } = await import('./pages/RestaurantReturnsPage')
            return { Component: RestaurantReturnsPage }
          },
        },
        {
          path: 'coupons',
          lazy: async () => {
            const { RestaurantCouponsPage } = await import('./pages/RestaurantCouponsPage')
            return { Component: RestaurantCouponsPage }
          },
        },
        {
          path: 'assistant',
          lazy: async () => {
            const { RestaurantAssistantPage } = await import('./pages/RestaurantAssistantPage')
            return { Component: RestaurantAssistantPage }
          },
        },
      ],
    },
  ],
}
