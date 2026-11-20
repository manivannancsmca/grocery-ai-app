import type { RouteObject } from 'react-router'
import { RequireRole } from '../../shared/auth/guards'

export const deliveryRoutes: RouteObject = {
  path: 'delivery',
  children: [
    {
      element: <RequireRole roles={['DELIVERY_PARTNER']} />,
      children: [
        {
          // Phase 9: onboarding until approved, then the partner app (online switch, offers, active delivery).
          index: true,
          lazy: async () => {
            const { DeliveryDashboardPage } = await import('./pages/DeliveryDashboardPage')
            return { Component: DeliveryDashboardPage }
          },
        },
        {
          path: 'profile',
          lazy: async () => {
            const { DeliveryProfilePage } = await import('./pages/DeliveryProfilePage')
            return { Component: DeliveryProfilePage }
          },
        },
        {
          path: 'history',
          lazy: async () => {
            const { DeliveryHistoryPage } = await import('./pages/DeliveryHistoryPage')
            return { Component: DeliveryHistoryPage }
          },
        },
      ],
    },
    {
      // Phase 12 §6: the delivery assistant's actions — partners and delivery managers (a manager's reassignments).
      path: 'assistant',
      element: <RequireRole roles={['DELIVERY_PARTNER', 'DELIVERY_MANAGER']} />,
      children: [
        {
          index: true,
          lazy: async () => {
            const { DeliveryAssistantPage } = await import('./pages/DeliveryAssistantPage')
            return { Component: DeliveryAssistantPage }
          },
        },
      ],
    },
  ],
}
