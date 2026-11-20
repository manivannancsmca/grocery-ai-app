import { createStakeholderAdminApi, createStakeholderSelfApi } from '../stakeholders/api'

/** @typedef {import('../stakeholders/types').BusinessAddress} BusinessAddress */
/** @typedef {import('../stakeholders/types').StakeholderLifecycle} StakeholderLifecycle */

export const DAYS_OF_WEEK = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
/** @typedef {(typeof DAYS_OF_WEEK)[number]} DayOfWeek */

/**
 * @typedef {object} OpeningHours
 * @property {DayOfWeek} dayOfWeek
 * @property {string} opensAt `HH:mm`
 * @property {string} closesAt `HH:mm`
 */

/**
 * `RestaurantUpdateRequest` — everything optional while editing (contract §5).
 * @typedef {object} RestaurantUpdateRequest
 * @property {string | null} name
 * @property {string | null} description
 * @property {string[]} cuisines Uppercase tokens `^[A-Z_]{2,30}$`, at most 8.
 * @property {string | null} fssaiLicense
 * @property {string | null} gstin
 * @property {string | null} contactEmail
 * @property {string | null} contactPhone
 * @property {BusinessAddress | null} address
 * @property {OpeningHours[]} openingHours
 */

/**
 * `RestaurantView` = request fields + lifecycle fields.
 * @typedef {StakeholderLifecycle & { ownerUserId: string, name: string | null, description: string | null, cuisines: string[] | null, fssaiLicense: string | null, gstin: string | null, contactEmail: string | null, contactPhone: string | null, address: BusinessAddress | null, openingHours: OpeningHours[] | null, acceptingOrders?: boolean }} RestaurantView
 * - `acceptingOrders`: Phase 3: the owner's "accepting orders" switch.
 */

export const RESTAURANT_NOT_FOUND = 'RESTAURANT_NOT_FOUND'
export const MAX_CUISINES = 8

export const restaurantApi = createStakeholderSelfApi('/restaurants')
export const restaurantAdminApi = createStakeholderAdminApi('/admin/restaurants')

export const restaurantKeys = {
  mine: ['restaurant', 'me'],
  admin: ['admin', 'restaurants'],
  adminList: (params) => [...restaurantKeys.admin, 'list', params],
}
