/** @typedef {import('../../shared/auth/roles').RoleArea} RoleArea */
/** @typedef {import('../../shared/auth/roles').UserRole} UserRole */

/**
 * Development sign-ins, one per stakeholder. identity-service creates them on startup when
 * `DEV_SEED_ENABLED=true` (`DevAccountSeeder.java` — keep the two lists in step); the super admin is the
 * bootstrap admin of the local Docker Compose file. This module is only ever loaded by the Vite dev
 * server (see `LoginPage`), so none of it reaches a production build.
 * @typedef {object} DevAccount
 * @property {UserRole} role
 * @property {string} email
 * @property {string} password
 */

const DEV_PASSWORD = 'Dev@12345'

/** @type {Record<RoleArea, readonly DevAccount[]>} */
export const DEV_ACCOUNTS = {
  CUSTOMER: [{ role: 'CUSTOMER', email: 'dev.customer@grocery.local', password: DEV_PASSWORD }],
  VENDOR: [{ role: 'VENDOR', email: 'dev.vendor@grocery.local', password: DEV_PASSWORD }],
  RESTAURANT_OWNER: [{ role: 'RESTAURANT_OWNER', email: 'dev.restaurant@grocery.local', password: DEV_PASSWORD }],
  DELIVERY_PARTNER: [{ role: 'DELIVERY_PARTNER', email: 'dev.delivery@grocery.local', password: DEV_PASSWORD }],
  ADMIN: [
    { role: 'SUPER_ADMIN', email: 'admin@grocery.local', password: 'Admin@12345' },
    { role: 'OPS_ADMIN', email: 'dev.ops@grocery.local', password: DEV_PASSWORD },
    { role: 'FINANCE_ADMIN', email: 'dev.finance@grocery.local', password: DEV_PASSWORD },
    { role: 'SUPPORT', email: 'dev.support@grocery.local', password: DEV_PASSWORD },
    { role: 'DELIVERY_MANAGER', email: 'dev.delivery-manager@grocery.local', password: DEV_PASSWORD },
  ],
}
