import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { cn } from '../../shared/utils/cn'
import { FOOD_TYPE_SHORT_LABELS } from '../../shared/utils/foodType'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/utils/foodType').FoodType} FoodType */
/** @typedef {import('./api').CountFacet} CountFacet */
/** @typedef {import('./api').ProductSearchResponse} ProductSearchResponse */

/**
 * @typedef {object} FacetSelection
 * @property {string} [categoryId]
 * @property {string} [brandId]
 * @property {FoodType} [foodType]
 */

/**
 * @typedef {object} SearchFacetsProps
 * @property {ProductSearchResponse['facets'] | undefined} facets
 * @property {FacetSelection} selection
 * @property {string} [selectedCategoryName] Name of the selected category when it is not among the facets (e.g. a top-level category).
 * @property {(facet: keyof FacetSelection, value: string | null) => void} onSelect Picks a value, or `null` to clear that facet.
 * @property {boolean} [disabled]
 */

/** @param {{ selected: boolean, count?: number, onClick: () => void, disabled?: boolean, children: ReactNode }} props */
function FacetButton({ selected, count, onClick, disabled, children }) {
  return (
    <li>
      <button
        type="button"
        aria-pressed={selected}
        onClick={onClick}
        disabled={disabled}
        className={cn(
          'group flex min-h-9 w-full items-center justify-between gap-2 rounded-xl px-2 py-1.5 text-left text-sm transition-colors',
          'focus-visible:outline-2 focus-visible:outline-emerald-600 disabled:opacity-60',
          selected
            ? 'bg-emerald-50 font-semibold text-emerald-800'
            : 'text-slate-700 hover:bg-slate-50 hover:text-slate-900',
        )}
      >
        <span className="flex min-w-0 items-center gap-2.5">
          <span
            aria-hidden="true"
            className={cn(
              'flex size-4 shrink-0 items-center justify-center rounded-[5px] ring-1 transition-colors ring-inset',
              selected
                ? 'bg-emerald-600 text-white ring-emerald-600'
                : 'bg-white ring-slate-300 group-hover:ring-emerald-400',
            )}
          >
            {selected && (
              <svg className="size-3" viewBox="0 0 20 20" fill="currentColor">
                <path
                  fillRule="evenodd"
                  d="M16.7 5.3a1 1 0 0 1 0 1.4l-8 8a1 1 0 0 1-1.4 0l-4-4a1 1 0 1 1 1.4-1.4L8 12.6l7.3-7.3a1 1 0 0 1 1.4 0Z"
                  clipRule="evenodd"
                />
              </svg>
            )}
          </span>
          <span className="flex min-w-0 items-center gap-2 truncate">{children}</span>
        </span>
        {count !== undefined && (
          <span
            className={cn(
              'shrink-0 rounded-full px-2 py-px text-xs font-medium tabular-nums',
              selected ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-100 text-slate-500',
            )}
          >
            {count}
            <span className="sr-only"> products</span>
          </span>
        )}
      </button>
    </li>
  )
}

/** @param {{ title: string, children: ReactNode }} props */
function FacetGroup({ title, children }) {
  return (
    <section aria-label={title}>
      <h3 className="mb-2 px-1.5 text-xs font-semibold tracking-wider text-slate-500 uppercase">{title}</h3>
      <ul className="space-y-0.5">{children}</ul>
    </section>
  )
}

/**
 * @param {CountFacet[]} facets
 * @param {string | undefined} selectedId
 * @param {string | undefined} selectedName
 * @returns {Array<{ id: string, name: string, count?: number }>}
 */
function countFacets(facets, selectedId, selectedName) {
  if (!selectedId || facets.some((facet) => facet.id === selectedId)) return facets
  return [{ id: selectedId, name: selectedName ?? 'Selected' }, ...facets]
}

/**
 * Category, brand and food-type filters with result counts. Clicking a selected value clears it.
 * @param {SearchFacetsProps} props
 */
export function SearchFacets({ facets, selection, selectedCategoryName, onSelect, disabled }) {
  const categories = countFacets(facets?.categories ?? [], selection.categoryId, selectedCategoryName)
  const brands = countFacets(facets?.brands ?? [], selection.brandId, undefined)
  const foodTypes = facets?.foodTypes ?? []

  return (
    <div className="space-y-5">
      {categories.length > 0 && (
        <FacetGroup title="Category">
          {categories.map((facet) => {
            const selected = facet.id === selection.categoryId
            return (
              <FacetButton
                key={facet.id}
                selected={selected}
                count={facet.count}
                disabled={disabled}
                onClick={() => onSelect('categoryId', selected ? null : facet.id)}
              >
                {facet.name}
              </FacetButton>
            )
          })}
        </FacetGroup>
      )}
      {brands.length > 0 && (
        <FacetGroup title="Brand">
          {brands.map((facet) => {
            const selected = facet.id === selection.brandId
            return (
              <FacetButton
                key={facet.id}
                selected={selected}
                count={facet.count}
                disabled={disabled}
                onClick={() => onSelect('brandId', selected ? null : facet.id)}
              >
                {facet.name}
              </FacetButton>
            )
          })}
        </FacetGroup>
      )}
      {(foodTypes.length > 0 || selection.foodType) && (
        <FacetGroup title="Food type">
          {(foodTypes.length > 0 ? foodTypes : [{ value: selection.foodType, count: undefined }]).map((facet) => {
            const selected = facet.value === selection.foodType
            return (
              <FacetButton
                key={facet.value}
                selected={selected}
                count={facet.count}
                disabled={disabled}
                onClick={() => onSelect('foodType', selected ? null : facet.value)}
              >
                <FoodTypeMark foodType={facet.value} />
                {FOOD_TYPE_SHORT_LABELS[facet.value]}
              </FacetButton>
            )
          })}
        </FacetGroup>
      )}
    </div>
  )
}
