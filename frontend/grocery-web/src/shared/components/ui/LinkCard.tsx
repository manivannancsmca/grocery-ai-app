import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Icon, type IconName } from '../../icons'
import { cn } from '../../utils/cn'
import { ACCENT_CLASSES, type StatAccent } from './statAccents'

interface LinkCardProps {
  to: string
  title: string
  icon: IconName
  accent?: StatAccent
  /** A big number under the title (a count waiting for review). */
  value?: ReactNode
  description?: string
  /** The call to action at the bottom, e.g. "Open reports". */
  cta: string
  className?: string
}

/** A whole-card link on a dashboard: picture, title, optional count or text, and a call to action that nudges on hover. */
export function LinkCard({ to, title, icon, accent = 'emerald', value, description, cta, className }: LinkCardProps) {
  const colours = ACCENT_CLASSES[accent]
  return (
    <Link
      to={to}
      className={cn(
        'group/card animate-fade-up relative isolate flex flex-col overflow-hidden rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm shadow-slate-900/[0.04]',
        'transition-all duration-300 hover:-translate-y-1 hover:border-emerald-200 hover:shadow-xl hover:shadow-slate-900/[0.08]',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
        className,
      )}
    >
      <span aria-hidden="true" className={cn('absolute inset-x-0 top-0 h-1 bg-gradient-to-r', colours.bar)} />
      <span
        aria-hidden="true"
        className={cn('absolute -top-10 -right-10 -z-10 size-32 rounded-full blur-3xl transition-transform duration-500 group-hover/card:scale-125', colours.glow)}
      />
      <span className="flex items-start gap-3">
        <span
          aria-hidden="true"
          className={cn(
            'flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br ring-1 transition-transform duration-300 group-hover/card:scale-110 group-hover/card:-rotate-6',
            colours.tile,
          )}
        >
          <Icon name={icon} className="size-5" />
        </span>
        <span className="min-w-0 pt-0.5 text-sm font-semibold text-slate-900">{title}</span>
      </span>
      {value !== undefined && <span className="mt-3 block text-3xl font-bold tracking-tight text-slate-900">{value}</span>}
      {description && <span className="mt-2 block text-sm text-slate-500">{description}</span>}
      <span className="mt-auto flex items-center gap-1 pt-3 text-sm font-semibold text-emerald-700 group-hover/card:text-emerald-900">
        {cta}
        <span aria-hidden="true" className="transition-transform duration-200 group-hover/card:translate-x-1">
          →
        </span>
      </span>
    </Link>
  )
}
