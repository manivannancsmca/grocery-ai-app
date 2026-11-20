import { createStakeholderAdminApi, createStakeholderSelfApi } from '../stakeholders/api'
import type { BusinessAddress, StakeholderLifecycle, StakeholderListParams } from '../stakeholders/types'

export const DAYS_OF_WEEK = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const
export type DayOfWeek = (typeof DAYS_OF_WEEK)[number]

export interface OpeningHours {
  dayOfWeek: DayOfWeek
  /** `HH:mm` */
  opensAt: string
  /** `HH:mm` */
  closesAt: string
}

/** `RestaurantUpdateRequest` — everything optional while editing (contract §5). */
export interface RestaurantUpdateRequest {
  name: string | null
  description: string | null
  /** Uppercase tokens `^[A-Z_]{2,30}$`, at most 8. */
  cuisines: string[]
  fssaiLicense: string | null
  gstin: string | null
  contactEmail: string | null
  contactPhone: string | null
  address: BusinessAddress | null
  openingHours: OpeningHours[]
}

/** `RestaurantView` = request fields + lifecycle fields. */
export interface RestaurantView extends StakeholderLifecycle {
  ownerUserId: string
  name: string | null
  description: string | null
  cuisines: string[] | null
  fssaiLicense: string | null
  gstin: string | null
  contactEmail: string | null
  contactPhone: string | null
  address: BusinessAddress | null
  openingHours: OpeningHours[] | null
  /** Phase 3: the owner's "accepting orders" switch. */
  acceptingOrders?: boolean
}

export const RESTAURANT_NOT_FOUND = 'RESTAURANT_NOT_FOUND'
export const MAX_CUISINES = 8

export const restaurantApi = createStakeholderSelfApi<RestaurantView, RestaurantUpdateRequest>('/restaurants')
export const restaurantAdminApi = createStakeholderAdminApi<RestaurantView>('/admin/restaurants')

export const restaurantKeys = {
  mine: ['restaurant', 'me'] as const,
  admin: ['admin', 'restaurants'] as const,
  adminList: (params: StakeholderListParams) => [...restaurantKeys.admin, 'list', params] as const,
}
