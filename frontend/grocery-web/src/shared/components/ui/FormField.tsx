import { type ComponentProps, type ReactNode, useId } from 'react'
import { cn } from '../../utils/cn'
import { Input, Select, Textarea } from './Input'

interface FormFieldProps {
  label: ReactNode
  htmlFor: string
  error?: string
  hint?: ReactNode
  required?: boolean
  className?: string
  children: ReactNode
}

function fieldDescriptionIds(id: string, error?: string, hint?: ReactNode): string | undefined {
  const ids = [error ? `${id}-error` : null, hint ? `${id}-hint` : null].filter(Boolean)
  return ids.length > 0 ? ids.join(' ') : undefined
}

/** Label + control + hint + error, wired together for screen readers. */
export function FormField({ label, htmlFor, error, hint, required, className, children }: FormFieldProps) {
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

interface LabelledProps {
  label: ReactNode
  error?: string
  hint?: ReactNode
  /** Classes for the wrapper (grid placement). */
  containerClassName?: string
}

export function TextField({
  label,
  error,
  hint,
  containerClassName,
  id,
  required,
  ...props
}: ComponentProps<'input'> & LabelledProps) {
  const autoId = useId()
  const fieldId = id ?? autoId
  return (
    <FormField label={label} htmlFor={fieldId} error={error} hint={hint} required={required} className={containerClassName}>
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

export function SelectField({
  label,
  error,
  hint,
  containerClassName,
  id,
  required,
  children,
  ...props
}: ComponentProps<'select'> & LabelledProps) {
  const autoId = useId()
  const fieldId = id ?? autoId
  return (
    <FormField label={label} htmlFor={fieldId} error={error} hint={hint} required={required} className={containerClassName}>
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

export function TextareaField({
  label,
  error,
  hint,
  containerClassName,
  id,
  required,
  ...props
}: ComponentProps<'textarea'> & LabelledProps) {
  const autoId = useId()
  const fieldId = id ?? autoId
  return (
    <FormField label={label} htmlFor={fieldId} error={error} hint={hint} required={required} className={containerClassName}>
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
