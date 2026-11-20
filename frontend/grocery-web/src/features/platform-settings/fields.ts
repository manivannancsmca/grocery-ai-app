import { z } from 'zod'
import { ApiError } from '../../shared/api/problemDetail'
import type { IconName } from '../../shared/icons'
import { formatPrice } from '../../shared/utils/format'
import type { SettingsArea, SettingsUpdate, SettingsView, SettingValue } from './api'

/**
 * The settings of each area as the page shows them: label, unit, bounds (the same as the owning service's Bean
 * Validation), plus the pure form logic of a card (draft, validation, the PUT body, the optimistic view).
 */

/** `integer` whole units; `decimal` km with one decimal; `money` rupees with paise; `radii` the search radius list. */
export type FieldKind = 'integer' | 'decimal' | 'money' | 'radii'

export interface SettingField {
  key: string
  label: string
  /** Read before the label by screen readers where two fields of a card share one ("Grocery" base fee). */
  group?: string
  kind: FieldKind
  /** After the value: `km`, `s`, `min`, `h`, `days`, `partners` (money fields show ₹ before it instead). */
  unit?: string
  /** The unit for exactly 1 (`day`, `partner`). */
  unitOne?: string
  /** Bounds of the value (radii: of each step). */
  min: number
  max: number
  /** Radii only: the most steps. */
  maxSteps?: number
  hint?: string
}

export interface AreaDefinition {
  area: SettingsArea
  title: string
  icon: IconName
  description: string
  /** When a change takes effect. */
  appliesTo: string
  fields: SettingField[]
}

const ACCEPTANCE_TIME: SettingField = {
  key: 'acceptanceTimeMinutes',
  label: 'Time to accept an order',
  kind: 'integer',
  unit: 'min',
  min: 1,
  max: 60,
  hint: 'Then the order is rejected automatically and the customer refunded.',
}

