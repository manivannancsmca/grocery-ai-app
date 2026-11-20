import type { ComponentProps } from 'react'
import { cn } from '../../utils/cn'
import { controlBase, controlState } from './controlStyles'
import { DateTimeInput, type PickerKind } from './DateTimeInput'

function isPickerType(type: string | undefined): type is PickerKind {
  return type === 'date' || type === 'time' || type === 'datetime-local'
}

interface ControlProps {
  /** Red border and `aria-invalid`. */
  invalid?: boolean
}

/** Text input; `date`, `time` and `datetime-local` get the app's calendar / time picker on top of the native input. */
export function Input({ invalid, className, type, ...props }: ComponentProps<'input'> & ControlProps) {
  if (isPickerType(type)) {
    return (
      <DateTimeInput
        kind={type}
        aria-invalid={invalid || undefined}
        inputClassName={cn(controlBase, controlState(invalid))}
        className={className}
        {...props}
      />
    )
  }
  return (
    <input
      type={type}
      aria-invalid={invalid || undefined}
      className={cn(controlBase, controlState(invalid), 'h-10 px-3', className)}
      {...props}
    />
  )
}

export function Select({ invalid, className, children, ...props }: ComponentProps<'select'> & ControlProps) {
  return (
    <select
      aria-invalid={invalid || undefined}
      className={cn(controlBase, controlState(invalid), 'select-chevron select-modern h-10 cursor-pointer appearance-none pr-9 pl-3', className)}
      {...props}
    >
      {children}
    </select>
  )
}

export function Textarea({ invalid, className, rows = 3, ...props }: ComponentProps<'textarea'> & ControlProps) {
  return (
    <textarea
      rows={rows}
      aria-invalid={invalid || undefined}
      className={cn(controlBase, controlState(invalid), 'px-3 py-2', className)}
      {...props}
    />
  )
}

export function Checkbox({ className, ...props }: Omit<ComponentProps<'input'>, 'type'>) {
  return (
    <input
      type="checkbox"
      className={cn(
        'size-4 cursor-pointer rounded border-slate-300 text-emerald-600 accent-emerald-600 transition focus:ring-2 focus:ring-emerald-500/30 disabled:cursor-not-allowed',
        className,
      )}
      {...props}
    />
  )
}
