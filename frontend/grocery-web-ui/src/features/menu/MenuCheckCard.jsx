import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { FOOD_TYPE_SHORT_LABELS, FOOD_TYPES } from '../../shared/utils/foodType'

/** @typedef {import('./api').MenuCategoryView} MenuCategoryView */

/**
 * A quick check of the whole menu, under the category list on wide screens: the veg / non-veg / egg split and the
 * dishes still missing a photo or a description (they help customers choose).
 * @param {{ categories: readonly MenuCategoryView[] }} props
 */
export function MenuCheckCard({ categories }) {
  const items = categories.flatMap((category) => category.items)
  const noPhoto = items.filter((item) => !item.imageUrl).length
  const noDescription = items.filter((item) => !item.description?.trim()).length
  const gaps = [
    { label: 'Without a photo', count: noPhoto },
    { label: 'Without a description', count: noDescription },
  ]

  return (
    <section
      aria-labelledby="menu-check-title"
      className="rounded-2xl border border-slate-200/70 bg-white p-3.5 shadow-sm shadow-slate-900/[0.03]"
    >
      <h2 id="menu-check-title" className="px-0.5 text-[11px] font-semibold tracking-wider text-slate-500 uppercase">
        Menu check
      </h2>
      <dl className="mt-2.5 grid grid-cols-3 gap-1.5">
        {FOOD_TYPES.map((foodType) => (
          <div
            key={foodType}
            className="flex flex-col items-center gap-1 rounded-xl bg-slate-50 px-1 py-2 ring-1 ring-slate-100"
          >
            {/* The mark sits above its short label ("Non-veg" fits a narrow cell that way). */}
            <dt className="flex flex-col items-center gap-1 text-[11px] font-medium text-slate-600">
              <span aria-hidden="true">
                <FoodTypeMark foodType={foodType} />
              </span>
              {FOOD_TYPE_SHORT_LABELS[foodType]}
            </dt>
            <dd className="text-lg leading-none font-bold text-slate-900 tabular-nums">
              {items.filter((item) => item.foodType === foodType).length}
            </dd>
          </div>
        ))}
      </dl>
      <ul className="mt-3 space-y-1.5 text-sm">
        {gaps.map((gap) => (
          <li key={gap.label} className="flex items-center gap-2">
            <Icon name={gap.count > 0 ? 'warning' : 'checkCircle'} className="size-4" />
            <span className="min-w-0 flex-1 text-slate-600">{gap.label}</span>
            <span className={cn('font-semibold tabular-nums', gap.count > 0 ? 'text-amber-700' : 'text-emerald-700')}>
              {gap.count}
            </span>
          </li>
        ))}
      </ul>
      {(noPhoto > 0 || noDescription > 0) && (
        <p className="mt-3 border-t border-slate-100 pt-2.5 text-xs leading-relaxed text-slate-500">
          Photos and short descriptions help customers choose. Add them with the pencil on a dish.
        </p>
      )}
    </section>
  )
}
