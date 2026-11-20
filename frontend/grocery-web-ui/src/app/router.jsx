import { createBrowserRouter } from 'react-router'
import { adminRoutes } from '../roles/admin/routes'
import { customerRoutes } from '../roles/customer/routes'
import { deliveryRoutes } from '../roles/delivery/routes'
import { restaurantRoutes } from '../roles/restaurant/routes'
import { vendorRoutes } from '../roles/vendor/routes'
import { GuestOnly, RequireAuth } from '../shared/auth/guards'
import { PageLoader } from '../shared/components/ui'
import { AppLayout } from './layout/AppLayout'
import { NotFoundPage } from './pages/NotFoundPage'
import { RouteErrorPage } from './pages/RouteErrorPage'

/** @typedef {import('react-router').RouteObject} RouteObject */

/**
 * Route tree. Each role area lives in src/roles/<role>/routes.jsx, guards itself with `RequireRole`
 * and lazy-loads its pages, so every role's code is split into its own chunk.
 * @type {RouteObject[]}
 */
export const routes = [
  {
    path: '/',
    element: <AppLayout />,
    errorElement: <RouteErrorPage />,
    hydrateFallbackElement: <PageLoader />,
    children: [
      ...customerRoutes,
      {
        element: <GuestOnly />,
        children: [
          {
            path: 'login',
            lazy: async () => {
              const { LoginPage } = await import('../features/auth/LoginPage')
              return { Component: LoginPage }
            },
          },
          {
            path: 'register',
            lazy: async () => {
              const { RegisterPage } = await import('../features/auth/RegisterPage')
              return { Component: RegisterPage }
            },
          },
        ],
      },
      {
        path: 'account-suspended',
        lazy: async () => {
          const { AccountSuspendedPage } = await import('../features/auth/AccountSuspendedPage')
          return { Component: AccountSuspendedPage }
        },
      },
      // Password reset: public, and open while signed in too (the emailed link may be opened in a signed-in browser).
      {
        path: 'forgot-password',
        lazy: async () => {
          const { ForgotPasswordPage } = await import('../features/auth/ForgotPasswordPage')
          return { Component: ForgotPasswordPage }
        },
      },
      {
        path: 'reset-password',
        lazy: async () => {
          const { ResetPasswordPage } = await import('../features/auth/ResetPasswordPage')
          return { Component: ResetPasswordPage }
        },
      },
      {
        // Pages for every signed-in role (Phase 7: the notification inbox).
        element: <RequireAuth />,
        children: [
          {
            path: 'notifications',
            lazy: async () => {
              const { NotificationsPage } = await import('../features/notifications/NotificationsPage')
              return { Component: NotificationsPage }
            },
          },
          // Phase 12 §5: which notifications are also emailed.
          {
            path: 'notifications/preferences',
            lazy: async () => {
              const { NotificationPreferencesPage } = await import(
                '../features/notifications/NotificationPreferencesPage'
              )
              return { Component: NotificationPreferencesPage }
            },
          },
          // Change password (the account menu links here for every role).
          {
            path: 'account/password',
            lazy: async () => {
              const { ChangePasswordPage } = await import('../features/auth/ChangePasswordPage')
              return { Component: ChangePasswordPage }
            },
          },
        ],
      },
      vendorRoutes,
      restaurantRoutes,
      deliveryRoutes,
      adminRoutes,
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

export function createAppRouter() {
  return createBrowserRouter(routes)
}
