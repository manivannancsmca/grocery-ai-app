import type { Permission } from '../../shared/auth/permissions'
import type { RoleArea } from '../../shared/auth/roles'
import type { IconName } from '../../shared/icons'

export interface NavItem {
  label: string
  to: string
  /** Implementation phase that delivers this screen; set while it is not built yet. */
  comingInPhase?: number
  /** Shown only to users with this permission (admin sub-roles). */
  permission?: Permission
  /** Also highlight the item on nested pages (e.g. `/vendor/products/new`). */
  matchNested?: boolean
  /** Icon shown before the label. */
  icon?: IconName
  /** Group heading; consecutive items with the same section are listed under it. */
  section?: string
}

/**
 * Height of the shoppers' bottom tab bar on phones. AppLayout exposes it as `--tabbar-h` so floating buttons
 * (Ask AI) and toasts sit above the bar.
 */
export const TAB_BAR_HEIGHT = '4.25rem'

/** Sidebar navigation per area. Items with `comingInPhase` render disabled with a phase badge. */
export const NAVIGATION: Record<RoleArea, NavItem[]> = {
  // Grouped with icons: shopping first, then what the customer bought, then their account.
  CUSTOMER: [
    { label: 'Home', to: '/customer/home', icon: 'home', section: 'Shop' },
    { label: 'Search', to: '/search', icon: 'search', section: 'Shop' },
    { label: 'Restaurants', to: '/restaurants', matchNested: true, icon: 'cutlery', section: 'Shop' },
    { label: 'Cart', to: '/cart', icon: 'cart', section: 'Orders' },
    { label: 'My orders', to: '/orders', matchNested: true, icon: 'box', section: 'Orders' },
    // Phase 12: the customer's returns and reviews.
    { label: 'My returns', to: '/account/returns', icon: 'return', section: 'Orders' },
    // Complaints: raise one and read our answers.
    { label: 'Help & support', to: '/account/complaints', icon: 'chat', section: 'Orders' },
    { label: 'My reviews', to: '/account/reviews', icon: 'star', section: 'Orders' },
    { label: 'My profile', to: '/account/profile', icon: 'user', section: 'Account' },
    { label: 'Addresses', to: '/account/addresses', icon: 'mapPin', section: 'Account' },
    // Phase 12: which notifications are also emailed.
    { label: 'Email preferences', to: '/notifications/preferences', icon: 'mail', section: 'Account' },
    // Phase 11: the assistant itself is the "Ask AI" panel on every page; this lists what it prepared.
    { label: 'Assistant actions', to: '/assistant/approvals', icon: 'sparkle', section: 'Account' },
  ],
  // Every area: grouped, each item with its picture (the page header shows the same one).
  VENDOR: [
    { label: 'Dashboard', to: '/vendor', icon: 'grid', section: 'Store' },
    { label: 'Business profile', to: '/vendor/profile', icon: 'store', section: 'Store' },
    { label: 'Products', to: '/vendor/products', matchNested: true, icon: 'bag', section: 'Store' },
    { label: 'Inventory', to: '/vendor/inventory', icon: 'box', section: 'Store' },
    { label: 'Pricing', to: '/vendor/pricing', icon: 'tag', section: 'Store' },
    { label: 'Orders', to: '/vendor/orders', icon: 'receipt', section: 'Sales' },
    { label: 'Reports', to: '/vendor/reports', icon: 'chart', section: 'Sales' },
    // Phase 12: reviews, returns, coupons, purchasing and what the vendor assistant prepared.
    { label: 'Reviews', to: '/vendor/reviews', icon: 'star', section: 'Sales' },
    { label: 'Returns', to: '/vendor/returns', icon: 'return', section: 'Sales' },
    { label: 'Coupons', to: '/vendor/coupons', icon: 'ticket', section: 'Sales' },
    { label: 'Suppliers', to: '/vendor/suppliers', icon: 'truck', section: 'Purchasing' },
    { label: 'Purchase orders', to: '/vendor/purchase-orders', matchNested: true, icon: 'clipboard', section: 'Purchasing' },
    { label: 'Assistant actions', to: '/vendor/assistant', icon: 'sparkle', section: 'Assistant' },
  ],
  RESTAURANT_OWNER: [
    { label: 'Dashboard', to: '/restaurant', icon: 'grid', section: 'Kitchen' },
    { label: 'Restaurant profile', to: '/restaurant/profile', icon: 'cloche', section: 'Kitchen' },
    { label: 'Menu', to: '/restaurant/menu', icon: 'menuBook', section: 'Kitchen' },
    { label: 'Orders', to: '/restaurant/orders', icon: 'receipt', section: 'Sales' },
    { label: 'Reports', to: '/restaurant/reports', icon: 'chart', section: 'Sales' },
    // Phase 12: reviews, returns ("report a problem"), coupons and what the restaurant assistant prepared.
    { label: 'Reviews', to: '/restaurant/reviews', icon: 'star', section: 'Sales' },
    { label: 'Returns', to: '/restaurant/returns', icon: 'return', section: 'Sales' },
    { label: 'Coupons', to: '/restaurant/coupons', icon: 'ticket', section: 'Sales' },
    { label: 'Assistant actions', to: '/restaurant/assistant', icon: 'sparkle', section: 'Assistant' },
  ],
  DELIVERY_PARTNER: [
    // Phase 9: offers and the active delivery live on the dashboard (the partner app).
    { label: 'Dashboard', to: '/delivery', icon: 'scooter', section: 'Deliveries' },
    { label: 'History', to: '/delivery/history', icon: 'route', section: 'Deliveries' },
    { label: 'Partner profile', to: '/delivery/profile', icon: 'user', section: 'Account' },
    // Phase 12: the delivery assistant's requests.
    { label: 'Assistant actions', to: '/delivery/assistant', icon: 'sparkle', section: 'Account' },
  ],
  ADMIN: [
    { label: 'Dashboard', to: '/admin', icon: 'grid', section: 'Overview' },
    // Phase 12.
    { label: 'Reports', to: '/admin/reports', icon: 'chart', section: 'Overview' },
    { label: 'System status', to: '/admin/system', icon: 'pulse', section: 'Overview' },
    { label: 'Approvals', to: '/admin/approvals', icon: 'shield', section: 'Partners' },
    { label: 'Price approvals', to: '/admin/price-approvals', icon: 'tag', section: 'Partners' },
    { label: 'Vendors', to: '/admin/vendors', icon: 'store', section: 'Partners' },
    { label: 'Restaurants', to: '/admin/restaurants', icon: 'cloche', section: 'Partners' },
    { label: 'Delivery partners', to: '/admin/delivery-partners', icon: 'scooter', section: 'Partners' },
    { label: 'Customers', to: '/admin/customers', icon: 'users', section: 'Partners' },
    { label: 'Users', to: '/admin/users', permission: 'users.view', icon: 'user', section: 'Partners' },
    { label: 'Catalog', to: '/admin/catalog', icon: 'basket', section: 'Marketplace' },
    { label: 'Orders', to: '/admin/orders', icon: 'receipt', section: 'Marketplace' },
    { label: 'Payments', to: '/admin/payments', icon: 'card', section: 'Marketplace' },
    { label: 'Deliveries', to: '/admin/deliveries', icon: 'truck', section: 'Marketplace' },
    { label: 'Returns', to: '/admin/returns', icon: 'return', section: 'Marketplace' },
    { label: 'Complaints', to: '/admin/complaints', icon: 'chat', section: 'Marketplace' },
    { label: 'Reviews', to: '/admin/reviews', icon: 'star', section: 'Marketplace' },
    { label: 'Coupons', to: '/admin/coupons', icon: 'ticket', section: 'Marketplace' },
    { label: 'Purchase orders', to: '/admin/purchase-orders', icon: 'clipboard', section: 'Marketplace' },
    // Phase 11 (the onboarding queue above is "Approvals").
    { label: 'AI approvals', to: '/admin/ai/approvals', icon: 'sparkleQuestion', section: 'Operations' },
    { label: 'AI audit', to: '/admin/ai/audit', icon: 'sparkleCheck', section: 'Operations' },
    { label: 'Help center', to: '/admin/ai/knowledge', icon: 'menuBook', section: 'Operations' },
    // One search across every service's audit events (operations admins only: it has sign-in addresses).
    { label: 'Audit log', to: '/admin/audit', permission: 'audit.view', icon: 'shield', section: 'Operations' },
    { label: 'Operations', to: '/admin/operations', icon: 'settings', section: 'Operations' },
    { label: 'Email log', to: '/admin/emails', icon: 'mail', section: 'Operations' },
    // Business rules admins may change without a release (every admin may look; two roles may change them).
    { label: 'Platform settings', to: '/admin/settings', permission: 'settings.view', icon: 'sliders', section: 'Operations' },
  ],
}

