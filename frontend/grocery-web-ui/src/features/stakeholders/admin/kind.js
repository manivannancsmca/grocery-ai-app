/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../../shared/auth/permissions').Permission} Permission */
/** @typedef {import('../../../shared/components/ui').DescriptionItem} DescriptionItem */
/** @typedef {import('../api').StakeholderAdminApi} StakeholderAdminApi */
/** @typedef {import('../types').StakeholderLifecycle} StakeholderLifecycle */

/**
 * Everything the generic admin screens need to know about one stakeholder type
 * (vendors, restaurants, delivery partners). Configured in src/roles/admin/stakeholderKinds.jsx.
 * @template {StakeholderLifecycle} T
 * @typedef {object} StakeholderKind
 * @property {'vendors' | 'restaurants' | 'delivery-partners'} slug
 * @property {string} singular Lower-case singular, e.g. "vendor".
 * @property {string} plural Title-case plural, e.g. "Vendors".
 * @property {StakeholderAdminApi<T>} api
 * @property {readonly unknown[]} queryKey Root query key; lists, details and history live underneath it.
 * @property {Permission} managePermission Who may approve / reject / suspend / reinstate this type.
 * @property {string} searchPlaceholder
 * @property {(item: T) => string} displayName
 * @property {(item: T) => ReactNode} summary Secondary line in tables (contact, city …).
 * @property {(item: T) => DescriptionItem[]} details
 * @property {{ title: string, render: (item: T) => ReactNode }} [extraSection] Optional extra drawer section (e.g. a restaurant's menu for reviewers).
 */

/** @typedef {'approve' | 'reject' | 'suspend' | 'reinstate'} StakeholderAction */

// Types only (JSDoc); this file has no runtime code.
export {}
