import type { ReactNode } from 'react'
import { cn } from '../../utils/cn'

export interface DescriptionItem {
  label: string
  value: ReactNode
  /** Span both columns (long values such as addresses). */
  wide?: boolean
}

export function DescriptionList({ items, className }: { items: readonly DescriptionItem[]; className?: string }) {
  return (
    <dl className={cn('grid gap-3 sm:grid-cols-2', className)}>
      {items.map((item) => (
        <div
          key={item.label}
          className={cn(
            'min-w-0 rounded-xl bg-slate-50/70 px-3.5 py-2.5 ring-1 ring-slate-100 transition-colors duration-200 ring-inset hover:bg-emerald-50/40 hover:ring-emerald-100',
            item.wide && 'sm:col-span-2',
          )}
        >
          <dt className="text-xs font-medium tracking-wide text-slate-500 uppercase">{item.label}</dt>
          <dd className="mt-1 text-sm break-words text-slate-900">
            {item.value === null || item.value === undefined || item.value === '' ? (
              <span className="text-slate-400">—</span>
            ) : (
              item.value
            )}
          </dd>
        </div>
      ))}
    </dl>
  )
}
