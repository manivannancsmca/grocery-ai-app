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
  type ValidationMode,
} from '../stakeholders/businessAddress'
import type { VendorUpdateRequest, VendorView } from './api'

/** Required to submit: businessName, legalName, gstin, contactEmail, contactPhone, address incl. lat/lng. */
export function vendorSchema(mode: ValidationMode) {
  const required = mode === 'submit'
  return z.object({
    businessName: textField({ required, max: 120 }),
    legalName: textField({ required, max: 200 }),
    gstin: textField({ required, pattern: PATTERNS.gstin, patternMessage: MESSAGES.gstin, normalize: (v) => v.toUpperCase() }),
    fssaiLicense: textField({ pattern: PATTERNS.fssai, patternMessage: MESSAGES.fssai }),
    contactEmail: emailField(required),
    contactPhone: phoneField(required),
    address: businessAddressSchema(mode),
  })
}

export type VendorFormValues = z.infer<ReturnType<typeof vendorSchema>>

export const vendorDraftSchema = vendorSchema('draft')
export const vendorSubmitSchema = vendorSchema('submit')

export function vendorToFormValues(vendor: VendorView): VendorFormValues {
  return {
    businessName: vendor.businessName ?? '',
    legalName: vendor.legalName ?? '',
    gstin: vendor.gstin ?? '',
    fssaiLicense: vendor.fssaiLicense ?? '',
    contactEmail: vendor.contactEmail ?? '',
    contactPhone: vendor.contactPhone ?? '',
    address: businessAddressToFormValues(vendor.address),
  }
}

export function vendorFormToRequest(values: VendorFormValues): VendorUpdateRequest {
  const phone = emptyToNull(values.contactPhone)
  return {
    businessName: emptyToNull(values.businessName),
    legalName: emptyToNull(values.legalName),
    gstin: emptyToNull(values.gstin)?.toUpperCase() ?? null,
    fssaiLicense: emptyToNull(values.fssaiLicense),
    contactEmail: emptyToNull(values.contactEmail),
    contactPhone: phone ? normalizePhone(phone) : null,
    address: businessAddressToRequest(values.address),
  }
}
