import type { FieldPath, FieldValues, UseFormSetError } from 'react-hook-form'
import { ApiError } from '../api/problemDetail'

/** Every dotted path in a form-values object (`address`, `address.city`, `openingHours.0.opensAt`, …). */
export function collectPaths(value: unknown, prefix = ''): string[] {
  if (typeof value !== 'object' || value === null) return prefix ? [prefix] : []
  const entries = Array.isArray(value) ? value.map((item, index) => [String(index), item] as const) : Object.entries(value)
  const nested = entries.flatMap(([key, child]) => collectPaths(child, prefix ? `${prefix}.${key}` : key))
  return prefix ? [prefix, ...nested] : nested
}

/** `openingHours[0].opensAt` → `openingHours.0.opensAt` */
function normaliseServerField(field: string): string {
  return field.replace(/\[(\d+)]/g, '.$1').replace(/^\./, '')
}

/** Finds the closest known form path for a server field (falls back to its parents). */
function resolveField(field: string, known: ReadonlySet<string>): string | null {
  const segments = normaliseServerField(field).split('.')
  while (segments.length > 0) {
    const candidate = segments.join('.')
    if (known.has(candidate)) return candidate
    segments.pop()
  }
  return null
}

export interface ServerErrorResult {
  /** Messages for fields the form does not show (render them in a form-level alert). */
  unmatched: string[]
  /** True when at least one field error was placed on a form field. */
  placedOnFields: boolean
}

/**
 * Puts a ProblemDetail's `errors[]` (VALIDATION_FAILED, PROFILE_INCOMPLETE) on the matching form fields.
 * `formValues` (usually the default values) tells which paths exist in the form.
 */
export function applyServerFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  formValues: T,
  fieldAliases: Readonly<Record<string, string>> = {},
): ServerErrorResult {
  if (!(error instanceof ApiError)) return { unmatched: [], placedOnFields: false }

  const known = new Set(collectPaths(formValues))
  const unmatched: string[] = []
  let placedOnFields = false
  let shouldFocus = true

  for (const { field, message } of error.fieldErrors) {
    const target = resolveField(fieldAliases[field] ?? field, known)
    if (target) {
      setError(target as FieldPath<T>, { type: 'server', message }, { shouldFocus })
      shouldFocus = false
      placedOnFields = true
    } else {
      unmatched.push(field ? `${field}: ${message}` : message)
    }
  }
  return { unmatched, placedOnFields }
}

/** Reads a message from any RHF error node (plain field, array root, or first array item). */
export function fieldErrorMessage(node: unknown): string | undefined {
  if (typeof node !== 'object' || node === null) return undefined
  const record = node as { message?: unknown; root?: unknown }
  if (typeof record.message === 'string' && record.message) return record.message
  const rootMessage = fieldErrorMessage(record.root)
  if (rootMessage) return rootMessage
  if (Array.isArray(node)) {
    for (const item of node) {
      const message = fieldErrorMessage(item)
      if (message) return message
    }
  }
  return undefined
}
