import { type ReactNode, useEffect, useId, useState } from 'react'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { Badge, Switch } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatPrice } from '../../shared/utils/format'
import { ProductImage } from '../catalog/ProductImage'
import type { PublicMenuCategory, PublicMenuItem } from './api'

/** One dish card: food-type mark, name, description, price (or "Sold out") and the add control. */
function DishCard({ item, action }: { item: PublicMenuItem; action?: ReactNode }) {
  return (
    <li
      className={cn(
        'flex gap-4 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm shadow-slate-900/[0.03] transition',
        item.available ? 'hover:border-emerald-200 hover:shadow-md' : 'bg-slate-50/80',
      )}
    >
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex items-start gap-2">
          <FoodTypeMark foodType={item.foodType} className="mt-0.5" />
          <h4 className={cn('text-sm leading-snug font-semibold sm:text-[0.95rem]', item.available ? 'text-slate-900' : 'text-slate-500')}>
            {item.name}
          </h4>
        </div>
        {item.description && <p className="mt-1.5 line-clamp-2 text-sm leading-relaxed text-slate-500">{item.description}</p>}
        <div className="mt-auto flex flex-wrap items-center justify-between gap-x-3 gap-y-2 pt-3">
          <p className="flex items-center gap-2">
            <span className={cn('text-base font-bold tabular-nums', item.available ? 'text-slate-900' : 'text-slate-400')}>
              {formatPrice(item.price)}
            </span>
            {!item.available && <Badge tone="red">Sold out</Badge>}
          </p>
          {!item.imageUrl && action && <div className="shrink-0">{action}</div>}
        </div>
      </div>
      {item.imageUrl && (
        <div className="flex w-24 shrink-0 flex-col items-center gap-2 sm:w-28">
          <ProductImage
            src={item.imageUrl}
            alt={item.name}
            className={cn('size-24 rounded-xl sm:size-28', !item.available && 'opacity-60 grayscale')}
            iconClassName="size-8"
          />
          {action}
        </div>
      )}
    </li>
  )
}

/**
 * The category whose section is nearest the top of the screen, for highlighting it in the navigation. Without
 * `IntersectionObserver` (tests, old browsers) nothing is highlighted until a category is chosen.
 */
function useVisibleSection(sectionIds: readonly string[]): string | null {
  const [visible, setVisible] = useState<string | null>(null)
  const key = sectionIds.join('|')

  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return
    const ids = key ? key.split('|') : []
    const shown = new Set<string>()
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) shown.add(entry.target.id)
          else shown.delete(entry.target.id)
        }
        const first = ids.find((id) => shown.has(id))
        if (first) setVisible(first)
      },
      // The band below the sticky header (and the phone category bar).
      { rootMargin: '-120px 0px -55% 0px' },
    )
    for (const id of ids) {
      const section = document.getElementById(id)
      if (section) observer.observe(section)
    }
    return () => observer.disconnect()
  }, [key])

  return visible
}

interface RestaurantMenuProps {
  menu: readonly PublicMenuCategory[]
  /** Pure-veg restaurants need no veg-only toggle. */
  pureVeg: boolean
  /** Control shown on each dish (Phase 5: "Add" / quantity stepper). */
  itemAction?: (item: PublicMenuItem) => ReactNode
}

