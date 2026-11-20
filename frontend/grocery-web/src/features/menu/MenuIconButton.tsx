import type { ComponentProps } from 'react'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'

interface MenuIconButtonProps extends Omit<ComponentProps<'button'>, 'children'> {
  icon: IconName
  /** Accessible name, also shown as the tooltip. */
  label: string
  tone?: 'default' | 'danger'
}

/** A small square button with only a picture (edit, delete) for dish cards and category headers. */
export function MenuIconButton({ icon, label, tone = 'default', className, ...props }: MenuIconButtonProps) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      className={cn(
        'inline-flex size-9 shrink-0 items-center justify-center rounded-lg text-slate-500 transition-all duration-200 sm:size-8',
        'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-emerald-600',
        'enabled:hover:-translate-y-px disabled:cursor-not-allowed disabled:opacity-40',
        tone === 'danger' ? 'enabled:hover:bg-red-50 enabled:hover:ring-red-200' : 'enabled:hover:bg-emerald-50 enabled:hover:ring-emerald-200',
        'ring-1 ring-transparent ring-inset',
        className,
      )}
      {...props}
    >
      <Icon name={icon} className="size-4.5" />
    </button>
  )
}