export const SETTINGS_AREA_DEFINITIONS: readonly AreaDefinition[] = [
  {
    area: 'delivery',
    title: 'Delivery partner search',
    icon: 'scooter',
    description: 'How a new delivery is offered to partners near the store.',
    appliesTo: 'New offers use the new values; offers already sent keep their time limit.',
    fields: [
      {
        key: 'searchRadiiKm',
        label: 'Search radii',
        kind: 'radii',
        unit: 'km',
        min: 0.5,
        max: 50,
        maxSteps: 5,
        hint: 'Up to 5 steps, smallest first, separated by commas (e.g. 3, 6, 10).',
      },
      { key: 'offersPerWave', label: 'Partners offered per wave', kind: 'integer', unit: 'partners', unitOne: 'partner', min: 1, max: 10, hint: 'The nearest free partners get the offer at the same time.' },
      { key: 'offerTimeLimitSeconds', label: 'Time to accept an offer', kind: 'integer', unit: 's', min: 10, max: 300 },
      {
        key: 'locationFreshnessSeconds',
        label: 'Location freshness',
        kind: 'integer',
        unit: 's',
        // At least 30 s, as on the server: the partner app re-sends a still position every 25 s (LOCATION_KEEPALIVE_MS),
        // so a shorter freshness would drop partners who wait without moving between offers.
        min: 30,
        max: 600,
        hint: 'Only partners whose last position is newer than this get offers.',
      },
    ],
  },
  {
    area: 'delivery-fees',
    title: 'Delivery fees',
    icon: 'truck',
    description: 'What customers pay for delivery at checkout.',
    appliesTo:
      'Checkout uses the new fees from now on; placed orders keep what they paid. The road factor and the maximum delivery distance stay in the server configuration.',
    fields: [
      { key: 'groceryBaseFee', group: 'Grocery', label: 'Base fee', kind: 'money', min: 0, max: 500 },
      { key: 'groceryIncludedKm', group: 'Grocery', label: 'Distance included', kind: 'decimal', unit: 'km', min: 0, max: 20 },
      { key: 'groceryPerExtraKm', group: 'Grocery', label: 'Each extra km', kind: 'money', min: 0, max: 100 },
      { key: 'groceryFreeAbove', group: 'Grocery', label: 'Free delivery from', kind: 'money', min: 0, max: 10_000, hint: 'Order subtotal.' },
      { key: 'groceryMinimumOrder', group: 'Grocery', label: 'Minimum order', kind: 'money', min: 0, max: 5_000 },
      { key: 'restaurantBaseFee', group: 'Restaurant', label: 'Base fee', kind: 'money', min: 0, max: 500 },
      { key: 'restaurantIncludedKm', group: 'Restaurant', label: 'Distance included', kind: 'decimal', unit: 'km', min: 0, max: 20 },
      { key: 'restaurantPerExtraKm', group: 'Restaurant', label: 'Each extra km', kind: 'money', min: 0, max: 100 },
    ],
  },
  {
    area: 'grocery-stores',
    title: 'Grocery stores',
    icon: 'store',
    description: 'How long a grocery store has to accept a new order.',
    appliesTo: 'Orders received from now on use the new time; orders already waiting keep their deadline.',
    fields: [ACCEPTANCE_TIME],
  },
  {
    area: 'restaurants',
    title: 'Restaurants',
    icon: 'cloche',
    description: 'How long a restaurant has to accept a new order.',
    appliesTo: 'Orders received from now on use the new time; orders already waiting keep their deadline.',
    fields: [ACCEPTANCE_TIME],
  },
  {
    area: 'returns',
    title: 'Returns',
    icon: 'return',
    description: 'How long customers can ask for a return, and how long the store has to decide before admins take over.',
    appliesTo:
      'The return window counts from delivery, so a new window applies to every delivered order straight away. Requests already made keep their decision deadline.',
    fields: [
      { key: 'returnWindowHours', label: 'Return window after delivery', kind: 'integer', unit: 'h', min: 1, max: 720 },
      { key: 'storeDecisionHours', label: 'Store decision time', kind: 'integer', unit: 'h', min: 1, max: 168, hint: 'Undecided requests then go to the admins.' },
    ],
  },
  {
    area: 'reviews',
    title: 'Reviews',
    icon: 'star',
    description: 'How long after delivery customers can rate the items, the store and the delivery partner.',
    appliesTo: 'The window counts from delivery, so a change applies to every delivered order straight away.',
    fields: [{ key: 'reviewWindowDays', label: 'Review window after delivery', kind: 'integer', unit: 'days', unitOne: 'day', min: 1, max: 365 }],
  },
]

/** Group headings of the delivery-fees card. */
const FIELD_GROUP_TITLES: Record<string, string> = { Grocery: 'Grocery stores', Restaurant: 'Restaurants' }

/** The card's fields in order, under their group heading (null: the card has no groups). */
export function fieldGroups(definition: AreaDefinition): { title: string | null; fields: SettingField[] }[] {
  const groups: { title: string | null; fields: SettingField[] }[] = []
  for (const field of definition.fields) {
    const title = field.group ? (FIELD_GROUP_TITLES[field.group] ?? field.group) : null
    const last = groups.at(-1)
    if (last && last.title === title) last.fields.push(field)
    else groups.push({ title, fields: [field] })
  }
  return groups
}

// ---- display ----------------------------------------------------------------------------------------------------

/** `5` → `5`, `0.5` → `0.5`, `2.345` → `2.35`. */
function plain(value: number): string {
  return Number.isInteger(value) ? String(value) : String(Math.round(value * 100) / 100)
}

function numbersOf(value: SettingValue | null | undefined): number[] {
  if (Array.isArray(value)) return value.filter((n) => typeof n === 'number' && Number.isFinite(n))
  return typeof value === 'number' && Number.isFinite(value) ? [value] : []
}

