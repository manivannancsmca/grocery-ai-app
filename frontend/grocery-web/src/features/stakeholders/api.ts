import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { StakeholderListParams, StatusChange } from './types'

/** Owner endpoints: `GET|PUT <base>/me`, `POST <base>/me/submit`. */
export interface StakeholderSelfApi<TView, TUpdate> {
  fetchMine: () => Promise<TView>
  updateMine: (body: TUpdate) => Promise<TView>
  submitMine: () => Promise<TView>
}

export function createStakeholderSelfApi<TView, TUpdate>(basePath: string): StakeholderSelfApi<TView, TUpdate> {
  return {
    fetchMine: async () => (await httpClient.get<TView>(`${basePath}/me`)).data,
    updateMine: async (body) => (await httpClient.put<TView>(`${basePath}/me`, body)).data,
    submitMine: async () => (await httpClient.post<TView>(`${basePath}/me/submit`)).data,
  }
}

/** Admin endpoints under `/api/admin/<type>` (list and history for any admin; actions for SUPER_ADMIN, OPS_ADMIN). */
export interface StakeholderAdminApi<TView> {
  list: (params: StakeholderListParams) => Promise<PageResponse<TView>>
  get: (id: string) => Promise<TView>
  history: (id: string) => Promise<StatusChange[]>
  approve: (id: string) => Promise<TView>
  reject: (id: string, reason: string) => Promise<TView>
  suspend: (id: string, reason: string) => Promise<TView>
  reinstate: (id: string) => Promise<TView>
}

export function createStakeholderAdminApi<TView>(basePath: string): StakeholderAdminApi<TView> {
  const item = (id: string) => `${basePath}/${segment(id)}`
  return {
    list: async (params) => (await httpClient.get<PageResponse<TView>>(basePath, { params: cleanParams(params) })).data,
    get: async (id) => (await httpClient.get<TView>(item(id))).data,
    history: async (id) => (await httpClient.get<StatusChange[]>(`${item(id)}/history`)).data,
    approve: async (id) => (await httpClient.post<TView>(`${item(id)}/approve`)).data,
    reject: async (id, reason) => (await httpClient.post<TView>(`${item(id)}/reject`, { reason })).data,
    suspend: async (id, reason) => (await httpClient.post<TView>(`${item(id)}/suspend`, { reason })).data,
    reinstate: async (id) => (await httpClient.post<TView>(`${item(id)}/reinstate`)).data,
  }
}
