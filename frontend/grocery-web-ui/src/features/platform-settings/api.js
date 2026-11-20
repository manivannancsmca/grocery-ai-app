import { httpClient } from '../../shared/api/httpClient'
import { segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/**
 * Platform settings (docs/api/platform-settings.md): business rules an admin may change without a deploy. Each area
 * belongs to the service that uses it (`GET` / `PUT /api/admin/settings/{area}`); the gateway routes each area to its
 * owner. A stored value is an override; without one the service uses its configured default. Any admin may read;
 * SUPER_ADMIN and OPS_ADMIN may change.
 */

export const SETTINGS_AREAS = ['delivery', 'grocery-stores', 'restaurants', 'returns', 'reviews', 'delivery-fees']
/** @typedef {(typeof SETTINGS_AREAS)[number]} SettingsArea */

/**
 * A setting's value: a number, or the list of search radii.
 * @typedef {number | number[]} SettingValue
 */

/**
 * `GET /api/admin/settings/{area}`.
 * @typedef {object} SettingsView
 * @property {SettingsArea | (string & {})} area
 * @property {Record<string, SettingValue>} values What the service uses now: the override, else the default.
 * @property {Record<string, SettingValue>} defaults The service's configured defaults (a default can be fractional when the config is not a whole number of units).
 * @property {string[]} overridden Keys that hold an admin override.
 * @property {string | null} updatedAt The last change of any key of the area (null: never changed).
 * @property {string | null} updatedBy Who made it (display name or email).
 * @property {number} version 0 = never changed; sent back as `expectedVersion`.
 */

/**
 * `PUT /api/admin/settings/{area}`: the whole area at once. `null` (or a missing key) = use the default. 409
 * SETTINGS_CHANGED when `expectedVersion` is stale; 400 VALIDATION_FAILED with `errors[].field` = `values.<key>`.
 * @typedef {object} SettingsUpdate
 * @property {Record<string, SettingValue | null>} values
 * @property {number} expectedVersion
 */

/**
 * One entry of `GET /api/admin/settings/{area}/history` (newest first): a field one save changed. The values are in the
 * units of `values`; null = the default. A value stored by hand that the service cannot read comes as its stored text.
 * @typedef {object} SettingChange
 * @property {string} field
 * @property {SettingValue | string | null} oldValue
 * @property {SettingValue | string | null} newValue
 * @property {string} changedAt
 * @property {{ id: string, name: string | null }} changedBy The admin, and their name (or email) when they saved.
 */

export const SETTINGS_ERRORS = { changed: 'SETTINGS_CHANGED', validation: 'VALIDATION_FAILED' }

export const settingsKeys = {
  all: ['platform-settings'],
  area: (area) => [...settingsKeys.all, area],
  /** An area's history pages. Not under `area(…)`, whose exact key the optimistic save cancels and overwrites. */
  history: (area) => [...settingsKeys.all, 'history', area],
  historyPage: (area, page, size) => [...settingsKeys.history(area), { page, size }],
}

/** @param {SettingsArea} area */
const path = (area) => `/admin/settings/${segment(area)}`

/**
 * @param {SettingsArea} area
 * @returns {Promise<SettingsView>}
 */
export async function fetchSettings(area) {
  return (await httpClient.get(path(area))).data
}

/**
 * @param {SettingsArea} area
 * @param {SettingsUpdate} body
 * @returns {Promise<SettingsView>}
 */
export async function saveSettings(area, body) {
  return (await httpClient.put(path(area), body)).data
}

/**
 * Any admin may read it, like the settings themselves.
 * @param {SettingsArea} area
 * @param {number} page
 * @param {number} size
 * @returns {Promise<PageResponse<SettingChange>>}
 */
export async function fetchSettingsHistory(area, page, size) {
  return (await httpClient.get(`${path(area)}/history`, { params: { page, size } })).data
}
