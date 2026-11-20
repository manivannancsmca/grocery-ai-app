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
import type { BusinessAddress } from './types'

export type ValidationMode = 'draft' | 'submit'

export interface BusinessAddressFormValues {
  line1: string
  line2: string
  city: string
  state: string
  postalCode: string
  country: string
  latitude: string
  longitude: string
}

/** Address rules: formats always; line1/city/state/PIN and latitude/longitude required to submit. */
export function businessAddressSchema(mode: ValidationMode) {
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

export function businessAddressToFormValues(address: BusinessAddress | null | undefined): BusinessAddressFormValues {
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

/** Form → API. An untouched address is sent as null rather than an object full of nulls. */
export function businessAddressToRequest(values: BusinessAddressFormValues): BusinessAddress | null {
  const address: BusinessAddress = {
    line1: emptyToNull(values.line1),
    line2: emptyToNull(values.line2),
    city: emptyToNull(values.city),
    state: emptyToNull(values.state),
    postalCode: emptyToNull(values.postalCode),
    country: values.country || 'IN',
    latitude: toNumberOrNull(values.latitude),
    longitude: toNumberOrNull(values.longitude),
  }
  const isEmpty = [address.line1, address.line2, address.city, address.state, address.postalCode, address.latitude, address.longitude].every(
    (value) => value === null,
  )
  return isEmpty ? null : address
}
