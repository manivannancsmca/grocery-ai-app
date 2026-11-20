import { cn } from '../../../shared/utils/cn'
import { OUTCOME_FILTERS, PERIOD_FILTERS } from './historyFilters'

/** @typedef {import('./historyFilters').HistoryFilters} HistoryFilters */

/**
 * @template {string} T
 * @typedef {object} ChipGroupProps
 * @property {string} label
 * @property {readonly { value: T, label: string }[]} options
 * @property {T} value
 * @property {(value: T) => void} onChange
 */

/**
 * A row of toggle chips, one pressed (same look as the dashboard's period chips); wraps on a phone.
 * @template {string} T
 * @param {ChipGroupProps<T>} props
 */
function ChipGroup({ label, options, value, onChange }) {
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

/**
 * @typedef {object} HistoryFilterBarProps
 * @property {HistoryFilters} filters
 * @property {(change: Partial<HistoryFilters>) => void} onChange
 */

/**
 * The trip list's filters: how the trip ended, and when (India-time days up to today).
 * @param {HistoryFilterBarProps} props
 */
export function HistoryFilterBar({ filters, onChange }) {
  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-slate-200/80 bg-gradient-to-br from-white via-white to-emerald-50/50 p-3 shadow-sm shadow-slate-900/[0.04] lg:flex-row lg:items-center lg:justify-between">
      <ChipGroup
        label="Outcome"
        options={OUTCOME_FILTERS}
        value={filters.outcome}
        onChange={(outcome) => onChange({ outcome })}
      />
      <ChipGroup
        label="Period"
        options={PERIOD_FILTERS}
        value={filters.period}
        onChange={(period) => onChange({ period })}
      />
    </div>
  )
}
