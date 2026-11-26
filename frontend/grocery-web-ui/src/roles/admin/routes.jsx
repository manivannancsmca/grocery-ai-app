import { RequireRole } from '../../shared/auth/guards'
import { ADMIN_ROLES } from '../../shared/auth/roles'
import { AdminArea } from './AdminArea'

/** @typedef {import('react-router').RouteObject} RouteObject */

/**
 * Admin area: any admin role; finer permissions hide write actions inside the pages. AdminArea also gives its pages
 * the colourful "hero" page header.
 * @type {RouteObject}
 */
export const adminRoutes = {
  path: 'admin',
  element: <AdminArea />,
  children: [
    {
      index: true,
      lazy: async () => {
        const { AdminDashboardPage } = await import('./pages/AdminDashboardPage')
        return { Component: AdminDashboardPage }
      },
    },
    {
      path: 'approvals',
      lazy: async () => {
        const { ApprovalsPage } = await import('./pages/ApprovalsPage')
        return { Component: ApprovalsPage }
      },
    },
    {
      path: 'price-approvals',
      lazy: async () => {
        const { PriceApprovalsPage } = await import('./pages/PriceApprovalsPage')
        return { Component: PriceApprovalsPage }
      },
    },
    {
      path: 'vendors',
      lazy: async () => {
        const { VendorsPage } = await import('./pages/StakeholderListPages')
        return { Component: VendorsPage }
      },
    },
    {
      path: 'restaurants',
      lazy: async () => {
        const { RestaurantsPage } = await import('./pages/StakeholderListPages')
        return { Component: RestaurantsPage }
      },
    },
    {
      path: 'delivery-partners',
      lazy: async () => {
        const { DeliveryPartnersPage } = await import('./pages/StakeholderListPages')
        return { Component: DeliveryPartnersPage }
      },
    },
    {
      path: 'customers',
      lazy: async () => {
        const { CustomersPage } = await import('./pages/CustomersPage')
        return { Component: CustomersPage }
      },
    },
    {
      // GET /api/admin/users is limited to SUPER_ADMIN, OPS_ADMIN and SUPPORT.
      path: 'users',
      element: <RequireRole roles={ADMIN_ROLES} permission="users.view" />,
      children: [
        {
          index: true,
          lazy: async () => {
            const { UsersPage } = await import('./pages/UsersPage')
            return { Component: UsersPage }
          },
        },
      ],
    },
    {
      path: 'catalog',
      lazy: async () => {
        const { CatalogPage } = await import('./pages/CatalogPage')
        return { Component: CatalogPage }
      },
    },
    // Phase 6: order and payment monitoring (reads for every admin; cancel / refund are gated inside).
    {
      path: 'orders',
      lazy: async () => {
        const { AdminOrdersPage } = await import('./pages/AdminOrdersPage')
        return { Component: AdminOrdersPage }
      },
    },
    {
      path: 'payments',
      lazy: async () => {
        const { AdminPaymentsPage } = await import('./pages/AdminPaymentsPage')
        return { Component: AdminPaymentsPage }
      },
    },
    // Phase 9: delivery monitor (reads for every admin; reassign is gated inside).
    {
      path: 'deliveries',
      lazy: async () => {
        const { AdminDeliveriesPage } = await import('./pages/AdminDeliveriesPage')
        return { Component: AdminDeliveriesPage }
      },
    },
    // Phase 10: dead letters, connector health and stuck orders (reads for every admin; actions are gated inside).
    {
      path: 'operations',
      lazy: async () => {
        const { OperationsPage } = await import('./pages/OperationsPage')
        return { Component: OperationsPage }
      },
    },
    // Phase 11: what the business assistant proposed (decided here) and the audit of every tool call.
    {
      path: 'ai/approvals',
      lazy: async () => {
        const { AiApprovalsPage } = await import('./pages/AiApprovalsPage')
        return { Component: AiApprovalsPage }
      },
    },
    {
      path: 'ai/audit',
      lazy: async () => {
        const { AiAuditPage } = await import('./pages/AiAuditPage')
        return { Component: AiAuditPage }
      },
    },
    // The help center (knowledge base) the assistants answer policy questions from: every admin looks, two roles change.
    {
      path: 'ai/knowledge',
      lazy: async () => {
        const { KnowledgeBasePage } = await import('./pages/KnowledgeBasePage')
        return { Component: KnowledgeBasePage }
      },
    },
    {
      path: 'system',
      lazy: async () => {
        const { SystemStatusPage } = await import('./pages/SystemStatusPage')
        return { Component: SystemStatusPage }
      },
    },
    // Phase 12 (reads for every admin; moderation, return decisions and coupon management are gated inside).
    {
      path: 'reviews',
      lazy: async () => {
        const { AdminReviewsPage } = await import('./pages/AdminReviewsPage')
        return { Component: AdminReviewsPage }
      },
    },
    {
      path: 'returns',
      lazy: async () => {
        const { AdminReturnsPage } = await import('./pages/AdminReturnsPage')
        return { Component: AdminReturnsPage }
      },
    },
    // Customers' complaints (every admin reads; support, operations and super admins start and resolve them).
    {
      path: 'complaints',
      lazy: async () => {
        const { AdminComplaintsPage } = await import('./pages/AdminComplaintsPage')
        return { Component: AdminComplaintsPage }
      },
    },
    {
      path: 'coupons',
      lazy: async () => {
        const { AdminCouponsPage } = await import('./pages/AdminCouponsPage')
        return { Component: AdminCouponsPage }
      },
    },
    {
      path: 'purchase-orders',
      lazy: async () => {
        const { AdminPurchaseOrdersPage } = await import('./pages/AdminPurchaseOrdersPage')
        return { Component: AdminPurchaseOrdersPage }
      },
    },
    {
      path: 'emails',
      lazy: async () => {
        const { AdminEmailsPage } = await import('./pages/AdminEmailsPage')
        return { Component: AdminEmailsPage }
      },
    },
    {
      path: 'reports',
      lazy: async () => {
        const { AdminReportsPage } = await import('./pages/AdminReportsPage')
        return { Component: AdminReportsPage }
      },
    },
    // Platform settings (every admin may read; SUPER_ADMIN and OPS_ADMIN may change them inside the page).
    {
      path: 'settings',
      element: <RequireRole roles={ADMIN_ROLES} permission="settings.view" />,
      children: [
        {
          index: true,
          lazy: async () => {
            const { PlatformSettingsPage } = await import('./pages/PlatformSettingsPage')
            return { Component: PlatformSettingsPage }
          },
        },
      ],
    },
  ],
}
