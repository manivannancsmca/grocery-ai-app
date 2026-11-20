import { httpClient } from '../../shared/api/httpClient'
import { type PageResponse, segment } from '../../shared/api/types'

/**
 * Platform settings (docs/api/platform-settings.md): business rules an admin may change without a deploy. Each area
 * belongs to the service that uses it (`GET` / `PUT /api/admin/settings/{area}`); the gateway routes each area to its
 * owner. A stored value is an override; without one the service uses its configured default. Any admin may read;
 * SUPER_ADMIN and OPS_ADMIN may change.
 */

export const SETTINGS_AREAS = ['delivery', 'grocery-stores', 'restaurants', 'returns', 'reviews', 'delivery-fees'] as const
export type SettingsArea = (typeof SETTINGS_AREAS)[number]

/** A setting's value: a number, or the list of search radii. */
export type SettingValue = number | number[]

/** `GET /api/admin/settings/{area}`. */
export interface SettingsView {
  area: SettingsArea | (string & {})
  /** What the service uses now: the override, else the default. */
  values: Record<string, SettingValue>
  /** The service's configured defaults (a default can be fractional when the config is not a whole number of units). */
  defaults: Record<string, SettingValue>
  /** Keys that hold an admin override. */
  overridden: string[]
  /** The last change of any key of the area (null: never changed). */
  updatedAt: string | null
  /** Who made it (display name or email). */
  updatedBy: string | null
  /** 0 = never changed; sent back as `expectedVersion`. */
  version: number
}

/**
 * `PUT /api/admin/settings/{area}`: the whole area at once. `null` (or a missing key) = use the default. 409
 * SETTINGS_CHANGED when `expectedVersion` is stale; 400 VALIDATION_FAILED with `errors[].field` = `values.<key>`.
 */
export interface SettingsUpdate {
  values: Record<string, SettingValue | null>
  expectedVersion: number
}

/**
 * One entry of `GET /api/admin/settings/{area}/history` (newest first): a field one save changed. The values are in the
 * units of `values`; null = the default. A value stored by hand that the service cannot read comes as its stored text.
 */
export interface SettingChange {
  field: string
  oldValue: SettingValue | string | null
  newValue: SettingValue | string | null
  changedAt: string
  /** The admin, and their name (or email) when they saved. */
  changedBy: { id: string; name: string | null }
}

export const SETTINGS_ERRORS = { changed: 'SETTINGS_CHANGED', validation: 'VALIDATION_FAILED' } as const

export const settingsKeys = {
  all: ['platform-settings'] as const,
  area: (area: SettingsArea) => [...settingsKeys.all, area] as const,
  /** An area's history pages. Not under `area(…)`, whose exact key the optimistic save cancels and overwrites. */
  history: (area: SettingsArea) => [...settingsKeys.all, 'history', area] as const,
  historyPage: (area: SettingsArea, page: number, size: number) => [...settingsKeys.history(area), { page, size }] as const,
}

const path = (area: SettingsArea) => `/admin/settings/${segment(area)}`

export async function fetchSettings(area: SettingsArea): Promise<SettingsView> {
  return (await httpClient.get<SettingsView>(path(area))).data
}

export async function saveSettings(area: SettingsArea, body: SettingsUpdate): Promise<SettingsView> {
  return (await httpClient.put<SettingsView>(path(area), body)).data
}

/** Any admin may read it, like the settings themselves. */
export async function fetchSettingsHistory(area: SettingsArea, page: number, size: number): Promise<PageResponse<SettingChange>> {
  return (await httpClient.get<PageResponse<SettingChange>>(`${path(area)}/history`, { params: { page, size } })).data
}
