import { cn } from '../../../shared/utils/cn'
import { type HistoryFilters, OUTCOME_FILTERS, PERIOD_FILTERS } from './historyFilters'

interface ChipGroupProps<T extends string> {
  label: string
  options: readonly { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}

/** A row of toggle chips, one pressed (same look as the dashboard's period chips); wraps on a phone. */
function ChipGroup<T extends string>({ label, options, value, onChange }: ChipGroupProps<T>) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-2">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={value === option.value}
          onClick={() => value !== option.value && onChange(option.value)}
          className={cn(
            'inline-flex h-9 items-center rounded-full px-3.5 text-sm font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
            value === option.value
              ? 'bg-gradient-to-r from-emerald-500 to-teal-500 text-white shadow-sm shadow-emerald-600/25'
              : 'bg-white text-slate-700 ring-1 ring-slate-200 ring-inset hover:text-emerald-800 hover:ring-emerald-300',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

interface HistoryFilterBarProps {
  filters: HistoryFilters
  onChange: (change: Partial<HistoryFilters>) => void
}

/** The trip list's filters: how the trip ended, and when (India-time days up to today). */
export function HistoryFilterBar({ filters, onChange }: HistoryFilterBarProps) {
  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-slate-200/80 bg-gradient-to-br from-white via-white to-emerald-50/50 p-3 shadow-sm shadow-slate-900/[0.04] lg:flex-row lg:items-center lg:justify-between">
      <ChipGroup label="Outcome" options={OUTCOME_FILTERS} value={filters.outcome} onChange={(outcome) => onChange({ outcome })} />
      <ChipGroup label="Period" options={PERIOD_FILTERS} value={filters.period} onChange={(period) => onChange({ period })} />
    </div>
  )
}
