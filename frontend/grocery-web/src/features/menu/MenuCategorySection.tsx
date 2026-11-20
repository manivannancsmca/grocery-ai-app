import type { ReactNode } from 'react'
import { Button } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import type { MenuCategoryView } from './api'
import { MenuIconButton } from './MenuIconButton'
import { countSoldOut, dishesLabel, menuIconFor } from './menuView'

interface MenuCategorySectionProps {
  category: MenuCategoryView
  /** How many of its dishes the search / availability filter shows (all of them without a filter). */
  shownCount: number
  readOnly: boolean
  /** The menu has the maximum number of dishes. */
  addDisabled: boolean
  onAddDish: () => void
  onEdit: () => void
  onDelete: () => void
  /** The dish cards (`<li>`s). */
  children: ReactNode
}

/** A category panel: picture, name, counts and actions on top, its dishes as a grid of cards below. */
export function MenuCategorySection({ category, shownCount, readOnly, addDisabled, onAddDish, onEdit, onDelete, children }: MenuCategorySectionProps) {
  const headingId = `menu-category-${category.id}`
  const total = category.items.length
  const soldOut = countSoldOut(category.items)

  return (
    <section aria-labelledby={headingId} className="animate-fade-up rounded-2xl border border-slate-200/70 bg-white shadow-sm shadow-slate-900/[0.03]">
      <header className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-slate-100 px-3.5 py-3 sm:px-4">
        <span
          aria-hidden="true"
          className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-50 to-teal-50 ring-1 ring-emerald-100"
        >
          <Icon name={menuIconFor(category.name)} className="size-6" />
        </span>
        <div className="min-w-0 flex-1">
          <h2 id={headingId} className="truncate text-base font-semibold text-slate-900">
            {category.name}
          </h2>
          <p className="text-xs text-slate-500">
            {shownCount === total ? dishesLabel(total) : `${shownCount} of ${dishesLabel(total)}`}
            {soldOut > 0 && <span className="text-amber-700"> · {soldOut} sold out</span>}
          </p>
        </div>
        {!readOnly && (
          <div className="flex shrink-0 items-center gap-1">
            {/* Narrow screens show only the plus, so the category name keeps its room. */}
            <Button
              size="sm"
              variant="secondary"
              className="px-2.5"
              disabled={addDisabled}
              aria-label={`Add dish to ${category.name}`}
              title={`Add dish to ${category.name}`}
              onClick={onAddDish}
            >
              <Icon name="plus" className="size-4" />
              <span className="hidden @md:inline">Add dish</span>
            </Button>
            <MenuIconButton icon="pencil" label={`Edit category ${category.name}`} onClick={onEdit} />
            <MenuIconButton icon="trash" label={`Delete category ${category.name}`} tone="danger" onClick={onDelete} />
          </div>
        )}
        {category.description && <p className="basis-full text-sm text-slate-600">{category.description}</p>}
      </header>

      <div className="p-2.5 @xl:p-4">
        {total === 0 ? (
          <p className="rounded-xl border border-dashed border-slate-300 bg-slate-50/60 px-4 py-6 text-center text-sm text-slate-500">
            No dishes in this category yet.
          </p>
        ) : (
          <ul className="grid gap-3 @xl:grid-cols-2 @3xl:grid-cols-3" aria-label={`${category.name} dishes`}>
            {children}
          </ul>
        )}
      </div>
    </section>
  )
}
