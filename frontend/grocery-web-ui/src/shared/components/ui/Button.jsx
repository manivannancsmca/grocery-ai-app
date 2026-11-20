import { cn } from '../../utils/cn'

/** @typedef {import('react').ComponentProps} ComponentProps */

/** @typedef {'primary' | 'secondary' | 'ghost' | 'danger'} ButtonVariant */
/** @typedef {'sm' | 'md'} ButtonSize */

/** @type {Record<ButtonVariant, string>} */
const variantClasses = {
  primary:
    'bg-gradient-to-b from-emerald-500 to-emerald-600 text-white shadow-md shadow-emerald-600/25 ring-1 ring-inset ring-emerald-700/20 ' +
    'hover:from-emerald-500 hover:to-emerald-700 hover:shadow-lg hover:shadow-emerald-600/30 focus-visible:outline-emerald-600',
  secondary:
    'bg-white text-slate-700 shadow-xs ring-1 ring-inset ring-slate-300 hover:bg-emerald-50/60 hover:text-emerald-800 hover:ring-emerald-300 ' +
    'focus-visible:outline-emerald-600',
  ghost: 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-slate-400',
  danger:
    'bg-gradient-to-b from-red-500 to-red-600 text-white shadow-md shadow-red-600/25 ring-1 ring-inset ring-red-700/20 ' +
    'hover:to-red-700 hover:shadow-lg hover:shadow-red-600/30 focus-visible:outline-red-600',
}

/** @type {Record<ButtonSize, string>} */
const sizeClasses = {
  sm: 'h-8 px-3 text-sm',
  md: 'h-10 px-4 text-sm',
}

/** @typedef {ComponentProps<'button'> & { variant?: ButtonVariant, size?: ButtonSize }} ButtonProps */

/**
 * The app's button: a lift on hover and a press on click (both off with reduced motion and when disabled).
 * @param {ButtonProps} props
 */
export function Button({ variant = 'primary', size = 'md', className, type = 'button', ...props }) {
  return (
    <button
      type={type}
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-xl font-semibold transition-all duration-200 ease-out select-none',
        'enabled:hover:-translate-y-px enabled:active:translate-y-0 enabled:active:scale-[0.98]',
        'focus-visible:outline-2 focus-visible:outline-offset-2 disabled:cursor-not-allowed disabled:opacity-50 disabled:shadow-none',
        variantClasses[variant],
        sizeClasses[size],
        className,
      )}
      {...props}
    />
  )
}
