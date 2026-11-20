import { ApiError } from '../api/problemDetail'

/** @typedef {import('react-hook-form').FieldValues} FieldValues */
/** @typedef {import('react-hook-form').UseFormSetError} UseFormSetError */

/**
 * Every dotted path in a form-values object (`address`, `address.city`, `openingHours.0.opensAt`, …).
 * @param {unknown} value
 * @param [prefix='']
 * @returns {string[]}
 */
export function collectPaths(value, prefix = '') {
  if (typeof value !== 'object' || value === null) return prefix ? [prefix] : []
  const entries = Array.isArray(value) ? value.map((item, index) => [String(index), item]) : Object.entries(value)
  const nested = entries.flatMap(([key, child]) => collectPaths(child, prefix ? `${prefix}.${key}` : key))
  return prefix ? [prefix, ...nested] : nested
}

/**
 * `openingHours[0].opensAt` → `openingHours.0.opensAt`
 * @param {string} field
 * @returns {string}
 */
function normaliseServerField(field) {
  return field.replace(/\[(\d+)]/g, '.$1').replace(/^\./, '')
}

/**
 * Finds the closest known form path for a server field (falls back to its parents).
 * @param {string} field
 * @param {ReadonlySet<string>} known
 * @returns {string | null}
 */
function resolveField(field, known) {
  const segments = normaliseServerField(field).split('.')
  while (segments.length > 0) {
    const candidate = segments.join('.')
    if (known.has(candidate)) return candidate
    segments.pop()
  }
  return null
}

/**
 * @typedef {object} ServerErrorResult
 * @property {string[]} unmatched Messages for fields the form does not show (render them in a form-level alert).
 * @property {boolean} placedOnFields True when at least one field error was placed on a form field.
 */

/**
 * Puts a ProblemDetail's `errors[]` (VALIDATION_FAILED, PROFILE_INCOMPLETE) on the matching form fields.
 * `formValues` (usually the default values) tells which paths exist in the form.
 * @template {FieldValues} T
 * @param {unknown} error
 * @param {UseFormSetError<T>} setError
 * @param {T} formValues
 * @param {Readonly<Record<string, string>>} [fieldAliases={}]
 * @returns {ServerErrorResult}
 */
export function applyServerFieldErrors(error, setError, formValues, fieldAliases = {}) {
  if (!(error instanceof ApiError)) return { unmatched: [], placedOnFields: false }

  const known = new Set(collectPaths(formValues))
  const unmatched = []
  let placedOnFields = false
  let shouldFocus = true

  for (const { field, message } of error.fieldErrors) {
    const target = resolveField(fieldAliases[field] ?? field, known)
    if (target) {
      setError(target, { type: 'server', message }, { shouldFocus })
      shouldFocus = false
      placedOnFields = true
    } else {
      unmatched.push(field ? `${field}: ${message}` : message)
    }
  }
  return { unmatched, placedOnFields }
}

/**
 * Reads a message from any RHF error node (plain field, array root, or first array item).
 * @param {unknown} node
 * @returns {string | undefined}
 */
export function fieldErrorMessage(node) {
  if (typeof node !== 'object' || node === null) return undefined
  const record = node
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
