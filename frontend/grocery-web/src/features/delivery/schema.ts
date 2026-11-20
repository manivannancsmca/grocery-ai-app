import { z } from 'zod'
import { emptyToNull, MESSAGES, normalizePhone, phoneField, textField } from '../../shared/forms/validation'
import type { ValidationMode } from '../stakeholders/businessAddress'
import { type PartnerUpdateRequest, type PartnerView, VEHICLE_TYPES, type VehicleType } from './api'

export const VEHICLE_LABELS: Record<VehicleType, string> = {
  BICYCLE: 'Bicycle',
  SCOOTER: 'Scooter',
  MOTORCYCLE: 'Motorcycle',
  CAR: 'Car',
}

/** Uppercase, no spaces: `ka 01 ab 1234` → `KA01AB1234`. */
export function normalizeRegistration(value: string): string {
  return value.replace(/[\s-]/g, '').toUpperCase()
}

/**
 * Required to submit: fullName, phone, city, vehicleType; vehicleNumber and drivingLicenseNumber
 * unless the vehicle is a bicycle.
 */
export function partnerSchema(mode: ValidationMode) {
  const required = mode === 'submit'
  return z
    .object({
      fullName: textField({ required, max: 120 }),
      phone: phoneField(required),
      city: textField({ required, max: 80 }),
      vehicleType: z.union([z.enum(VEHICLE_TYPES), z.literal('')]),
      vehicleNumber: textField({ max: 20 }),
      drivingLicenseNumber: textField({ max: 30 }),
    })
    .superRefine((values, ctx) => {
      if (!required) return
      if (values.vehicleType === '') {
        ctx.addIssue({ code: 'custom', message: 'Choose your vehicle.', path: ['vehicleType'] })
        return
      }
      if (values.vehicleType === 'BICYCLE') return
      if (!values.vehicleNumber.trim()) {
        ctx.addIssue({ code: 'custom', message: MESSAGES.required, path: ['vehicleNumber'] })
      }
      if (!values.drivingLicenseNumber.trim()) {
        ctx.addIssue({ code: 'custom', message: MESSAGES.required, path: ['drivingLicenseNumber'] })
      }
    })
}

export type PartnerFormValues = z.infer<ReturnType<typeof partnerSchema>>

export const partnerDraftSchema = partnerSchema('draft')
export const partnerSubmitSchema = partnerSchema('submit')

export function partnerToFormValues(
  partner: PartnerView,
  fallback?: { fullName?: string | null; phone?: string | null },
): PartnerFormValues {
  return {
    fullName: partner.fullName ?? fallback?.fullName ?? '',
    phone: partner.phone ?? fallback?.phone ?? '',
    city: partner.city ?? '',
    vehicleType: partner.vehicleType ?? '',
    vehicleNumber: partner.vehicleNumber ?? '',
    drivingLicenseNumber: partner.drivingLicenseNumber ?? '',
  }
}

export function partnerFormToRequest(values: PartnerFormValues): PartnerUpdateRequest {
  const phone = emptyToNull(values.phone)
  const vehicleNumber = emptyToNull(values.vehicleNumber)
  const licence = emptyToNull(values.drivingLicenseNumber)
  return {
    fullName: emptyToNull(values.fullName),
    phone: phone ? normalizePhone(phone) : null,
    city: emptyToNull(values.city),
    vehicleType: values.vehicleType === '' ? null : values.vehicleType,
    vehicleNumber: vehicleNumber ? normalizeRegistration(vehicleNumber) : null,
    drivingLicenseNumber: licence ? normalizeRegistration(licence) : null,
  }
}
