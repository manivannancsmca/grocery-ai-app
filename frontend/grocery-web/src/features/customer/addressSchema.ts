import { z } from 'zod'
import {
  coordinateField,
  emptyToNull,
  MESSAGES,
  normalizePhone,
  numberToText,
  PATTERNS,
  phoneField,
  textField,
  toNumberOrNull,
} from '../../shared/forms/validation'
import type { Address, AddressRequest } from './api'

export const addressSchema = z.object({
  label: textField({ required: true, requiredMessage: 'Give the address a label, e.g. Home.', max: 40 }),
  recipientName: textField({ required: true, requiredMessage: 'Enter who receives deliveries here.', max: 120 }),
  phone: phoneField(true),
  line1: textField({ required: true, requiredMessage: 'Enter the house / flat and street.', max: 200 }),
  line2: textField({ max: 200 }),
  landmark: textField({ max: 120 }),
  city: textField({ required: true, max: 80 }),
  state: textField({ required: true, max: 80 }),
  postalCode: textField({ required: true, pattern: PATTERNS.postalCode, patternMessage: MESSAGES.postalCode }),
  country: z.string().min(1),
  latitude: coordinateField('latitude', true),
  longitude: coordinateField('longitude', true),
  isDefault: z.boolean(),
})

export type AddressFormValues = z.infer<typeof addressSchema>

export function emptyAddressValues(recipient?: { name?: string | null; phone?: string | null }): AddressFormValues {
  return {
    label: '',
    recipientName: recipient?.name ?? '',
    phone: recipient?.phone ?? '',
    line1: '',
    line2: '',
    landmark: '',
    city: '',
    state: '',
    postalCode: '',
    country: 'IN',
    latitude: '',
    longitude: '',
    isDefault: false,
  }
}

export function addressToFormValues(address: Address): AddressFormValues {
  return {
    label: address.label ?? '',
    recipientName: address.recipientName ?? '',
    phone: address.phone ?? '',
    line1: address.line1 ?? '',
    line2: address.line2 ?? '',
    landmark: address.landmark ?? '',
    city: address.city ?? '',
    state: address.state ?? '',
    postalCode: address.postalCode ?? '',
    country: address.country || 'IN',
    latitude: numberToText(address.latitude),
    longitude: numberToText(address.longitude),
    isDefault: address.isDefault,
  }
}

export function formValuesToAddressRequest(values: AddressFormValues): AddressRequest {
  return {
    label: values.label.trim(),
    recipientName: values.recipientName.trim(),
    phone: normalizePhone(values.phone.trim()),
    line1: values.line1.trim(),
    line2: emptyToNull(values.line2),
    landmark: emptyToNull(values.landmark),
    city: values.city.trim(),
    state: values.state.trim(),
    postalCode: values.postalCode.trim(),
    country: values.country || 'IN',
    latitude: toNumberOrNull(values.latitude),
    longitude: toNumberOrNull(values.longitude),
    isDefault: values.isDefault,
  }
}
