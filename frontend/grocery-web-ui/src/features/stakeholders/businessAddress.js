import { z } from 'zod'
import {
  coordinateField,
  emptyToNull,
  MESSAGES,
  numberToText,
  PATTERNS,
  textField,
  toNumberOrNull,
} from '../../shared/forms/validation'

/** @typedef {import('./types').BusinessAddress} BusinessAddress */

/** @typedef {'draft' | 'submit'} ValidationMode */

/**
 * @typedef {object} BusinessAddressFormValues
 * @property {string} line1
 * @property {string} line2
 * @property {string} city
 * @property {string} state
 * @property {string} postalCode
 * @property {string} country
 * @property {string} latitude
 * @property {string} longitude
 */

/**
 * Address rules: formats always; line1/city/state/PIN and latitude/longitude required to submit.
 * @param {ValidationMode} mode
 */
export function businessAddressSchema(mode) {
  const required = mode === 'submit'
  return z.object({
    line1: textField({ required, max: 200 }),
    line2: textField({ max: 200 }),
    city: textField({ required, max: 80 }),
    state: textField({ required, max: 80 }),
    postalCode: textField({ required, pattern: PATTERNS.postalCode, patternMessage: MESSAGES.postalCode }),
    country: z.string(),
    latitude: coordinateField('latitude', required),
    longitude: coordinateField('longitude', required),
  })
}

/**
 * @param {BusinessAddress | null | undefined} address
 * @returns {BusinessAddressFormValues}
 */
export function businessAddressToFormValues(address) {
  return {
    line1: address?.line1 ?? '',
    line2: address?.line2 ?? '',
    city: address?.city ?? '',
    state: address?.state ?? '',
    postalCode: address?.postalCode ?? '',
    country: address?.country || 'IN',
    latitude: numberToText(address?.latitude),
    longitude: numberToText(address?.longitude),
  }
}

/**
 * Form → API. An untouched address is sent as null rather than an object full of nulls.
 * @param {BusinessAddressFormValues} values
 * @returns {BusinessAddress | null}
 */
export function businessAddressToRequest(values) {
  const address = {
    line1: emptyToNull(values.line1),
    line2: emptyToNull(values.line2),
    city: emptyToNull(values.city),
    state: emptyToNull(values.state),
    postalCode: emptyToNull(values.postalCode),
    country: values.country || 'IN',
    latitude: toNumberOrNull(values.latitude),
    longitude: toNumberOrNull(values.longitude),
  }
  const isEmpty = [
    address.line1,
    address.line2,
    address.city,
    address.state,
    address.postalCode,
    address.latitude,
    address.longitude,
  ].every((value) => value === null)
  return isEmpty ? null : address
}
