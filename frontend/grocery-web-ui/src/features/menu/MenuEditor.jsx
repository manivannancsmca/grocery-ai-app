import { useRef, useState } from 'react'
import { useToast } from '../../shared/components/toast/useToast'
import { Button, ConfirmDialog, EmptyState } from '../../shared/components/ui'
import { MENU_LIMITS } from './api'
import { menuErrorMessage } from './errors'
import { useDeleteMenuCategory, useDeleteMenuItem, useSetItemAvailability } from './hooks'
import { MenuCategoryNav } from './MenuCategoryNav'
import { MenuCategorySection } from './MenuCategorySection'
import { MenuCheckCard } from './MenuCheckCard'
import { MenuCategoryDialog, MenuItemDialog } from './MenuDialogs'
import { MenuDishCard } from './MenuDishCard'
import { MenuToolbar } from './MenuToolbar'
import { countSoldOut, dishesLabel, dishMatches } from './menuView'

/** @typedef {import('./api').MenuCategoryView} MenuCategoryView */
/** @typedef {import('./api').MenuItemView} MenuItemView */
/** @typedef {import('./api').MenuView} MenuView */

/**
 * @typedef {(
 *   | { type: 'category', category: MenuCategoryView | null }
 *   | { type: 'item', item: MenuItemView | null, categoryId: string }
 *   | { type: 'deleteCategory', category: MenuCategoryView }
 *   | { type: 'deleteItem', item: MenuItemView }
 *   | null
 * )} DialogState
 */

/**
 * @typedef {object} MenuEditorProps
 * @property {MenuView} menu
 * @property {boolean} readOnly Suspended restaurants see the menu without editing controls.
 */

/**
 * Owner menu editor. Wide screens: a sticky category list on the left, the dishes as a grid of cards on the right.
 * Narrow screens: the categories become a row of chips above the dishes. Search and the availability filter narrow the
 * cards; add / edit / delete open dialogs, and each card has the sold-out switch.
 * @param {MenuEditorProps} props
 */
