import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { countSoldOut, menuIconFor } from './menuView'

/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').MenuCategoryView} MenuCategoryView */

/**
 * @typedef {object} MenuCategoryNavProps
 * @property {readonly MenuCategoryView[]} categories
 * @property {string | null} chosenId The category shown on its own; null = every category.
 * @property {(categoryId: string | null) => void} onChoose
 */

/**
 * @typedef {object} NavOption
 * @property {string | null} id
 * @property {string} name
 * @property {IconName} icon
 * @property {number} count
 * @property {number} soldOut
 */

/**
 * Category filter beside the dishes: a list on wide screens, a row of scrollable chips on narrow ones. Each
 * entry shows its dish count, and an amber dot when some of its dishes are sold out.
 * @param {MenuCategoryNavProps} props
 */
export function MenuCategoryNav({ categories, chosenId, onChoose }) {
  const allItems = categories.flatMap((category) => category.items)
  const options = [
    { id: null, name: 'All dishes', icon: 'grid', count: allItems.length, soldOut: countSoldOut(allItems) },
    ...categories.map((category) => ({
      id: category.id,
      name: category.name,
      icon: menuIconFor(category.name),
      count: category.items.length,
      soldOut: countSoldOut(category.items),
    })),
  ]

  return (
    <nav
      aria-label="Menu categories"
      className="min-w-0 @4xl:rounded-2xl @4xl:border @4xl:border-slate-200/70 @4xl:bg-white @4xl:p-2.5 @4xl:shadow-sm @4xl:shadow-slate-900/[0.03]"
    >
      <p className="mb-1.5 hidden items-center gap-2 px-2 pt-1 text-[11px] font-semibold tracking-wider text-slate-500 uppercase @4xl:flex">
        Categories
        <span className="rounded-full bg-slate-100 px-1.5 text-[11px] text-slate-500 tabular-nums">
          {categories.length}
        </span>
      </p>
      <ul className="flex gap-2 overflow-x-auto pb-1 [scrollbar-width:thin] @4xl:max-h-[50vh] @4xl:flex-col @4xl:gap-0.5 @4xl:overflow-y-auto @4xl:pb-0">
        {options.map((option) => {
          const active = option.id === chosenId
          return (
            <li key={option.id ?? 'all'} className="shrink-0">
              <button
                type="button"
                aria-pressed={active}
                title={option.soldOut > 0 ? `${option.name}: ${option.soldOut} sold out` : option.name}
                onClick={() => onChoose(option.id)}
                className={cn(
                  'flex h-10 w-full items-center gap-2.5 rounded-full pr-2.5 pl-1.5 text-sm font-medium whitespace-nowrap ring-1 transition-all duration-200 ring-inset @4xl:rounded-xl @4xl:pr-2',
                  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
                  active
                    ? 'bg-emerald-600 text-white shadow-sm shadow-emerald-900/20 ring-emerald-600 @4xl:bg-emerald-50 @4xl:text-emerald-800 @4xl:shadow-none @4xl:ring-emerald-200'
                    : 'bg-white text-slate-700 ring-slate-200 hover:bg-slate-50 @4xl:bg-transparent @4xl:ring-transparent @4xl:hover:bg-slate-100',
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    'flex size-7 shrink-0 items-center justify-center rounded-full @4xl:rounded-lg',
                    active ? 'bg-white @4xl:ring-1 @4xl:ring-emerald-200' : 'bg-slate-50 ring-1 ring-slate-200/70',
                  )}
                >
                  <Icon name={option.icon} className="size-4.5" />
                </span>
                <span className="min-w-0 flex-1 truncate text-left">{option.name}</span>
                {option.soldOut > 0 && (
                  <span aria-hidden="true" className="size-1.5 shrink-0 rounded-full bg-amber-500" />
                )}
                <span
                  className={cn(
                    'min-w-6 rounded-full px-1.5 text-center text-xs font-semibold tabular-nums',
                    active
                      ? 'bg-white/20 text-white @4xl:bg-emerald-100 @4xl:text-emerald-700'
                      : 'bg-slate-100 text-slate-500',
                  )}
                >
                  {option.count}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}
