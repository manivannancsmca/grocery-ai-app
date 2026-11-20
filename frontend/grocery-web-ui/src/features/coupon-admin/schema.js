import { z } from 'zod'
import { emptyToNull, toNumberOrNull } from '../../shared/forms/validation'
import { COUPON_CODE_PATTERN, COUPON_LIMITS, COUPON_SCOPES, COUPON_STORE_TYPES, DISCOUNT_TYPES } from './api'

/** @typedef {import('./api').CouponOwner} CouponOwner */
/** @typedef {import('./api').CouponRequest} CouponRequest */
/** @typedef {import('./api').CouponView} CouponView */

const DAY_MS = 86_400_000

/**
 * @param {number} value
 * @returns {string}
 */
function pad(value) {
  return String(value).padStart(2, '0')
}

/**
 * An instant as the value of an `<input type="datetime-local">` (the browser's time zone).
 * @param {string | null | undefined} iso
 * @returns {string}
 */
export function toLocalInput(iso) {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * A `datetime-local` value as an ISO instant (UTC), or null when empty / invalid.
 * @param {string} value
 * @returns {string | null}
 */
export function fromLocalInput(value) {
  if (!value.trim()) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

/**
 * Upper-cased and trimmed, as pricing-service stores codes.
 * @param {string} value
 * @returns {string}
 */
export function normaliseCode(value) {
  return value.trim().toUpperCase()
}

const decimal = /^\d+(\.\d{1,2})?$/
const whole = /^\d+$/

/**
 * The coupon form. Admins choose the scope (PLATFORM with an optional store type, or STORE with a store id);
 * store owners' coupons are always STORE coupons of their own store.
 * @param {CouponOwner} owner
 */
export function couponSchema(owner) {
  return z
    .object({
      code: z.string(),
      name: z.string(),
      description: z.string(),
      scope: z.enum(COUPON_SCOPES),
      storeType: z.enum(COUPON_STORE_TYPES),
      storeId: z.string(),
      discountType: z.enum(DISCOUNT_TYPES),
      value: z.string(),
      maxDiscount: z.string(),
      minOrderSubtotal: z.string(),
      validFrom: z.string(),
      validUntil: z.string(),
      totalUsageLimit: z.string(),
      perCustomerLimit: z.string(),
      public: z.boolean(),
    })
    .superRefine((values, ctx) => {
      const issue = (path, message) => ctx.addIssue({ code: 'custom', path: [path], message })

      const code = normaliseCode(values.code)
      if (!code) issue('code', 'Enter a code.')
      else if (!COUPON_CODE_PATTERN.test(code)) issue('code', 'Use 4–20 letters, digits or dashes (no spaces).')

      const name = values.name.trim()
      if (!name) issue('name', 'Enter a name customers will see.')
      else if (name.length > COUPON_LIMITS.name) issue('name', `Use at most ${COUPON_LIMITS.name} characters.`)
      if (values.description.trim().length > COUPON_LIMITS.description)
        issue('description', `Use at most ${COUPON_LIMITS.description} characters.`)

      if (owner === 'admin' && values.scope === 'STORE' && !values.storeId.trim())
        issue('storeId', 'Enter the ID of the vendor or restaurant.')

      const value = values.value.trim()
      if (values.discountType === 'PERCENT') {
        const percent = Number(value)
        if (!value) issue('value', 'Enter the percentage.')
        else if (!decimal.test(value) || percent < COUPON_LIMITS.percentMin || percent > COUPON_LIMITS.percentMax) {
          issue('value', `Enter a percentage from ${COUPON_LIMITS.percentMin} to ${COUPON_LIMITS.percentMax}.`)
        }
        const max = values.maxDiscount.trim()
        if (max && (!decimal.test(max) || Number(max) <= 0 || Number(max) > COUPON_LIMITS.amountMax)) {
          issue('maxDiscount', 'Enter an amount above ₹0, or leave it empty for no cap.')
        }
      } else if (values.discountType === 'FLAT') {
        if (!value) issue('value', 'Enter the amount off.')
        else if (!decimal.test(value) || Number(value) <= 0 || Number(value) > COUPON_LIMITS.amountMax) {
          issue('value', `Enter an amount above ₹0 and at most ₹${COUPON_LIMITS.amountMax.toLocaleString('en-IN')}.`)
        }
      }

      const minimum = values.minOrderSubtotal.trim()
      if (minimum && (!decimal.test(minimum) || Number(minimum) > COUPON_LIMITS.amountMax))
        issue('minOrderSubtotal', 'Enter an amount, e.g. 199, or leave it empty.')
      if (
        values.discountType === 'FLAT' &&
        value &&
        minimum &&
        decimal.test(value) &&
        decimal.test(minimum) &&
        Number(value) > Number(minimum) &&
        Number(minimum) > 0
      ) {
        issue('value', 'The amount off cannot be more than the minimum order.')
      }

      const from = fromLocalInput(values.validFrom)
      const until = fromLocalInput(values.validUntil)
      if (!from) issue('validFrom', 'Choose when the coupon starts.')
      if (!until) issue('validUntil', 'Choose when the coupon ends.')
      if (from && until && Date.parse(until) <= Date.parse(from))
        issue('validUntil', 'The end must be after the start.')

      const total = values.totalUsageLimit.trim()
      if (total && (!whole.test(total) || Number(total) < 1 || Number(total) > COUPON_LIMITS.usageMax))
        issue('totalUsageLimit', 'Enter a whole number of at least 1, or leave it empty for no limit.')
      const perCustomer = values.perCustomerLimit.trim()
      if (!perCustomer) issue('perCustomerLimit', 'Enter how often one customer may use it.')
      else if (!whole.test(perCustomer) || Number(perCustomer) < 1 || Number(perCustomer) > 1000)
        issue('perCustomerLimit', 'Enter a whole number from 1 to 1000.')
      else if (total && whole.test(total) && Number(perCustomer) > Number(total))
        issue('perCustomerLimit', 'Cannot be more than the total limit.')
    })
}

/** @typedef {z.infer<ReturnType<typeof couponSchema>>} CouponFormValues */

/**
 * A new coupon: starts now, runs 30 days, one use per customer, listed as an offer.
 * @param {CouponOwner} owner
 * @param {number} [now=Date.now()]
 * @returns {CouponFormValues}
 */
export function newCouponForm(owner, now = Date.now()) {
  return {
    code: '',
    name: '',
    description: '',
    scope: owner === 'admin' ? 'PLATFORM' : 'STORE',
    storeType: 'ANY',
    storeId: '',
    discountType: 'PERCENT',
    value: '',
    maxDiscount: '',
    minOrderSubtotal: '',
    validFrom: toLocalInput(new Date(now).toISOString()),
    validUntil: toLocalInput(new Date(now + 30 * DAY_MS).toISOString()),
    totalUsageLimit: '',
    perCustomerLimit: '1',
    public: true,
  }
}

/**
 * @param {number | null | undefined} value
 * @returns {string}
 */
function text(value) {
  return value === null || value === undefined ? '' : String(value)
}

/**
 * @param {CouponView} coupon
 * @returns {CouponFormValues}
 */
export function couponToForm(coupon) {
  const scope = coupon.scope === 'STORE' ? 'STORE' : 'PLATFORM'
  const storeType = COUPON_STORE_TYPES.includes(coupon.storeType ?? '') ? coupon.storeType : 'ANY'
  const discountType = DISCOUNT_TYPES.includes(coupon.discountType) ? coupon.discountType : 'PERCENT'
  return {
    code: coupon.code,
    name: coupon.name,
    description: coupon.description ?? '',
    scope,
    storeType,
    storeId: coupon.storeId ?? '',
    discountType,
    value: discountType === 'FREE_DELIVERY' ? '' : text(coupon.value),
    maxDiscount: text(coupon.maxDiscount),
    minOrderSubtotal: coupon.minOrderSubtotal ? String(coupon.minOrderSubtotal) : '',
    validFrom: toLocalInput(coupon.validFrom),
    validUntil: toLocalInput(coupon.validUntil),
    totalUsageLimit: text(coupon.totalUsageLimit),
    perCustomerLimit: text(coupon.perCustomerLimit),
    public: coupon.public,
  }
}

/**
 * @param {CouponOwner} owner
 * @param {CouponFormValues} values
 * @returns {CouponRequest}
 */
export function couponFormToRequest(owner, values) {
  const scope = owner === 'admin' ? values.scope : 'STORE'
  const percent = values.discountType === 'PERCENT'
  return {
    code: normaliseCode(values.code),
    name: values.name.trim(),
    description: emptyToNull(values.description),
    scope,
    ...(owner === 'admin'
      ? scope === 'PLATFORM'
        ? { storeType: values.storeType, storeId: null }
        : { storeId: values.storeId.trim(), storeType: null }
      : {}),
    discountType: values.discountType,
    value: values.discountType === 'FREE_DELIVERY' ? null : toNumberOrNull(values.value),
    maxDiscount: percent ? toNumberOrNull(values.maxDiscount) : null,
    minOrderSubtotal: toNumberOrNull(values.minOrderSubtotal) ?? 0,
    validFrom: fromLocalInput(values.validFrom) ?? '',
    validUntil: fromLocalInput(values.validUntil) ?? '',
    totalUsageLimit: toNumberOrNull(values.totalUsageLimit),
    perCustomerLimit: toNumberOrNull(values.perCustomerLimit) ?? 1,
    public: values.public,
  }
}
