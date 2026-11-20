import type { RouteObject } from 'react-router'
import { RequireRole } from '../../shared/auth/guards'

export const vendorRoutes: RouteObject = {
  path: 'vendor',
  element: <RequireRole roles={['VENDOR']} />,
  children: [
    {
      // Phase 8: new-order alerts (sound, flashing tab title) on every vendor page.
      lazy: async () => {
        const { StoreArea } = await import('../../features/store-orders/StoreArea')
        return { Component: StoreArea }
      },
      children: [
        {
          index: true,
          lazy: async () => {
            const { VendorDashboardPage } = await import('./pages/VendorDashboardPage')
            return { Component: VendorDashboardPage }
          },
        },
        {
          path: 'profile',
          lazy: async () => {
            const { VendorProfilePage } = await import('./pages/VendorProfilePage')
            return { Component: VendorProfilePage }
          },
        },
        {
          path: 'products',
          lazy: async () => {
            const { VendorProductsPage } = await import('./pages/VendorProductsPage')
            return { Component: VendorProductsPage }
          },
        },
        {
          path: 'products/new',
          lazy: async () => {
            const { VendorProductEditorPage } = await import('./pages/VendorProductEditorPage')
            return { Component: VendorProductEditorPage }
          },
        },
        {
          path: 'products/:productId',
          lazy: async () => {
            const { VendorProductEditorPage } = await import('./pages/VendorProductEditorPage')
            return { Component: VendorProductEditorPage }
          },
        },
        {
          path: 'inventory',
          lazy: async () => {
            const { VendorInventoryPage } = await import('./pages/VendorInventoryPage')
            return { Component: VendorInventoryPage }
          },
        },
        {
          path: 'pricing',
          lazy: async () => {
            const { VendorPricingPage } = await import('./pages/VendorPricingPage')
            return { Component: VendorPricingPage }
          },
        },
        // Phase 8: the order board and the sales report.
        {
          path: 'orders',
          lazy: async () => {
            const { VendorOrdersPage } = await import('./pages/VendorOrdersPage')
            return { Component: VendorOrdersPage }
          },
        },
        {
          path: 'reports',
          lazy: async () => {
            const { VendorReportsPage } = await import('./pages/VendorReportsPage')
            return { Component: VendorReportsPage }
          },
        },
        // Phase 12: reviews, returns, coupons, purchasing and the vendor assistant's actions.
        {
          path: 'reviews',
          lazy: async () => {
            const { VendorReviewsPage } = await import('./pages/VendorReviewsPage')
            return { Component: VendorReviewsPage }
          },
        },
        {
          path: 'returns',
          lazy: async () => {
            const { VendorReturnsPage } = await import('./pages/VendorReturnsPage')
            return { Component: VendorReturnsPage }
          },
        },
        {
          path: 'coupons',
          lazy: async () => {
            const { VendorCouponsPage } = await import('./pages/VendorCouponsPage')
            return { Component: VendorCouponsPage }
          },
        },
        {
          path: 'suppliers',
          lazy: async () => {
            const { VendorSuppliersPage } = await import('./pages/VendorSuppliersPage')
            return { Component: VendorSuppliersPage }
          },
        },
        {
          path: 'purchase-orders',
          lazy: async () => {
            const { VendorPurchaseOrdersPage } = await import('./pages/VendorPurchaseOrdersPage')
            return { Component: VendorPurchaseOrdersPage }
          },
        },
        {
          path: 'purchase-orders/new',
          lazy: async () => {
            const { VendorPurchaseOrderEditorPage } = await import('./pages/VendorPurchaseOrderEditorPage')
            return { Component: VendorPurchaseOrderEditorPage }
          },
        },
        {
          path: 'purchase-orders/:purchaseOrderId',
          lazy: async () => {
            const { VendorPurchaseOrderPage } = await import('./pages/VendorPurchaseOrderPage')
            return { Component: VendorPurchaseOrderPage }
          },
        },
        {
          path: 'purchase-orders/:purchaseOrderId/edit',
          lazy: async () => {
            const { VendorPurchaseOrderEditorPage } = await import('./pages/VendorPurchaseOrderEditorPage')
            return { Component: VendorPurchaseOrderEditorPage }
          },
        },
        {
          path: 'purchase-orders/:purchaseOrderId/print',
          lazy: async () => {
            const { VendorPurchaseOrderPrintPage } = await import('./pages/VendorPurchaseOrderPrintPage')
            return { Component: VendorPurchaseOrderPrintPage }
          },
        },
        {
          path: 'assistant',
          lazy: async () => {
            const { VendorAssistantPage } = await import('./pages/VendorAssistantPage')
            return { Component: VendorAssistantPage }
          },
        },
      ],
    },
  ],
}
