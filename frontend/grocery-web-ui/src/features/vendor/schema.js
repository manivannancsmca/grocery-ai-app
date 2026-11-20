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

/** @typedef {import('../stakeholders/businessAddress').ValidationMode} ValidationMode */
/** @typedef {import('./api').VendorUpdateRequest} VendorUpdateRequest */
/** @typedef {import('./api').VendorView} VendorView */

/**
 * Required to submit: businessName, legalName, gstin, contactEmail, contactPhone, address incl. lat/lng.
 * @param {ValidationMode} mode
 */
export function vendorSchema(mode) {
  const required = mode === 'submit'
  return z.object({
    businessName: textField({ required, max: 120 }),
    legalName: textField({ required, max: 200 }),
    gstin: textField({
      required,
      pattern: PATTERNS.gstin,
      patternMessage: MESSAGES.gstin,
      normalize: (v) => v.toUpperCase(),
    }),
    fssaiLicense: textField({ pattern: PATTERNS.fssai, patternMessage: MESSAGES.fssai }),
    contactEmail: emailField(required),
    contactPhone: phoneField(required),
    address: businessAddressSchema(mode),
  })
}

/** @typedef {z.infer<ReturnType<typeof vendorSchema>>} VendorFormValues */

export const vendorDraftSchema = vendorSchema('draft')
export const vendorSubmitSchema = vendorSchema('submit')

/**
 * @param {VendorView} vendor
 * @returns {VendorFormValues}
 */
export function vendorToFormValues(vendor) {
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

/**
 * @param {VendorFormValues} values
 * @returns {VendorUpdateRequest}
 */
export function vendorFormToRequest(values) {
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
