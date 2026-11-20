import { type ComponentProps, type ReactNode, useId } from 'react'
import { FormField, Input } from '../../shared/components/ui'
import { Icon, type IconName } from '../../shared/icons'
import { authInputClass } from './authStyles'

/** A field icon, positioned over the left edge of the input (the wrapper must be `relative`). */
export function FieldIcon({ name }: { name: IconName }) {
  return <Icon name={name} className="pointer-events-none absolute top-1/2 left-3.5 size-5 -translate-y-1/2" />
}

interface IconFieldProps extends ComponentProps<'input'> {
  label: ReactNode
  icon: IconName
  error?: string
  hint?: ReactNode
  /** Classes for the wrapper (grid placement). */
  containerClassName?: string
}

/** The larger text input of the auth screens: label, leading icon, hint and error. */
export function IconField({ label, icon, error, hint, containerClassName, id, required, ...props }: IconFieldProps) {
  const autoId = useId()
  const fieldId = id ?? autoId
  const describedBy = [error ? `${fieldId}-error` : null, hint ? `${fieldId}-hint` : null].filter(Boolean).join(' ') || undefined
  return (
    <FormField label={label} htmlFor={fieldId} error={error} hint={hint} required={required} className={containerClassName}>
      <div className="relative">
        <FieldIcon name={icon} />
        <Input
          id={fieldId}
          invalid={Boolean(error)}
          aria-required={required || undefined}
          aria-describedby={describedBy}
          className={authInputClass(Boolean(error))}
          {...props}
        />
      </div>
    </FormField>
  )
}