/** A value with its unit: `48 h`, `₹499`, `3, 6, 10 km`, `1 partner`; `—` when unknown. */
export function formatSetting(field: SettingField, value: SettingValue | null | undefined): string {
  const numbers = numbersOf(value)
  if (numbers.length === 0) return '—'
  if (field.kind === 'money') return formatPrice(numbers[0])
  const text = numbers.map(plain).join(', ')
  const unit = numbers.length === 1 && numbers[0] === 1 && field.unitOne ? field.unitOne : field.unit
  return unit ? `${text} ${unit}` : text
}

/** The value as the input shows it: `48`, `25.5`, `3, 6, 10`. */
export function inputText(value: SettingValue | null | undefined): string {
  return numbersOf(value).map(plain).join(', ')
}

/** `Must be between 1 and 60` / `Must be between ₹0 and ₹500`. */
function boundsMessage(field: SettingField): string {
  const show = (n: number) => (field.kind === 'money' ? formatPrice(n) : plain(n))
  return `Must be between ${show(field.min)} and ${show(field.max)}`
}

// ---- validation -------------------------------------------------------------------------------------------------

const NUMBER_FORMATS: Record<Exclude<FieldKind, 'radii'>, { pattern: RegExp; message: string }> = {
  integer: { pattern: /^\d+$/, message: 'Enter a whole number' },
  decimal: { pattern: /^\d+(\.\d)?$/, message: 'Enter a number with at most one decimal' },
  money: { pattern: /^\d+(\.\d{1,2})?$/, message: 'Enter an amount in rupees, with at most two decimals' },
}
const RADIUS_FORMAT = /^\d+(\.\d)?$/

function valueSchema(field: SettingField) {
  const bounded = z.number().min(field.min, boundsMessage(field)).max(field.max, boundsMessage(field))
  if (field.kind !== 'radii') return bounded
  const steps = field.maxSteps ?? 5
  return z
    .array(z.number().min(field.min, `Each radius must be between ${plain(field.min)} and ${plain(field.max)} km`).max(field.max, `Each radius must be between ${plain(field.min)} and ${plain(field.max)} km`))
    .min(1, 'Enter at least one radius')
    .max(steps, `Use at most ${steps} steps`)
    .refine((radii) => radii.every((radius, index) => index === 0 || radius > (radii[index - 1] ?? 0)), 'Each radius must be larger than the one before')
}

export type ParsedSetting = { ok: true; value: SettingValue } | { ok: false; error: string }

/** The typed value of an input, or why it is not acceptable (the service's bounds). */
export function parseSetting(field: SettingField, text: string): ParsedSetting {
  const trimmed = text.trim()
  let value: SettingValue
  if (field.kind === 'radii') {
    const parts = trimmed.split(/[\s,]+/).filter(Boolean)
    if (parts.length === 0) return { ok: false, error: 'Enter at least one radius' }
    if (!parts.every((part) => RADIUS_FORMAT.test(part))) return { ok: false, error: 'Enter numbers in km (one decimal at most), separated by commas' }
    value = parts.map(Number)
  } else {
    if (!trimmed) return { ok: false, error: 'Enter a value, or reset it to the default' }
    const format = NUMBER_FORMATS[field.kind]
    if (!format.pattern.test(trimmed)) return { ok: false, error: format.message }
    value = Number(trimmed)
  }
  const result = valueSchema(field).safeParse(value)
  return result.success ? { ok: true, value } : { ok: false, error: result.error.issues[0]?.message ?? 'Not a valid value' }
}

export function sameValue(a: SettingValue | null | undefined, b: SettingValue | null | undefined): boolean {
  if (a === null || a === undefined || b === null || b === undefined) return (a ?? null) === (b ?? null)
  if (Array.isArray(a) || Array.isArray(b)) {
    const left = numbersOf(a)
    const right = numbersOf(b)
    return Array.isArray(a) === Array.isArray(b) && left.length === right.length && left.every((n, i) => n === right[i])
  }
  return a === b
}

