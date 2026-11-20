import { useId } from 'react'
import { cn } from '../../utils/cn'
import { Input, Select, Textarea } from './Input'

/** @typedef {import('react').ComponentProps} ComponentProps */
/** @typedef {import('react').ReactNode} ReactNode */

/**
 * @typedef {object} FormFieldProps
 * @property {ReactNode} label
 * @property {string} htmlFor
 * @property {string} [error]
 * @property {ReactNode} [hint]
 * @property {boolean} [required]
 * @property {string} [className]
 * @property {ReactNode} children
 */

/**
 * @param {string} id
 * @param {string} [error]
 * @param {ReactNode} [hint]
 * @returns {string | undefined}
 */
function fieldDescriptionIds(id, error, hint) {
  const ids = [error ? `${id}-error` : null, hint ? `${id}-hint` : null].filter(Boolean)
  return ids.length > 0 ? ids.join(' ') : undefined
}

/**
 * Label + control + hint + error, wired together for screen readers.
 * @param {FormFieldProps} props
 */
export function FormField({ label, htmlFor, error, hint, required, className, children }) {
  return (
    <div className={cn('space-y-1.5', className)}>
      <label htmlFor={htmlFor} className="block text-sm font-medium text-slate-700">
        {label}
        {required && (
          <span className="ml-0.5 text-red-600" aria-hidden="true">
            *
          </span>
        )}
      </label>
      {children}
      {hint && (
        <p id={`${htmlFor}-hint`} className="text-xs text-slate-500">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${htmlFor}-error`} className="text-xs font-medium text-red-600">
          {error}
        </p>
      )}
    </div>
  )
}

/**
 * @typedef {object} LabelledProps
 * @property {ReactNode} label
 * @property {string} [error]
 * @property {ReactNode} [hint]
 * @property {string} [containerClassName] Classes for the wrapper (grid placement).
 */

/** @param {ComponentProps<'input'> & LabelledProps} props */
export function TextField({ label, error, hint, containerClassName, id, required, ...props }) {
  const autoId = useId()
  const fieldId = id ?? autoId
  return (
    <FormField
      label={label}
      htmlFor={fieldId}
      error={error}
      hint={hint}
      required={required}
      className={containerClassName}
    >
      <Input
        id={fieldId}
        invalid={Boolean(error)}
        aria-required={required || undefined}
        aria-describedby={fieldDescriptionIds(fieldId, error, hint)}
        {...props}
      />
    </FormField>
  )
}

/** @param {ComponentProps<'select'> & LabelledProps} props */
export function SelectField({ label, error, hint, containerClassName, id, required, children, ...props }) {
  const autoId = useId()
  const fieldId = id ?? autoId
  return (
    <FormField
      label={label}
      htmlFor={fieldId}
      error={error}
      hint={hint}
      required={required}
      className={containerClassName}
    >
      <Select
        id={fieldId}
        invalid={Boolean(error)}
        aria-required={required || undefined}
        aria-describedby={fieldDescriptionIds(fieldId, error, hint)}
        {...props}
      >
        {children}
      </Select>
    </FormField>
  )
}

/** @param {ComponentProps<'textarea'> & LabelledProps} props */
export function TextareaField({ label, error, hint, containerClassName, id, required, ...props }) {
  const autoId = useId()
  const fieldId = id ?? autoId
  return (
    <FormField
      label={label}
      htmlFor={fieldId}
      error={error}
      hint={hint}
      required={required}
      className={containerClassName}
    >
      <Textarea
        id={fieldId}
        invalid={Boolean(error)}
        aria-required={required || undefined}
        aria-describedby={fieldDescriptionIds(fieldId, error, hint)}
        {...props}
      />
    </FormField>
  )
}
