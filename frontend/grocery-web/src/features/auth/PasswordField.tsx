import { type ComponentProps, type KeyboardEvent, type ReactNode, useId, useState } from 'react'
import { FormField, Input } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { authInputClass } from './authStyles'
import { FieldIcon } from './IconField'

interface PasswordFieldProps extends Omit<ComponentProps<'input'>, 'type'> {
  label: ReactNode
  error?: string
  hint?: ReactNode
  /** What the show/hide button reveals, for its accessible name ("Show password"). */
  revealLabel?: string
  /** Classes for the wrapper (grid placement). */
  containerClassName?: string
}

/** Password input with a show/hide button and a Caps Lock warning. */
export function PasswordField({
  label,
  error,
  hint,
  revealLabel = 'password',
  containerClassName,
  id,
  required,
  onKeyDown,
  onKeyUp,
  onBlur,
  ...props
}: PasswordFieldProps) {
  const autoId = useId()
  const fieldId = id ?? autoId
  const [visible, setVisible] = useState(false)
  const [capsLock, setCapsLock] = useState(false)
  const capsId = `${fieldId}-caps`

  const trackCapsLock = (event: KeyboardEvent<HTMLInputElement>) => setCapsLock(event.getModifierState('CapsLock'))
  const describedBy =
    [error ? `${fieldId}-error` : null, capsLock ? capsId : null, hint ? `${fieldId}-hint` : null].filter(Boolean).join(' ') ||
    undefined

  return (
    <FormField label={label} htmlFor={fieldId} error={error} hint={hint} required={required} className={containerClassName}>
      <div className="relative">
        <FieldIcon name="lock" />
        <Input
          id={fieldId}
          type={visible ? 'text' : 'password'}
          invalid={Boolean(error)}
          aria-required={required || undefined}
          aria-describedby={describedBy}
          className={authInputClass(Boolean(error), 'pr-12!')}
          onKeyDown={(event) => {
            trackCapsLock(event)
            onKeyDown?.(event)
          }}
          onKeyUp={(event) => {
            trackCapsLock(event)
            onKeyUp?.(event)
          }}
          onBlur={(event) => {
            setCapsLock(false)
            void onBlur?.(event)
          }}
          {...props}
        />
        <button
          type="button"
          aria-label={`${visible ? 'Hide' : 'Show'} ${revealLabel}`}
          aria-pressed={visible}
          aria-controls={fieldId}
          onClick={() => setVisible((shown) => !shown)}
          className="absolute inset-y-0 right-0 flex w-12 items-center justify-center rounded-r-xl text-slate-400 transition-colors hover:text-slate-700 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-emerald-600"
        >
          <Icon name={visible ? 'eyeOff' : 'eye'} className="size-5" />
        </button>
      </div>
      {capsLock && (
        <p id={capsId} role="status" className="text-xs font-medium text-amber-700">
          Caps Lock is on.
        </p>
      )}
    </FormField>
  )
}
