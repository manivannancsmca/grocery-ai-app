import { type ReactNode, useId } from 'react'
import { cn } from '../../utils/cn'

interface SwitchProps {
  checked: boolean
  onChange: (checked: boolean) => void
  /** Visible label; with `hideLabel` it becomes the accessible name only. */
  label: ReactNode
  /** Accessible name when the visible label is not descriptive enough (e.g. per-row toggles). */
  ariaLabel?: string
  description?: ReactNode
  hideLabel?: boolean
  disabled?: boolean
  /** Shows a busy state (request running) and blocks toggling. */
  pending?: boolean
  size?: 'sm' | 'md'
  className?: string
}

/** On/off toggle (`role="switch"`): Space / Enter toggle it, the label is clickable. */
export function Switch({
  checked,
  onChange,
  label,
  ariaLabel,
  description,
  hideLabel = false,
  disabled = false,
  pending = false,
  size = 'md',
  className,
}: SwitchProps) {
  const id = useId()
  const descriptionId = `${id}-description`
  const blocked = disabled || pending

  return (
    <div className={cn('flex items-start gap-3', className)}>
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={ariaLabel ?? (hideLabel && typeof label === 'string' ? label : undefined)}
        aria-describedby={description ? descriptionId : undefined}
        aria-busy={pending || undefined}
        disabled={blocked}
        onClick={() => onChange(!checked)}
        className={cn(
          'relative inline-flex shrink-0 cursor-pointer items-center rounded-full transition-all duration-300 hover:brightness-105',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
          'disabled:cursor-not-allowed disabled:opacity-50',
          size === 'sm' ? 'h-5 w-9' : 'h-6 w-11',
          checked ? 'bg-gradient-to-r from-emerald-500 to-teal-500 shadow-inner shadow-emerald-900/20' : 'bg-slate-300 shadow-inner shadow-slate-900/10',
          pending && 'animate-pulse',
        )}
      >
        <span
          aria-hidden="true"
          className={cn(
            'inline-block rounded-full bg-white shadow-md shadow-slate-900/20 transition-transform duration-300 ease-[cubic-bezier(0.34,1.56,0.64,1)]',
            size === 'sm' ? 'size-4' : 'size-5',
            checked ? (size === 'sm' ? 'translate-x-4.5' : 'translate-x-5.5') : 'translate-x-0.5',
          )}
        />
      </button>
      {!hideLabel && (
        <div className="min-w-0 text-sm">
          <label htmlFor={id} className={cn('font-medium text-slate-700', blocked ? 'cursor-not-allowed' : 'cursor-pointer')}>
            {label}
          </label>
          {description && (
            <p id={descriptionId} className="text-xs text-slate-500">
              {description}
            </p>
          )}
        </div>
      )}
    </div>
  )
}
