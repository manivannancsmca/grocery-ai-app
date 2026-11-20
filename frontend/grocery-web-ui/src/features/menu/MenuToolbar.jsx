import { Button, Input } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { MENU_LIMITS } from './api'

/** @typedef {import('./menuView').AvailabilityFilter} AvailabilityFilter */

/**
 * @typedef {object} MenuToolbarProps
 * @property {string} query
 * @property {(query: string) => void} onQueryChange
 * @property {AvailabilityFilter} availability
 * @property {(availability: AvailabilityFilter) => void} onAvailabilityChange
 * @property {Record<AvailabilityFilter, number>} counts Dishes per availability filter (the whole menu).
 * @property {boolean} readOnly Suspended restaurants get no add buttons.
 * @property {boolean} categoryLimitReached
 * @property {boolean} itemLimitReached
 * @property {() => void} onAddCategory
 * @property {() => void} onAddDish
 */

/** @type {ReadonlyArray<{ id: AvailabilityFilter, label: string }>} */
const FILTERS = [
  { id: 'ALL', label: 'All' },
  { id: 'AVAILABLE', label: 'Available' },
  { id: 'SOLD_OUT', label: 'Sold out' },
]

/**
 * Above the dishes: search, the all / available / sold-out filter, and the add buttons.
 * @param {MenuToolbarProps} props
 */
export function MenuToolbar({
  query,
  onQueryChange,
  availability,
  onAvailabilityChange,
  counts,
  readOnly,
  categoryLimitReached,
  itemLimitReached,
  onAddCategory,
  onAddDish,
}) {
  return (
    <div className="flex flex-wrap items-center gap-2.5 rounded-2xl border border-slate-200/70 bg-white p-2.5 shadow-sm shadow-slate-900/[0.03]">
      <label className="relative block min-w-[12rem] flex-1 @3xl:max-w-xs">
        <span className="sr-only">Search dishes</span>
        <Icon
          name="search"
          className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-slate-400"
        />
        <Input
          type="search"
          value={query}
          placeholder="Search dishes"
          className="pl-9"
          onChange={(event) => onQueryChange(event.target.value)}
        />
      </label>

      <div
        role="group"
        aria-label="Show dishes"
        className="flex w-full rounded-xl bg-slate-100/80 p-1 ring-1 ring-slate-200/60 ring-inset @lg:w-auto"
      >
        {FILTERS.map((filter) => {
          const active = availability === filter.id
          return (
            <button
              key={filter.id}
              type="button"
              aria-pressed={active}
              onClick={() => onAvailabilityChange(filter.id)}
              className={cn(
                'inline-flex h-8 flex-1 items-center justify-center gap-1.5 rounded-lg px-2.5 text-sm font-semibold whitespace-nowrap transition-all duration-200 @lg:flex-none',
                'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-emerald-600',
                active
                  ? 'bg-white text-emerald-700 shadow-sm ring-1 ring-slate-200/80'
                  : 'text-slate-500 hover:bg-white/60 hover:text-slate-800',
              )}
            >
              {filter.label}
              <span
                aria-hidden="true"
                className={cn(
                  'rounded-full px-1.5 py-px text-[11px] font-bold tabular-nums',
                  filter.id === 'SOLD_OUT' && counts.SOLD_OUT > 0
                    ? 'bg-amber-100 text-amber-800'
                    : 'bg-slate-200/70 text-slate-600',
                )}
              >
                {counts[filter.id]}
              </span>
            </button>
          )
        })}
      </div>

      {!readOnly && (
        <div className="flex w-full gap-2 @lg:ml-auto @lg:w-auto">
          <Button
            variant="secondary"
            className="flex-1 @lg:flex-none"
            disabled={categoryLimitReached}
            title={categoryLimitReached ? `A menu can have up to ${MENU_LIMITS.categories} categories.` : undefined}
            onClick={onAddCategory}
          >
            <Icon name="plus" className="size-4" />
            Add category
          </Button>
          <Button
            className="flex-1 @lg:flex-none"
            disabled={itemLimitReached}
            title={itemLimitReached ? `A menu can have up to ${MENU_LIMITS.items} dishes.` : undefined}
            onClick={onAddDish}
          >
            <Icon name="plus" className="size-4" />
            Add dish
          </Button>
        </div>
      )}
    </div>
  )
}
