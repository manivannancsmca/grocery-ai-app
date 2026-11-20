import { httpClient } from '../../shared/api/httpClient'
import { segment } from '../../shared/api/types'
import type { FoodType } from '../../shared/utils/foodType'
import type { StakeholderStatus } from '../stakeholders/types'

/** `MenuItemView` (Phase 3 contract §5). Prices are INR with 2 decimals. */
export interface MenuItemView {
  id: string
  categoryId: string
  name: string
  description: string | null
  price: number
  foodType: FoodType
  imageUrl: string | null
  available: boolean
  sortOrder: number
  updatedAt: string
}

export interface MenuCategoryView {
  id: string
  name: string
  description: string | null
  sortOrder: number
  /** Sorted by sortOrder, then name. */
  items: MenuItemView[]
}

/** `MenuView` — the owner's (or, for admins, any restaurant's) complete menu. */
export interface MenuView {
  restaurantId: string
  restaurantStatus: StakeholderStatus
  acceptingOrders: boolean
  itemCount: number
  categories: MenuCategoryView[]
}

export interface MenuCategoryRequest {
  name: string
  description: string | null
  sortOrder: number
}

export interface MenuItemRequest {
  categoryId: string
  name: string
  description: string | null
  price: number
  foodType: FoodType
  imageUrl: string | null
  available: boolean
  sortOrder: number
}

export const MENU_LIMITS = { categories: 30, items: 500 } as const
export const RESTAURANT_NOT_FOUND = 'RESTAURANT_NOT_FOUND'

export const menuKeys = {
  mine: ['restaurant', 'menu'] as const,
  admin: (restaurantId: string) => ['admin', 'restaurants', 'menu', restaurantId] as const,
}

const base = '/restaurant/menu'

export async function fetchMyMenu(): Promise<MenuView> {
  return (await httpClient.get<MenuView>(base)).data
}

export async function createMenuCategory(body: MenuCategoryRequest): Promise<MenuCategoryView> {
  return (await httpClient.post<MenuCategoryView>(`${base}/categories`, body)).data
}

export async function updateMenuCategory(id: string, body: MenuCategoryRequest): Promise<MenuCategoryView> {
  return (await httpClient.put<MenuCategoryView>(`${base}/categories/${segment(id)}`, body)).data
}

/** 409 `MENU_CATEGORY_NOT_EMPTY` while the category still has items. */
export async function deleteMenuCategory(id: string): Promise<void> {
  await httpClient.delete(`${base}/categories/${segment(id)}`)
}

export async function createMenuItem(body: MenuItemRequest): Promise<MenuItemView> {
  return (await httpClient.post<MenuItemView>(`${base}/items`, body)).data
}

export async function updateMenuItem(id: string, body: MenuItemRequest): Promise<MenuItemView> {
  return (await httpClient.put<MenuItemView>(`${base}/items/${segment(id)}`, body)).data
}

/** The "sold out" toggle. */
export async function setMenuItemAvailability(id: string, available: boolean): Promise<MenuItemView> {
  return (await httpClient.patch<MenuItemView>(`${base}/items/${segment(id)}/availability`, { available })).data
}

export async function deleteMenuItem(id: string): Promise<void> {
  await httpClient.delete(`${base}/items/${segment(id)}`)
}

/** Temporarily stop (or resume) new orders. */
export async function setAcceptingOrders(acceptingOrders: boolean): Promise<{ acceptingOrders: boolean }> {
  return (await httpClient.put<{ acceptingOrders: boolean }>('/restaurant/accepting-orders', { acceptingOrders })).data
}

/** Any admin: a restaurant's menu, to review it before approving. */
export async function fetchAdminRestaurantMenu(restaurantId: string): Promise<MenuView> {
  return (await httpClient.get<MenuView>(`/admin/restaurants/${segment(restaurantId)}/menu`)).data
}