export function MenuEditor({ menu, readOnly }) {
  const toast = useToast()
  const [dialog, setDialog] = useState(null)
  const [deleteError, setDeleteError] = useState(null)
  const [chosenCategoryId, setChosenCategoryId] = useState(null)
  const [query, setQuery] = useState('')
  const [availability, setAvailability] = useState('ALL')
  const dishesRef = useRef(null)
  const deleteCategory = useDeleteMenuCategory()
  const deleteItem = useDeleteMenuItem()
  const setItemAvailability = useSetItemAvailability()

  const categories = menu.categories
  const lastSortOrder = categories.reduce((max, category) => Math.max(max, category.sortOrder), -10)
  const nextSortOrder = Math.min(lastSortOrder + 10, 9999)
  const categoryLimitReached = categories.length >= MENU_LIMITS.categories
  const itemLimitReached = menu.itemCount >= MENU_LIMITS.items

  // A chosen category that has been deleted meanwhile falls back to "All dishes".
  const chosenCategory = categories.find((category) => category.id === chosenCategoryId) ?? null
  const filtering = query.trim() !== '' || availability !== 'ALL'
  const allItems = categories.flatMap((category) => category.items)
  const soldOutCount = countSoldOut(allItems)
  // While searching or filtering, categories without a matching dish are left out.
  const shown = (chosenCategory ? [chosenCategory] : categories)
    .map((category) => ({ category, items: category.items.filter((item) => dishMatches(item, query, availability)) }))
    .filter(({ items }) => !filtering || items.length > 0)
  const shownDishCount = shown.reduce((sum, { items }) => sum + items.length, 0)

  function closeDialog() {
    setDialog(null)
    setDeleteError(null)
  }

  function clearFilters() {
    setQuery('')
    setAvailability('ALL')
    setChosenCategoryId(null)
  }

  /** Show one category (or all); scroll back up to the dishes if their top is hidden under the top bar. */
  function chooseCategory(categoryId) {
    setChosenCategoryId(categoryId)
    const dishes = dishesRef.current
    if (dishes && dishes.getBoundingClientRect().top < 64)
      dishes.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  }

  function toggleAvailability(item, available) {
    setItemAvailability.mutate(
      { id: item.id, available },
      {
        onSuccess: (saved) =>
          toast.success(saved.available ? `${saved.name} is available again.` : `${saved.name} is marked sold out.`),
        onError: (error) => toast.error(`Could not update ${item.name}: ${menuErrorMessage(error)}`),
      },
    )
  }

  function renderDish(item) {
    return (
      <MenuDishCard
        key={item.id}
        item={item}
        readOnly={readOnly}
        switching={setItemAvailability.isPending && setItemAvailability.variables.id === item.id}
        onAvailabilityChange={(available) => toggleAvailability(item, available)}
        onEdit={() => setDialog({ type: 'item', item, categoryId: item.categoryId })}
        onDelete={() => setDialog({ type: 'deleteItem', item })}
      />
    )
  }

  return (
    <div className="@container">
      {categories.length === 0 ? (
        <EmptyState
          size="lg"
          title="Your menu is empty"
          description="Start with a category such as Starters or Biryani, then add dishes to it."
          action={
            readOnly ? undefined : (
              <Button onClick={() => setDialog({ type: 'category', category: null })}>Add your first category</Button>
            )
          }
        />
      ) : (
        <div className="grid gap-4 @4xl:grid-cols-[15rem_minmax(0,1fr)] @4xl:gap-5">
          {/* Wide screens: the categories and the menu check stay in view while the dishes scroll. */}
          <div className="flex min-w-0 flex-col gap-4 @4xl:sticky @4xl:top-20 @4xl:self-start">
            <MenuCategoryNav categories={categories} chosenId={chosenCategory?.id ?? null} onChoose={chooseCategory} />
            <div className="hidden @4xl:block">
              <MenuCheckCard categories={categories} />
            </div>
          </div>

          <div ref={dishesRef} className="@container min-w-0 scroll-mt-20 space-y-4">
            <MenuToolbar
              query={query}
              onQueryChange={setQuery}
              availability={availability}
              onAvailabilityChange={setAvailability}
              counts={{ ALL: allItems.length, AVAILABLE: allItems.length - soldOutCount, SOLD_OUT: soldOutCount }}
              readOnly={readOnly}
              categoryLimitReached={categoryLimitReached}
              itemLimitReached={itemLimitReached}
              onAddCategory={() => setDialog({ type: 'category', category: null })}
              onAddDish={() =>
                setDialog({ type: 'item', item: null, categoryId: chosenCategory?.id ?? categories[0]?.id ?? '' })
              }
            />

            <p className="sr-only" aria-live="polite">
              {filtering ? `${shownDishCount} of ${dishesLabel(allItems.length)} shown` : ''}
            </p>

            {shown.length === 0 ? (
              <EmptyState
                title="No dishes match"
                description="Try another name, or show all dishes again."
                action={
                  <Button variant="secondary" onClick={clearFilters}>
                    Clear filters
                  </Button>
                }
              />
            ) : (
              shown.map(({ category, items }) => (
                <MenuCategorySection
                  key={category.id}
                  category={category}
                  shownCount={items.length}
                  readOnly={readOnly}
                  addDisabled={itemLimitReached}
                  onAddDish={() => setDialog({ type: 'item', item: null, categoryId: category.id })}
                  onEdit={() => setDialog({ type: 'category', category })}
                  onDelete={() => setDialog({ type: 'deleteCategory', category })}
                >
                  {items.map(renderDish)}
                </MenuCategorySection>
              ))
            )}
          </div>
        </div>
      )}

      <MenuCategoryDialog
        open={dialog?.type === 'category'}
        category={dialog?.type === 'category' ? dialog.category : null}
        nextSortOrder={nextSortOrder}
        onClose={closeDialog}
      />
      <MenuItemDialog
        open={dialog?.type === 'item'}
        item={dialog?.type === 'item' ? dialog.item : null}
        categories={categories}
        defaultCategoryId={dialog?.type === 'item' ? dialog.categoryId : ''}
        onClose={closeDialog}
      />
      <ConfirmDialog
        open={dialog?.type === 'deleteCategory'}
        title={`Delete ${dialog?.type === 'deleteCategory' ? dialog.category.name : 'category'}?`}
        confirmLabel="Delete category"
        tone="danger"
        pending={deleteCategory.isPending}
        error={deleteError}
        onClose={closeDialog}
        onConfirm={() => {
          if (dialog?.type !== 'deleteCategory') return
          const { category } = dialog
          setDeleteError(null)
          deleteCategory.mutate(category.id, {
            onSuccess: () => {
              closeDialog()
              toast.success(`${category.name} was deleted.`)
            },
            onError: (error) => setDeleteError(menuErrorMessage(error)),
          })
        }}
      >
        <p>Only empty categories can be deleted. Customers stop seeing it right away.</p>
      </ConfirmDialog>
      <ConfirmDialog
        open={dialog?.type === 'deleteItem'}
        title={`Delete ${dialog?.type === 'deleteItem' ? dialog.item.name : 'dish'}?`}
        confirmLabel="Delete dish"
        tone="danger"
        pending={deleteItem.isPending}
        error={deleteError}
        onClose={closeDialog}
        onConfirm={() => {
          if (dialog?.type !== 'deleteItem') return
          const { item } = dialog
          setDeleteError(null)
          deleteItem.mutate(item.id, {
            onSuccess: () => {
              closeDialog()
              toast.success(`${item.name} was deleted.`)
            },
            onError: (error) => setDeleteError(menuErrorMessage(error)),
          })
        }}
      >
        <p>To hide a dish for a while instead, switch it to sold out.</p>
      </ConfirmDialog>
    </div>
  )
}
