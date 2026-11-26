import type { AdminRole } from './roles'

/**
 * Admin write actions and the roles allowed to perform them, copied from the "Auth" columns of the
 * phase contracts (2–10). The UI hides actions a role lacks; the backend enforces them regardless.
 */
export const PERMISSIONS = {
  /** GET /api/admin/users */
  'users.view': ['SUPER_ADMIN', 'OPS_ADMIN', 'SUPPORT'],
  /** POST /api/admin/users/{id}/suspend|reactivate */
  'users.manage': ['SUPER_ADMIN', 'OPS_ADMIN'],
  /** POST /api/admin/users */
  'admins.create': ['SUPER_ADMIN'],
  /** approve / reject / suspend / reinstate vendors and restaurants */
  'stakeholders.manage': ['SUPER_ADMIN', 'OPS_ADMIN'],
  /** approve / reject / suspend / reinstate delivery partners (delivery managers run the fleet) */
  'partners.manage': ['SUPER_ADMIN', 'OPS_ADMIN', 'DELIVERY_MANAGER'],
  /** Phase 3: create / edit / move / (de)activate / delete categories (reads are open to every admin) */
  'catalog.manage': ['SUPER_ADMIN', 'OPS_ADMIN'],
  /** Phase 3: POST /api/admin/catalog/republish and /api/admin/restaurants/listings/republish */
  'search.republish': ['SUPER_ADMIN', 'OPS_ADMIN'],
  /** Phase 4: POST /api/admin/price-change-requests/{id}/approve|reject (reads are open to every admin) */
  'prices.approve': ['SUPER_ADMIN', 'OPS_ADMIN', 'FINANCE_ADMIN'],
  /** Phase 6: POST /api/admin/orders/{id}/cancel (order and payment reads are open to every admin) */
  'orders.cancel': ['SUPER_ADMIN', 'OPS_ADMIN', 'SUPPORT'],
  /** Phase 6: POST /api/admin/refunds */
  'payments.refund': ['SUPER_ADMIN', 'FINANCE_ADMIN'],
  /** Phase 9: POST /api/admin/deliveries/{id}/reassign (delivery reads are open to every admin) */
  'deliveries.reassign': ['SUPER_ADMIN', 'OPS_ADMIN', 'DELIVERY_MANAGER'],
  /** Phase 10: POST /api/admin/ops/<service>/dead-letters/{id}/replay|discard and …/replay-all (reads: every admin) */
  'deadLetters.manage': ['SUPER_ADMIN', 'OPS_ADMIN'],
  /** Phase 10: POST /api/admin/system/connectors/{name}/restart (connector health: every admin) */
  'connectors.restart': ['SUPER_ADMIN', 'OPS_ADMIN'],
  /** Phase 12: POST /api/admin/reviews/{id}/publish|reject|remove (the review list is open to every admin) */
  'reviews.moderate': ['SUPER_ADMIN', 'OPS_ADMIN', 'SUPPORT'],
  /** Phase 12: POST /api/admin/returns/{id}/approve|reject (the return list is open to every admin) */
  'returns.decide': ['SUPER_ADMIN', 'OPS_ADMIN', 'SUPPORT'],
  /** POST /api/admin/complaints/{id}/start|resolve (the complaint queue is open to every admin) */
  'complaints.handle': ['SUPER_ADMIN', 'OPS_ADMIN', 'SUPPORT'],
  /** Phase 12: create / edit / pause / resume platform coupons (reads are open to every admin) */
  'coupons.manage': ['SUPER_ADMIN', 'OPS_ADMIN', 'FINANCE_ADMIN'],
  /** GET /api/admin/settings/{area} (platform settings, docs/api/platform-settings.md) */
  'settings.view': ['SUPER_ADMIN', 'OPS_ADMIN', 'FINANCE_ADMIN', 'SUPPORT', 'DELIVERY_MANAGER'],
  /** PUT /api/admin/settings/{area} */
  'settings.manage': ['SUPER_ADMIN', 'OPS_ADMIN'],
  /** Upload, replace, reindex and delete help-center documents (/api/admin/ai/knowledge; every admin may look). */
  'knowledge.manage': ['SUPER_ADMIN', 'OPS_ADMIN'],
  /** GET /api/admin/audit (the central audit trail: it has sign-in addresses and e-mails) */
  'audit.view': ['SUPER_ADMIN', 'OPS_ADMIN'],
} as const satisfies Record<string, readonly AdminRole[]>

export type Permission = keyof typeof PERMISSIONS

export function hasPermission(roles: readonly string[], permission: Permission): boolean {
  const allowed: readonly string[] = PERMISSIONS[permission]
  return roles.some((role) => allowed.includes(role))
}
