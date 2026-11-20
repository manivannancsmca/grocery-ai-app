import { httpClient } from '../../shared/api/httpClient'
import { segment } from '../../shared/api/types'
/** @typedef {import('../../shared/utils/foodType').FoodType} FoodType */
/** @typedef {import('../stakeholders/types').StakeholderStatus} StakeholderStatus */

/**
 * `MenuItemView` (Phase 3 contract §5). Prices are INR with 2 decimals.
 * @typedef {object} MenuItemView
 * @property {string} id
 * @property {string} categoryId
 * @property {string} name
 * @property {string | null} description
 * @property {number} price
 * @property {FoodType} foodType
 * @property {string | null} imageUrl
 * @property {boolean} available
 * @property {number} sortOrder
 * @property {string} updatedAt
 */

/**
 * @typedef {object} MenuCategoryView
 * @property {string} id
 * @property {string} name
 * @property {string | null} description
 * @property {number} sortOrder
 * @property {MenuItemView[]} items Sorted by sortOrder, then name.
 */

/**
 * `MenuView` — the owner's (or, for admins, any restaurant's) complete menu.
 * @typedef {object} MenuView
 * @property {string} restaurantId
 * @property {StakeholderStatus} restaurantStatus
 * @property {boolean} acceptingOrders
 * @property {number} itemCount
 * @property {MenuCategoryView[]} categories
 */

/**
 * @typedef {object} MenuCategoryRequest
 * @property {string} name
 * @property {string | null} description
 * @property {number} sortOrder
 */

/**
 * @typedef {object} MenuItemRequest
 * @property {string} categoryId
 * @property {string} name
 * @property {string | null} description
 * @property {number} price
 * @property {FoodType} foodType
 * @property {string | null} imageUrl
 * @property {boolean} available
 * @property {number} sortOrder
 */

export const MENU_LIMITS = { categories: 30, items: 500 }
export const RESTAURANT_NOT_FOUND = 'RESTAURANT_NOT_FOUND'

export const menuKeys = {
  mine: ['restaurant', 'menu'],
  admin: (restaurantId) => ['admin', 'restaurants', 'menu', restaurantId],
}

const base = '/restaurant/menu'

/** @returns {Promise<MenuView>} */
export async function fetchMyMenu() {
  return (await httpClient.get(base)).data
}

/**
 * @param {MenuCategoryRequest} body
 * @returns {Promise<MenuCategoryView>}
 */
export async function createMenuCategory(body) {
  return (await httpClient.post(`${base}/categories`, body)).data
}

/**
 * @param {string} id
 * @param {MenuCategoryRequest} body
 * @returns {Promise<MenuCategoryView>}
 */
export async function updateMenuCategory(id, body) {
  return (await httpClient.put(`${base}/categories/${segment(id)}`, body)).data
}

/**
 * 409 `MENU_CATEGORY_NOT_EMPTY` while the category still has items.
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function deleteMenuCategory(id) {
  await httpClient.delete(`${base}/categories/${segment(id)}`)
}

/**
 * @param {MenuItemRequest} body
 * @returns {Promise<MenuItemView>}
 */
export async function createMenuItem(body) {
  return (await httpClient.post(`${base}/items`, body)).data
}

/**
 * @param {string} id
 * @param {MenuItemRequest} body
 * @returns {Promise<MenuItemView>}
 */
export async function updateMenuItem(id, body) {
  return (await httpClient.put(`${base}/items/${segment(id)}`, body)).data
}

/**
 * The "sold out" toggle.
 * @param {string} id
 * @param {boolean} available
 * @returns {Promise<MenuItemView>}
 */
export async function setMenuItemAvailability(id, available) {
  return (await httpClient.patch(`${base}/items/${segment(id)}/availability`, { available })).data
}

/**
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function deleteMenuItem(id) {
  await httpClient.delete(`${base}/items/${segment(id)}`)
}

/**
 * Temporarily stop (or resume) new orders.
 * @param {boolean} acceptingOrders
 * @returns {Promise<{ acceptingOrders: boolean }>}
 */
export async function setAcceptingOrders(acceptingOrders) {
  return (await httpClient.put('/restaurant/accepting-orders', { acceptingOrders })).data
}

/**
 * Any admin: a restaurant's menu, to review it before approving.
 * @param {string} restaurantId
 * @returns {Promise<MenuView>}
 */
export async function fetchAdminRestaurantMenu(restaurantId) {
  return (await httpClient.get(`/admin/restaurants/${segment(restaurantId)}/menu`)).data
}
