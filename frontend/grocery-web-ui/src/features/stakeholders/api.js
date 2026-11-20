import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('./types').StakeholderListParams} StakeholderListParams */
/** @typedef {import('./types').StatusChange} StatusChange */

/**
 * Owner endpoints: `GET|PUT <base>/me`, `POST <base>/me/submit`.
 * @template TView
 * @template TUpdate
 * @typedef {object} StakeholderSelfApi
 * @property {() => Promise<TView>} fetchMine
 * @property {(body: TUpdate) => Promise<TView>} updateMine
 * @property {() => Promise<TView>} submitMine
 */

/**
 * @template TView
 * @template TUpdate
 * @param {string} basePath
 * @returns {StakeholderSelfApi<TView, TUpdate>}
 */
export function createStakeholderSelfApi(basePath) {
  return {
    fetchMine: async () => (await httpClient.get(`${basePath}/me`)).data,
    updateMine: async (body) => (await httpClient.put(`${basePath}/me`, body)).data,
    submitMine: async () => (await httpClient.post(`${basePath}/me/submit`)).data,
  }
}

/**
 * Admin endpoints under `/api/admin/<type>` (list and history for any admin; actions for SUPER_ADMIN, OPS_ADMIN).
 * @template TView
 * @typedef {object} StakeholderAdminApi
 * @property {(params: StakeholderListParams) => Promise<PageResponse<TView>>} list
 * @property {(id: string) => Promise<TView>} get
 * @property {(id: string) => Promise<StatusChange[]>} history
 * @property {(id: string) => Promise<TView>} approve
 * @property {(id: string, reason: string) => Promise<TView>} reject
 * @property {(id: string, reason: string) => Promise<TView>} suspend
 * @property {(id: string) => Promise<TView>} reinstate
 */

/**
 * @template TView
 * @param {string} basePath
 * @returns {StakeholderAdminApi<TView>}
 */
export function createStakeholderAdminApi(basePath) {
  const item = (id) => `${basePath}/${segment(id)}`
  return {
    list: async (params) => (await httpClient.get(basePath, { params: cleanParams(params) })).data,
    get: async (id) => (await httpClient.get(item(id))).data,
    history: async (id) => (await httpClient.get(`${item(id)}/history`)).data,
    approve: async (id) => (await httpClient.post(`${item(id)}/approve`)).data,
    reject: async (id, reason) => (await httpClient.post(`${item(id)}/reject`, { reason })).data,
    suspend: async (id, reason) => (await httpClient.post(`${item(id)}/suspend`, { reason })).data,
    reinstate: async (id) => (await httpClient.post(`${item(id)}/reinstate`)).data,
  }
}
