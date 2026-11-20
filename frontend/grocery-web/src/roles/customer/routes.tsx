import { Navigate, type RouteObject } from 'react-router'
import { HomeGate, RequireRole } from '../../shared/auth/guards'

/**
 * The public storefront lives at the site root for guests; signed-in customers are sent to `/customer/home`
 * (the same storefront, with their name and account shortcuts) and other roles to their dashboards.
 * Search, product and restaurant pages are public for everyone — guests, customers and every other
 * role — so they sit outside any guard. Account pages need a CUSTOMER session.
 */
export const customerRoutes: RouteObject[] = [
  {
    element: <HomeGate />,
    children: [
      {
        index: true,
        lazy: async () => {
          const { StorefrontPage } = await import('./pages/StorefrontPage')
          return { Component: StorefrontPage }
        },
      },
    ],
  },
  {
    path: 'customer',
    element: <RequireRole roles={['CUSTOMER']} />,
    children: [
      { index: true, element: <Navigate to="home" replace /> },
      {
        path: 'home',
        lazy: async () => {
          const { StorefrontPage } = await import('./pages/StorefrontPage')
          return { Component: StorefrontPage }
        },
      },
    ],
  },
  {
    path: 'search',
    lazy: async () => {
      const { SearchPage } = await import('./pages/SearchPage')
      return { Component: SearchPage }
    },
  },
  {
    path: 'products/:productId',
    lazy: async () => {
      const { ProductDetailPage } = await import('./pages/ProductDetailPage')
      return { Component: ProductDetailPage }
    },
  },
  {
    path: 'restaurants',
    lazy: async () => {
      const { RestaurantsPage } = await import('./pages/RestaurantsPage')
      return { Component: RestaurantsPage }
    },
  },
  {
    // Phase 15: a grocery store and its shelves (from "Grocery stores near you").
    path: 'stores/:storeId',
    lazy: async () => {
      const { StorePage } = await import('./pages/StorePage')
      return { Component: StorePage }
    },
  },
  {
    path: 'restaurants/:restaurantId',
    lazy: async () => {
      const { RestaurantDetailPage } = await import('./pages/RestaurantDetailPage')
      return { Component: RestaurantDetailPage }
    },
  },
  {
    // Phase 5: the cart page (same content as the header drawer) and the checkout preview.
    element: <RequireRole roles={['CUSTOMER']} />,
    children: [
      {
        path: 'cart',
        lazy: async () => {
          const { CartPage } = await import('./pages/CartPage')
          return { Component: CartPage }
        },
      },
      {
        path: 'checkout',
        lazy: async () => {
          const { CheckoutPage } = await import('./pages/CheckoutPage')
          return { Component: CheckoutPage }
        },
      },
      // Phase 6: the customer's orders and one order (status, payment, cancel).
      {
        path: 'orders',
        lazy: async () => {
          const { OrdersPage } = await import('./pages/OrdersPage')
          return { Component: OrdersPage }
        },
      },
      {
        path: 'orders/:orderId',
        lazy: async () => {
          const { OrderPage } = await import('./pages/OrderPage')
          return { Component: OrderPage }
        },
      },
      // Phase 12: rate a delivered order (each item, the store, the delivery partner).
      {
        path: 'orders/:orderId/review',
        lazy: async () => {
          const { RateOrderPage } = await import('./pages/RateOrderPage')
          return { Component: RateOrderPage }
        },
      },
      { path: 'returns', element: <Navigate to="/account/returns" replace /> },
      // Phase 11: what the shopping assistant prepared (the chat itself is the panel on every page).
      { path: 'assistant', element: <Navigate to="/assistant/approvals" replace /> },
      {
        path: 'assistant/approvals',
        lazy: async () => {
          const { AssistantApprovalsPage } = await import('./pages/AssistantApprovalsPage')
          return { Component: AssistantApprovalsPage }
        },
      },
    ],
  },
  {
    path: 'account',
    element: <RequireRole roles={['CUSTOMER']} />,
    children: [
      { index: true, element: <Navigate to="profile" replace /> },
      {
        path: 'profile',
        lazy: async () => {
          const { ProfilePage } = await import('./pages/ProfilePage')
          return { Component: ProfilePage }
        },
      },
      {
        path: 'addresses',
        lazy: async () => {
          const { AddressesPage } = await import('./pages/AddressesPage')
          return { Component: AddressesPage }
        },
      },
      // Phase 12: the customer's reviews and returns.
      {
        path: 'reviews',
        lazy: async () => {
          const { MyReviewsPage } = await import('./pages/MyReviewsPage')
          return { Component: MyReviewsPage }
        },
      },
      {
        path: 'returns',
        lazy: async () => {
          const { MyReturnsPage } = await import('./pages/MyReturnsPage')
          return { Component: MyReturnsPage }
        },
      },
      // Help & support: raise a complaint (`?orderId=` preselects an order) and read our answers.
      {
        path: 'complaints',
        lazy: async () => {
          const { HelpSupportPage } = await import('./pages/HelpSupportPage')
          return { Component: HelpSupportPage }
        },
      },
    ],
  },
]
