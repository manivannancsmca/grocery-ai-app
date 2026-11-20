import { z } from 'zod'

/** Regexes copied from the Phase 2 API contract. */
export const PATTERNS = {
  phone: /^\+?[0-9]{10,15}$/,
  gstin: /^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/,
  fssai: /^[0-9]{14}$/,
  postalCode: /^[1-9][0-9]{5}$/,
  cuisine: /^[A-Z_]{2,30}$/,
  email: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
  /** At least one letter and one digit (password rule). */
  letter: /[A-Za-z]/,
  digit: /[0-9]/,
} as const

export const MESSAGES = {
  required: 'This field is required.',
  phone: 'Enter 10–15 digits, optionally starting with +.',
  email: 'Enter a valid email address.',
  gstin: 'Enter a valid 15-character GSTIN, e.g. 29ABCDE1234F1Z5.',
  fssai: 'FSSAI licence numbers have 14 digits.',
  postalCode: 'Enter a valid 6-digit PIN code.',
  latitude: 'Latitude must be a number between -90 and 90.',
  longitude: 'Longitude must be a number between -180 and 180.',
} as const

/** Phones may be typed with spaces or dashes; the API wants `^\+?[0-9]{10,15}$`. */
export function normalizePhone(value: string): string {
  return value.replace(/[\s()-]/g, '')
}

/** Empty / whitespace-only form text → null (the API's "not set"). */
export function emptyToNull(value: string | null | undefined): string | null {
  const trimmed = value?.trim() ?? ''
  return trimmed === '' ? null : trimmed
}

/** Form text → number, or null when empty. Assumes the form already validated it. */
export function toNumberOrNull(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

export function numberToText(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : String(value)
}

interface TextRule {
  required?: boolean
  requiredMessage?: string
  pattern?: RegExp
  patternMessage?: string
  /** Applied before matching the pattern (e.g. strip spaces from phone numbers). */
  normalize?: (value: string) => string
  max?: number
}

/**
 * A string form field. Empty is allowed unless `required`; a non-empty value must match `pattern`.
 * Output equals input (no transforms), so the same type serves as form values.
 */
export function textField(rule: TextRule = {}) {
  return z.string().superRefine((raw, ctx) => {
    const value = raw.trim()
    if (value === '') {
      if (rule.required) ctx.addIssue({ code: 'custom', message: rule.requiredMessage ?? MESSAGES.required })
      return
    }
    if (rule.max !== undefined && value.length > rule.max) {
      ctx.addIssue({ code: 'custom', message: `Use at most ${rule.max} characters.` })
      return
    }
    const candidate = rule.normalize ? rule.normalize(value) : value
    if (rule.pattern && !rule.pattern.test(candidate)) {
      ctx.addIssue({ code: 'custom', message: rule.patternMessage ?? 'Invalid value.' })
    }
  })
}

export const phoneField = (required = false) =>
  textField({ required, pattern: PATTERNS.phone, patternMessage: MESSAGES.phone, normalize: normalizePhone })

export const emailField = (required = false) =>
  textField({ required, pattern: PATTERNS.email, patternMessage: MESSAGES.email, max: 254 })

/** Latitude / longitude typed as text; must parse and be within range when present. */
export function coordinateField(kind: 'latitude' | 'longitude', required = false) {
  const limit = kind === 'latitude' ? 90 : 180
  return z.string().superRefine((raw, ctx) => {
    const value = raw.trim()
    if (value === '') {
      if (required) ctx.addIssue({ code: 'custom', message: MESSAGES.required })
      return
    }
    const parsed = Number(value)
    if (!Number.isFinite(parsed) || parsed < -limit || parsed > limit) {
      ctx.addIssue({ code: 'custom', message: MESSAGES[kind] })
    }
  })
}

/** Password rule from the contract: 8–64 characters, at least one letter and one digit. */
export const passwordField = z
  .string()
  .min(1, 'Enter a password.')
  .superRefine((value, ctx) => {
    if (value.length > 0 && (value.length < 8 || value.length > 64)) {
      ctx.addIssue({ code: 'custom', message: 'Use 8 to 64 characters.' })
    } else if (value.length > 0 && (!PATTERNS.letter.test(value) || !PATTERNS.digit.test(value))) {
      ctx.addIssue({ code: 'custom', message: 'Include at least one letter and one digit.' })
    }
  })

/** http(s) URL (images): parses with the URL API and allows only the two web schemes. */
export function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

/** Optional http(s) URL of at most `max` characters (contract: images ≤ 500). */
export function httpUrlField(max = 500) {
  return z.string().superRefine((raw, ctx) => {
    const value = raw.trim()
    if (value === '') return
    if (value.length > max) ctx.addIssue({ code: 'custom', message: `Use at most ${max} characters.` })
    else if (!isHttpUrl(value)) ctx.addIssue({ code: 'custom', message: 'Enter a web address starting with http:// or https://.' })
  })
}

interface DecimalRule {
  required?: boolean
  requiredMessage?: string
  /** Inclusive lower bound; `exclusiveMin` makes it "greater than". */
  min: number
  exclusiveMin?: boolean
  max: number
  decimals: number
  integer?: boolean
}

/** A number typed as text (prices, pack sizes, sort orders) with range and decimal-places checks. */
export function decimalField(rule: DecimalRule) {
  return z.string().superRefine((raw, ctx) => {
    const value = raw.trim()
    if (value === '') {
      if (rule.required) ctx.addIssue({ code: 'custom', message: rule.requiredMessage ?? MESSAGES.required })
      return
    }
    const shape = rule.integer ? /^-?\d+$/ : /^-?\d+(\.\d+)?$/
    const parsed = Number(value)
    if (!shape.test(value) || !Number.isFinite(parsed)) {
      ctx.addIssue({ code: 'custom', message: rule.integer ? 'Enter a whole number.' : 'Enter a number, e.g. 12.5.' })
      return
    }
    const tooLow = rule.exclusiveMin ? parsed <= rule.min : parsed < rule.min
    if (tooLow || parsed > rule.max) {
      const lower = rule.exclusiveMin ? `more than ${rule.min}` : `at least ${rule.min}`
      ctx.addIssue({ code: 'custom', message: `Enter a value ${lower} and at most ${rule.max.toLocaleString('en-IN')}.` })
      return
    }
    const decimals = value.split('.')[1]?.length ?? 0
    if (decimals > rule.decimals) {
      ctx.addIssue({ code: 'custom', message: `Use at most ${rule.decimals} decimal place${rule.decimals === 1 ? '' : 's'}.` })
    }
  })
}
