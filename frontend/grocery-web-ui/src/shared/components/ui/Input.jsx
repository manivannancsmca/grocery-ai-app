import { cn } from '../../utils/cn'
import { controlBase, controlState } from './controlStyles'
import { DateTimeInput } from './DateTimeInput'

/** @typedef {import('react').ComponentProps} ComponentProps */
/** @typedef {import('./DateTimeInput').PickerKind} PickerKind */

/**
 * @param {string | undefined} type
 * @returns {type is PickerKind}
 */
function isPickerType(type) {
  return type === 'date' || type === 'time' || type === 'datetime-local'
}

/**
 * @typedef {object} ControlProps
 * @property {boolean} [invalid] Red border and `aria-invalid`.
 */

/**
 * Text input; `date`, `time` and `datetime-local` get the app's calendar / time picker on top of the native input.
 * @param {ComponentProps<'input'> & ControlProps} props
 */
export function Input({ invalid, className, type, ...props }) {
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

/** @param {ComponentProps<'select'> & ControlProps} props */
export function Select({ invalid, className, children, ...props }) {
  return (
    <select
      aria-invalid={invalid || undefined}
      className={cn(
        controlBase,
        controlState(invalid),
        'select-chevron select-modern h-10 cursor-pointer appearance-none pr-9 pl-3',
        className,
      )}
      {...props}
    >
      {children}
    </select>
  )
}

/** @param {ComponentProps<'textarea'> & ControlProps} props */
export function Textarea({ invalid, className, rows = 3, ...props }) {
  return (
    <textarea
      rows={rows}
      aria-invalid={invalid || undefined}
      className={cn(controlBase, controlState(invalid), 'px-3 py-2', className)}
      {...props}
    />
  )
}

/** @param {Omit<ComponentProps<'input'>, 'type'>} props */
export function Checkbox({ className, ...props }) {
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