/** Public menu: category navigation, a veg-only toggle and the dishes (sold-out ones stay listed). */
export function RestaurantMenu({ menu, pureVeg, itemAction }: RestaurantMenuProps) {
  const baseId = useId()
  const [vegOnly, setVegOnly] = useState(false)
  const [chosen, setChosen] = useState<string | null>(null)
  const sectionId = (categoryId: string) => `${baseId}-category-${categoryId}`

  const categories = menu
    .map((category) => ({
      ...category,
      items: vegOnly ? category.items.filter((item) => item.foodType === 'VEG') : category.items,
    }))
    .filter((category) => category.items.length > 0)
  const dishCount = categories.reduce((sum, category) => sum + category.items.length, 0)

  const visible = useVisibleSection(categories.map((category) => sectionId(category.id)))
  const activeId = visible ?? chosen

  function jumpTo(categoryId: string) {
    setChosen(sectionId(categoryId))
    const section = document.getElementById(sectionId(categoryId))
    section?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
    section?.focus({ preventScroll: true })
  }

  if (menu.length === 0) {
    return (
      <p className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-10 text-center text-sm text-slate-500">
        This restaurant has not published its menu yet.
      </p>
    )
  }

  return (
    <div className="grid gap-5 xl:grid-cols-[14rem_minmax(0,1fr)] xl:gap-8">
      <nav
        aria-label="Menu categories"
        className={cn(
          'sticky top-16 z-10 -mx-4 border-b border-slate-200/70 bg-white/90 px-4 py-2.5 backdrop-blur sm:-mx-6 sm:px-6',
          'xl:top-20 xl:mx-0 xl:self-start xl:rounded-2xl xl:border xl:border-slate-200/80 xl:bg-white xl:p-3 xl:shadow-sm',
        )}
      >
        <p className="mb-2 hidden items-center gap-2 px-2 text-xs font-semibold tracking-wider text-slate-500 uppercase xl:flex">
          <Icon name="menuBook" className="size-4" />
          Categories
        </p>
        <ul className="flex snap-x gap-2 overflow-x-auto [scrollbar-width:none] xl:flex-col xl:gap-0.5 xl:overflow-visible [&::-webkit-scrollbar]:hidden">
          {categories.map((category) => {
            const active = activeId === sectionId(category.id)
            return (
              <li key={category.id} className="shrink-0 snap-start">
                <button
                  type="button"
                  onClick={() => jumpTo(category.id)}
                  aria-current={active ? 'true' : undefined}
                  className={cn(
                    'flex h-9 w-full items-center justify-between gap-3 rounded-full px-3.5 text-sm font-medium ring-1 transition-colors ring-inset xl:h-10 xl:rounded-xl xl:ring-0',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
                    active
                      ? 'bg-emerald-600 text-white ring-emerald-600 xl:bg-emerald-50 xl:text-emerald-800'
                      : 'bg-white text-slate-700 ring-slate-200 hover:bg-slate-50 xl:bg-transparent xl:hover:bg-slate-100',
                  )}
                >
                  <span className="truncate">{category.name}</span>
                  <span
                    className={cn(
                      'rounded-full px-1.5 text-xs tabular-nums',
                      active ? 'bg-white/20 text-white xl:bg-emerald-100 xl:text-emerald-700' : 'bg-slate-100 text-slate-500',
                    )}
                  >
                    {category.items.length}
                  </span>
                </button>
              </li>
            )
          })}
        </ul>
      </nav>

      <div className="@container min-w-0">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">Menu</h2>
            <p className="text-sm text-slate-500">
              {dishCount} dish{dishCount === 1 ? '' : 'es'} · {categories.length} categor{categories.length === 1 ? 'y' : 'ies'}
            </p>
          </div>
          {!pureVeg && (
            <div className="rounded-full border border-green-200 bg-green-50/70 px-3 py-1.5">
              <Switch label="Veg only" checked={vegOnly} onChange={setVegOnly} size="sm" />
            </div>
          )}
        </div>
        {categories.length === 0 ? (
          <p className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-10 text-center text-sm text-slate-500">
            There are no vegetarian dishes on this menu.
          </p>
        ) : (
          <div className="space-y-8">
            {categories.map((category) => (
              <section
                key={category.id}
                id={sectionId(category.id)}
                tabIndex={-1}
                aria-labelledby={`${sectionId(category.id)}-title`}
                className="scroll-mt-32 focus:outline-none xl:scroll-mt-24"
              >
                <div className="mb-3 flex items-baseline gap-2 border-b border-slate-200/70 pb-2">
                  <h3 id={`${sectionId(category.id)}-title`} className="text-base font-bold text-slate-900">
                    {category.name}
                  </h3>
                  <span aria-hidden="true" className="text-sm text-slate-400 tabular-nums">
                    ({category.items.length})
                  </span>
                </div>
                {category.description && <p className="-mt-1 mb-3 text-sm text-slate-500">{category.description}</p>}
                <ul className="grid gap-3 @lg:grid-cols-2">
                  {category.items.map((item) => (
                    <DishCard key={item.id} item={item} action={itemAction?.(item)} />
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
