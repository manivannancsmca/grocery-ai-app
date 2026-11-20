import { z } from 'zod'
import {
  emailField,
  emptyToNull,
  MESSAGES,
  normalizePhone,
  PATTERNS,
  phoneField,
  textField,
} from '../../shared/forms/validation'
import {
  businessAddressSchema,
  businessAddressToFormValues,
  businessAddressToRequest,
} from '../stakeholders/businessAddress'
import { DAYS_OF_WEEK, MAX_CUISINES } from './api'

/** @typedef {import('../stakeholders/businessAddress').ValidationMode} ValidationMode */
/** @typedef {import('./api').RestaurantUpdateRequest} RestaurantUpdateRequest */
/** @typedef {import('./api').RestaurantView} RestaurantView */

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/

export const CUISINE_SUGGESTIONS = [
  'NORTH_INDIAN',
  'SOUTH_INDIAN',
  'BIRYANI',
  'CHINESE',
  'MUGHLAI',
  'STREET_FOOD',
  'PIZZA',
  'BURGERS',
  'CAFE',
  'BAKERY',
  'DESSERTS',
  'BEVERAGES',
  'CONTINENTAL',
  'ITALIAN',
  'HEALTHY',
]

/**
 * `north indian` / `North-Indian` → `NORTH_INDIAN`
 * @param {string} raw
 * @returns {string}
 */
export function normalizeCuisine(raw) {
  return raw
    .trim()
    .toUpperCase()
    .replace(/[\s-]+/g, '_')
}

/**
 * @param {string} tag
 * @returns {string | null}
 */
export function cuisineError(tag) {
  return PATTERNS.cuisine.test(tag) ? null : 'Use 2–30 letters (spaces become underscores).'
}

const openingHoursRow = z
  .object({
    dayOfWeek: z.enum(DAYS_OF_WEEK),
    open: z.boolean(),
    opensAt: z.string(),
    closesAt: z.string(),
  })
  .superRefine((row, ctx) => {
    if (!row.open) return
    if (!TIME.test(row.opensAt)) ctx.addIssue({ code: 'custom', message: 'Set an opening time.', path: ['opensAt'] })
    if (!TIME.test(row.closesAt)) ctx.addIssue({ code: 'custom', message: 'Set a closing time.', path: ['closesAt'] })
    if (TIME.test(row.opensAt) && row.opensAt === row.closesAt) {
      ctx.addIssue({ code: 'custom', message: 'Closing time must differ from opening time.', path: ['closesAt'] })
    }
  })

/**
 * Required to submit: name, cuisines (≥1), fssaiLicense, contactEmail, contactPhone, address incl. lat/lng, openingHours (≥1).
 * @param {ValidationMode} mode
 */
export function restaurantSchema(mode) {
  const required = mode === 'submit'
  return z.object({
    name: textField({ required, max: 120 }),
    description: textField({ max: 1000 }),
    cuisines: z
      .array(z.string().regex(PATTERNS.cuisine, 'Cuisines use 2–30 uppercase letters or underscores.'))
      .max(MAX_CUISINES, `Choose at most ${MAX_CUISINES} cuisines.`)
      .refine((list) => !required || list.length > 0, 'Add at least one cuisine.'),
    fssaiLicense: textField({ required, pattern: PATTERNS.fssai, patternMessage: MESSAGES.fssai }),
    gstin: textField({ pattern: PATTERNS.gstin, patternMessage: MESSAGES.gstin, normalize: (v) => v.toUpperCase() }),
    contactEmail: emailField(required),
    contactPhone: phoneField(required),
    address: businessAddressSchema(mode),
    openingHours: z
      .array(openingHoursRow)
      .refine((rows) => !required || rows.some((row) => row.open), 'Open on at least one day.'),
  })
}

/** @typedef {z.infer<ReturnType<typeof restaurantSchema>>} RestaurantFormValues */
/** @typedef {RestaurantFormValues['openingHours'][number]} OpeningHoursRowValues */

export const restaurantDraftSchema = restaurantSchema('draft')
export const restaurantSubmitSchema = restaurantSchema('submit')

/**
 * `09:00:00` → `09:00` (LocalTime may come back with seconds).
 * @param {string | null | undefined} value
 * @returns {string}
 */
function toHourMinute(value) {
  return value ? value.slice(0, 5) : ''
}

/**
 * One row per weekday; the UI supports one opening slot per day (the first one returned).
 * @param {RestaurantView['openingHours']} hours
 * @returns {OpeningHoursRowValues[]}
 */
export function openingHoursToRows(hours) {
  return DAYS_OF_WEEK.map((day) => {
    const slot = hours?.find((entry) => entry.dayOfWeek === day)
    return slot
      ? { dayOfWeek: day, open: true, opensAt: toHourMinute(slot.opensAt), closesAt: toHourMinute(slot.closesAt) }
      : { dayOfWeek: day, open: false, opensAt: '', closesAt: '' }
  })
}

/**
 * @param {RestaurantView} restaurant
 * @returns {RestaurantFormValues}
 */
export function restaurantToFormValues(restaurant) {
  return {
    name: restaurant.name ?? '',
    description: restaurant.description ?? '',
    cuisines: restaurant.cuisines ?? [],
    fssaiLicense: restaurant.fssaiLicense ?? '',
    gstin: restaurant.gstin ?? '',
    contactEmail: restaurant.contactEmail ?? '',
    contactPhone: restaurant.contactPhone ?? '',
    address: businessAddressToFormValues(restaurant.address),
    openingHours: openingHoursToRows(restaurant.openingHours),
  }
}

/**
 * @param {RestaurantFormValues} values
 * @returns {RestaurantUpdateRequest}
 */
export function restaurantFormToRequest(values) {
  const phone = emptyToNull(values.contactPhone)
  return {
    name: emptyToNull(values.name),
    description: emptyToNull(values.description),
    cuisines: values.cuisines,
    fssaiLicense: emptyToNull(values.fssaiLicense),
    gstin: emptyToNull(values.gstin)?.toUpperCase() ?? null,
    contactEmail: emptyToNull(values.contactEmail),
    contactPhone: phone ? normalizePhone(phone) : null,
    address: businessAddressToRequest(values.address),
    openingHours: values.openingHours
      .filter((row) => row.open && TIME.test(row.opensAt) && TIME.test(row.closesAt))
      .map(({ dayOfWeek, opensAt, closesAt }) => ({ dayOfWeek, opensAt, closesAt })),
  }
}