// ---- the card's form --------------------------------------------------------------------------------------------

/** One input: its text, or "use the default" (sent as null). */
export interface FieldDraft {
  text: string
  useDefault: boolean
}

/** The admin's edits of one card, with the version they started from (sent as `expectedVersion`). */
export interface AreaDraft {
  version: number
  fields: Record<string, FieldDraft>
}

/** The inputs as the server view has them: overrides as values, the rest "default". */
export function draftFrom(definition: AreaDefinition, view: SettingsView): AreaDraft {
  const fields: Record<string, FieldDraft> = {}
  for (const field of definition.fields) {
    fields[field.key] = { text: inputText(view.values[field.key]), useDefault: !view.overridden.includes(field.key) }
  }
  return { version: view.version, fields }
}

export interface FieldState {
  /** What would be sent: the value, or null for "use the default" (also null while invalid). */
  value: SettingValue | null
  error: string | null
  /** The value differs from the default: an override, saved or about to be. */
  changed: boolean
  /** Differs from what is saved. */
  unsaved: boolean
}

/** What the server has for `field`: the override, or null (default). */
function savedValue(field: SettingField, view: SettingsView): SettingValue | null {
  return view.overridden.includes(field.key) ? (view.values[field.key] ?? null) : null
}

/**
 * A field of the draft: untouched or reset → null (default). An edit that only types the default again on a field
 * without an override stays null, so the setting keeps following the configured default.
 */
export function fieldState(field: SettingField, draft: FieldDraft | undefined, view: SettingsView): FieldState {
  const saved = savedValue(field, view)
  if (!draft || draft.useDefault) return { value: null, error: null, changed: false, unsaved: saved !== null }
  const parsed = parseSetting(field, draft.text)
  if (!parsed.ok) return { value: null, error: parsed.error, changed: true, unsaved: true }
  const followsDefault = saved === null && sameValue(parsed.value, view.defaults[field.key])
  const value = followsDefault ? null : parsed.value
  return { value, error: null, changed: value !== null, unsaved: !sameValue(value, saved) }
}

/** The whole area as `PUT` takes it: every key, null = default. */
export function buildUpdate(definition: AreaDefinition, draft: AreaDraft, view: SettingsView): SettingsUpdate {
  const values: SettingsUpdate['values'] = {}
  for (const field of definition.fields) values[field.key] = fieldState(field, draft.fields[field.key], view).value
  return { values, expectedVersion: draft.version }
}

/** What the card shows while the save is on its way: the new values, the defaults where reset. */
export function optimisticView(view: SettingsView, update: SettingsUpdate): SettingsView {
  const values: Record<string, SettingValue> = { ...view.values }
  const overridden: string[] = []
  for (const [key, value] of Object.entries(update.values)) {
    if (value === null || value === undefined) {
      const fallback = view.defaults[key]
      if (fallback !== undefined) values[key] = fallback
    } else {
      values[key] = value
      overridden.push(key)
    }
  }
  return { ...view, values, overridden }
}

/**
 * The 400 VALIDATION_FAILED field errors by setting key (`values.searchRadiiKm[1]` → `searchRadiiKm`), and the
 * messages that belong to no field of the card.
 */
export function serverFieldErrors(error: unknown, definition: AreaDefinition): { byKey: Record<string, string>; other: string[] } {
  const byKey: Record<string, string> = {}
  const other: string[] = []
  if (!(error instanceof ApiError)) return { byKey, other }
  const keys = new Set(definition.fields.map((field) => field.key))
  for (const { field, message } of error.fieldErrors) {
    const key = /^(?:values\.)?([A-Za-z]+)/.exec(field)?.[1]
    if (key && keys.has(key)) byKey[key] ??= message
    else other.push(`${field}: ${message}`)
  }
  return { byKey, other }
}
