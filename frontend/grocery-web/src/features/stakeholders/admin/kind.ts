import type { ReactNode } from 'react'
import type { Permission } from '../../../shared/auth/permissions'
import type { DescriptionItem } from '../../../shared/components/ui'
import type { StakeholderAdminApi } from '../api'
import type { StakeholderLifecycle } from '../types'

/**
 * Everything the generic admin screens need to know about one stakeholder type
 * (vendors, restaurants, delivery partners). Configured in src/roles/admin/stakeholderKinds.tsx.
 */
export interface StakeholderKind<T extends StakeholderLifecycle> {
  slug: 'vendors' | 'restaurants' | 'delivery-partners'
  /** Lower-case singular, e.g. "vendor". */
  singular: string
  /** Title-case plural, e.g. "Vendors". */
  plural: string
  api: StakeholderAdminApi<T>
  /** Root query key; lists, details and history live underneath it. */
  queryKey: readonly unknown[]
  /** Who may approve / reject / suspend / reinstate this type. */
  managePermission: Permission
  searchPlaceholder: string
  displayName: (item: T) => string
  /** Secondary line in tables (contact, city …). */
  summary: (item: T) => ReactNode
  details: (item: T) => DescriptionItem[]
  /** Optional extra drawer section (e.g. a restaurant's menu for reviewers). */
  extraSection?: { title: string; render: (item: T) => ReactNode }
}

export type StakeholderAction = 'approve' | 'reject' | 'suspend' | 'reinstate'
