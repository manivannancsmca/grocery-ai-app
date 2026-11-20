import type { IconName } from '../../icons'
import { Skeleton } from './Skeleton'
import { STAT_ACCENTS, type StatAccent } from './statAccents'
import { StatCard } from './StatCard'

export interface SummaryItem {
  label: string
  /** `undefined` while loading or when the summary could not be loaded. */
  value: number | undefined
  /** Colours the number when it is above zero (e.g. out-of-stock counts). */
  tone?: 'default' | 'amber' | 'red'
  icon?: IconName
}

interface SummaryStripProps {
  /** Accessible name of the strip, e.g. "Stock summary". */
  label: string
  items: readonly SummaryItem[]
  loading?: boolean
}

const toneClasses = { default: 'text-slate-900', amber: 'text-amber-700', red: 'text-red-700' } as const
const toneAccents: Record<'amber' | 'red', StatAccent> = { amber: 'amber', red: 'rose' }

/** A row of headline numbers above a list (counts from a `/summary` endpoint). */
export function SummaryStrip({ label, items, loading = false }: SummaryStripProps) {
  return (
    <section aria-label={label} className="animate-fade-up motion-reduce:animate-none">
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {items.map((item, index) => {
          const alerting = item.value !== undefined && item.value > 0 && item.tone !== undefined && item.tone !== 'default'
          return (
            <StatCard
              key={item.label}
              label={item.label}
              icon={item.icon}
              accent={item.tone && item.tone !== 'default' ? toneAccents[item.tone] : (STAT_ACCENTS[index % STAT_ACCENTS.length] ?? 'emerald')}
              valueClassName={alerting ? toneClasses[item.tone ?? 'default'] : undefined}
              value={item.value !== undefined ? item.value.toLocaleString('en-IN') : loading ? <Skeleton className="my-1 h-6 w-10" /> : '—'}
            />
          )
        })}
      </dl>
    </section>
  )
}
