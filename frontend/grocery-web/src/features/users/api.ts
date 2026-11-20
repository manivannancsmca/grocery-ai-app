import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { AdminRole, UserRole } from '../../shared/auth/roles'

export type AdminUserStatus = 'ACTIVE' | 'SUSPENDED'

/** `AdminUserView` (contract §3). */
export interface AdminUserView {
  id: string
  email: string
  fullName: string
  phone: string | null
  roles: UserRole[]
  status: AdminUserStatus | (string & {})
  createdAt: string
  lastLoginAt: string | null
}

/** `CreateAdminRequest` — roles ⊆ admin roles. */
export interface CreateAdminRequest {
  email: string
  fullName: string
  password: string
  phone?: string
  roles: AdminRole[]
}

export interface AdminUserListParams {
  query?: string
  role?: UserRole
  status?: AdminUserStatus
  page?: number
  size?: number
}

export const userKeys = {
  all: ['admin', 'users'] as const,
  list: (params: AdminUserListParams) => [...userKeys.all, 'list', params] as const,
}

export async function fetchUsers(params: AdminUserListParams): Promise<PageResponse<AdminUserView>> {
  return (await httpClient.get<PageResponse<AdminUserView>>('/admin/users', { params: cleanParams(params) })).data
}

export async function createAdminUser(body: CreateAdminRequest): Promise<AdminUserView> {
  return (await httpClient.post<AdminUserView>('/admin/users', body)).data
}

export async function suspendUser(id: string, reason: string): Promise<AdminUserView> {
  return (await httpClient.post<AdminUserView>(`/admin/users/${segment(id)}/suspend`, { reason })).data
}

export async function reactivateUser(id: string): Promise<AdminUserView> {
  return (await httpClient.post<AdminUserView>(`/admin/users/${segment(id)}/reactivate`)).data
}
