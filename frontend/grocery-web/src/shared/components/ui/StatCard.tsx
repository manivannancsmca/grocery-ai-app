import type { ReactNode } from 'react'
import { Icon, type IconName } from '../../icons'
import { cn } from '../../utils/cn'
import { ACCENT_CLASSES, type StatAccent } from './statAccents'

interface StatCardProps {
  label: string
  value: ReactNode
  icon?: IconName
  accent?: StatAccent
  /** A line under the value (a comparison, a unit, a note). */
  hint?: ReactNode
  /** Colour of the value (e.g. red for failures above zero). */
  valueClassName?: string
  className?: string
}

/**
 * A headline number on a white tile: colour bar on top, soft glow, optional picture. The tile is a `<div>` holding a
 * `<dt>` (label) and `<dd>` (value), so tiles must be direct children of a `<dl>`.
 */
export function StatCard({ label, value, icon, accent = 'emerald', hint, valueClassName, className }: StatCardProps) {
  const colours = ACCENT_CLASSES[accent]
  return (
    <div
      className={cn(
        'group/stat relative isolate grid content-start overflow-hidden rounded-2xl border border-slate-200/70 bg-white px-4 py-3.5 shadow-sm shadow-slate-900/[0.04]',
        'transition-all duration-300 hover:-translate-y-0.5 hover:shadow-lg hover:shadow-slate-900/[0.07]',
        icon ? 'grid-cols-[auto_minmax(0,1fr)]' : 'grid-cols-1',
        className,
      )}
    >
      <span aria-hidden="true" className={cn('absolute inset-x-0 top-0 h-1 bg-gradient-to-r', colours.bar)} />
      <span
        aria-hidden="true"
        className={cn('absolute -top-8 -right-8 -z-10 size-24 rounded-full blur-2xl transition-transform duration-500 group-hover/stat:scale-125', colours.glow)}
      />
      {icon && (
        <span
          aria-hidden="true"
          className={cn(
            'row-span-2 mt-0.5 mr-3 flex size-10 items-center justify-center rounded-xl bg-gradient-to-br ring-1 transition-transform duration-300 group-hover/stat:scale-110 group-hover/stat:-rotate-6',
            colours.tile,
          )}
        >
          <Icon name={icon} className="size-5" />
        </span>
      )}
      {/* The term (label) comes first in the markup but is shown under the value. */}
      <dt className={cn('row-start-2 min-w-0 text-xs font-medium text-slate-500', icon ? 'col-start-2' : 'col-start-1')}>{label}</dt>
      <dd className={cn('row-start-1 min-w-0 text-xl font-bold tracking-tight break-words sm:text-2xl', icon ? 'col-start-2' : 'col-start-1', valueClassName ?? 'text-slate-900')}>
        {value}
      </dd>
      {hint && <dd className={cn('row-start-3 mt-1 min-w-0 text-xs text-slate-400', icon ? 'col-start-2' : 'col-start-1')}>{hint}</dd>}
    </div>
  )
}
