import { useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { ProductCartControl } from '../../../features/cart/CartItemControl'
import { MAX_SEARCH_RESULTS, PRODUCT_SORT_LABELS, PRODUCT_SORTS, SEARCH_PAGE_SIZE } from '../../../features/catalog/api'
import { PRODUCT_GRID } from '../../../features/catalog/catalogLayout'
import { findNode } from '../../../features/catalog/categoryTree'
import { CategoryIcon } from '../../../features/catalog/categoryVisuals'
import { useCategoryTree, useProductSearch } from '../../../features/catalog/hooks'
import { PriceRangeFilter } from '../../../features/catalog/PriceRangeFilter'
import { priceRangeFromParams, priceRangeLabel, priceRangeToParams } from '../../../features/catalog/priceRange'
import { ProductTile, ProductTileSkeleton } from '../../../features/catalog/ProductTile'
import { SearchBox } from '../../../features/catalog/SearchBox'
import { SearchFacets } from '../../../features/catalog/SearchFacets'
import { deliveryPlaceText, useDeliveryLocation } from '../../../features/stores/deliveryLocation'
import { useStoreScope } from '../../../features/stores/hooks'
import { StoreScopeBar } from '../../../features/stores/StoreScopeBar'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { Button, EmptyState, ErrorState, Pagination, Select, Switch } from '../../../shared/components/ui'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { SearchIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'
import { FOOD_TYPE_SHORT_LABELS, isFoodType } from '../../../shared/utils/foodType'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../../features/catalog/api').ProductSort} ProductSort */
/** @typedef {import('../../../shared/hooks/useSearchParamsUpdater').ParamChanges} ParamChanges */

/** Small group label inside the filter panel (shared look with the facets and the price filter). */
const FILTER_HEADING = 'mb-2.5 text-xs font-semibold tracking-wider text-slate-500 uppercase'

/**
 * @param {string | null} value
 * @returns {value is ProductSort}
 */
function isSort(value) {
  return PRODUCT_SORTS.includes(value ?? '')
}

const FILTER_KEYS = ['categoryId', 'brandId', 'foodType', 'vendorId', 'inStock', 'minPrice', 'maxPrice']

/**
 * @typedef {object} FilterChip
 * @property {string} key
 * @property {string} label
 * @property {ParamChanges} clear Query-string keys removed by the chip.
 */

/**
 * A category shortcut on the dark search band.
 * @param {{ selected: boolean, onClick: () => void, icon: ReactNode, children: string }} props
 */
function CategoryShortcut({ selected, onClick, icon, children }) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        'inline-flex h-9 shrink-0 items-center gap-1.5 rounded-full pr-3.5 pl-1.5 text-sm font-medium whitespace-nowrap ring-1 transition ring-inset',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white',
        selected
          ? 'bg-white text-emerald-900 shadow-sm ring-white'
          : 'bg-white/10 text-white ring-white/25 hover:bg-white/20',
      )}
    >
      <span aria-hidden="true" className="flex size-6 items-center justify-center rounded-full bg-white">
        {icon}
      </span>
      {children}
    </button>
  )
}

/**
 * `/search` — product search with facets. Everything (text, filters, sort, page, "all stores") lives in the query
 * string, so results can be bookmarked, shared and navigated with the back button. Phase 15: once a delivery
 * location is known, only the stores that deliver there are searched (`stores=all` searches every store).
 */
export function SearchPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [showFilters, setShowFilters] = useState(false)
  const resultsRef = useRef(null)
  const filtersId = useId()
  const sortId = useId()
  const { openChooser } = useDeliveryLocation()

  const q = searchParams.get('q')?.trim() ?? ''
  const categoryId = searchParams.get('categoryId') || undefined
  const brandId = searchParams.get('brandId') || undefined
  const vendorId = searchParams.get('vendorId') || undefined
  const foodTypeParam = searchParams.get('foodType')
  const foodType = isFoodType(foodTypeParam) ? foodTypeParam : undefined
  const sortParam = searchParams.get('sort')
  // `relevance` only means something with a text query.
  const sort = isSort(sortParam) && (sortParam !== 'relevance' || q) ? sortParam : undefined
  const defaultSort = q ? 'relevance' : 'newest'
  const page = pageFromParam(searchParams.get('page'))
  const inStock = searchParams.get('inStock') === 'true'
  const priceRange = priceRangeFromParams(searchParams.get('minPrice'), searchParams.get('maxPrice'))
  const priceLabel = priceRangeLabel(priceRange)
  const showAllStores = searchParams.get('stores') === 'all'
  const scope = useStoreScope(showAllStores)
  // A store picked by hand ("Sold by …") wins over the stores near the shopper.
  const scoped = !vendorId

  const params = {
    q: q || undefined,
    categoryId,
    brandId,
    vendorId: vendorId ?? scope.vendorIds,
    foodType,
    inStock: inStock ? true : undefined,
    minPrice: priceRange.minPrice,
    maxPrice: priceRange.maxPrice,
    sort,
    page,
    size: SEARCH_PAGE_SIZE,
  }
  const noneDeliver = scoped && scope.noneDeliver
  const search = useProductSearch(params, { enabled: !scoped || (!scope.isPending && !scope.noneDeliver) })
  const categories = useCategoryTree()
  const data = search.data

  const categoryName =
    (categoryId && categories.data ? findNode(categories.data, categoryId)?.name : undefined) ??
    data?.facets.categories.find((facet) => facet.id === categoryId)?.name
  const brandName = data?.facets.brands.find((facet) => facet.id === brandId)?.name
  const vendorName = data?.content.find((product) => product.vendor.id === vendorId)?.vendor.name
  const hasFilters = FILTER_KEYS.some((key) => searchParams.get(key))

  const heading = q ? `Results for “${q}”` : categoryId ? (categoryName ?? 'Category') : 'All products'

  function selectFacet(facet, value) {
    updateParams({ [facet]: value, page: null })
  }

  function clearFilters() {
    updateParams({ ...Object.fromEntries(FILTER_KEYS.map((key) => [key, null])), page: null })
  }

  const candidates = [
    categoryId && { key: 'categoryId', label: `Category: ${categoryName ?? 'selected'}`, clear: { categoryId: null } },
    brandId && { key: 'brandId', label: `Brand: ${brandName ?? 'selected'}`, clear: { brandId: null } },
    foodType && { key: 'foodType', label: FOOD_TYPE_SHORT_LABELS[foodType], clear: { foodType: null } },
    vendorId && { key: 'vendorId', label: `Sold by ${vendorName ?? 'selected vendor'}`, clear: { vendorId: null } },
    inStock && { key: 'inStock', label: 'In stock only', clear: { inStock: null } },
    priceLabel && { key: 'price', label: `Price: ${priceLabel}`, clear: { minPrice: null, maxPrice: null } },
  ]
  const chips = candidates.filter((chip) => Boolean(chip))

  // Contract: page × size may not exceed 1,000 results.
  const maxPages = Math.floor(MAX_SEARCH_RESULTS / SEARCH_PAGE_SIZE)
  const roots = categories.data ?? []
  const rootSelected = (id) =>
    categoryId === id ||
    Boolean(
      categoryId &&
        findNode(
          roots.filter((root) => root.id === id),
          categoryId,
        ),
    )

  return (
    <div className="space-y-5">
      {/* The search band: the big search box and the category shortcuts. */}
      <section
        aria-label="Search the shop"
        className="animate-fade-up relative z-10 rounded-3xl bg-gradient-to-br from-emerald-700 via-emerald-800 to-teal-800 p-4 text-white shadow-lg shadow-emerald-900/15 sm:p-6"
      >
        <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10 overflow-hidden rounded-3xl">
          <div className="absolute -top-24 -right-16 size-72 rounded-full bg-emerald-400/20 blur-3xl" />
          <div className="absolute -bottom-28 left-1/4 size-72 rounded-full bg-amber-300/15 blur-3xl" />
          <div className="absolute inset-0 [background-image:radial-gradient(rgb(255_255_255/0.12)_1px,transparent_1px)] [background-size:22px_22px] [mask-image:linear-gradient(to_bottom,black,transparent_80%)]" />
        </div>
        <div className="flex flex-col gap-4 lg:flex-row lg:items-center">
          <SearchBox
            key={q}
            defaultValue={q}
            size="lg"
            className="w-full max-w-3xl text-slate-900 [&_input]:border-white [&_input]:shadow-lg [&_input]:shadow-emerald-950/20"
            onSearch={(term) => updateParams({ q: term || null, page: null, brandId: null, sort: null })}
          />
        </div>
        {roots.length > 0 && (
          <div
            role="group"
            aria-label="Shop by category"
            className="-mx-1 mt-4 flex gap-2 overflow-x-auto px-1 pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
          >
            <CategoryShortcut
              selected={!categoryId}
              onClick={() => updateParams({ categoryId: null, page: null })}
              icon={<Icon name="grid" className="size-4" />}
            >
              Everything
            </CategoryShortcut>
            {roots.map((root) => (
              <CategoryShortcut
                key={root.id}
                selected={rootSelected(root.id)}
                onClick={() => updateParams({ categoryId: root.id, brandId: null, page: null })}
                icon={<CategoryIcon name={root.name} className="size-4" />}
              >
                {root.name}
              </CategoryShortcut>
            ))}
          </div>
        )}
      </section>

      {scoped && (
        <StoreScopeBar scope={scope} onShowAll={(all) => updateParams({ stores: all ? 'all' : null, page: null })} />
      )}

      <div className="animate-fade-up flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold tracking-tight break-words text-slate-900 sm:text-[1.65rem]">{heading}</h1>
          <p className="mt-0.5 text-sm font-medium text-slate-500" aria-live="polite">
            {data && !noneDeliver
              ? `${data.totalElements.toLocaleString('en-IN')} product${data.totalElements === 1 ? '' : 's'}`
              : search.isFetching
                ? 'Searching…'
                : ''}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            className="lg:hidden"
            aria-expanded={showFilters}
            aria-controls={filtersId}
            onClick={() => setShowFilters((value) => !value)}
          >
            <Icon name="filter" className="size-4" />
            Filters{chips.length > 0 ? ` (${chips.length})` : ''}
          </Button>
          <label htmlFor={sortId} className="sr-only text-sm whitespace-nowrap text-slate-600 sm:not-sr-only">
            Sort by
          </label>
          <Select
            id={sortId}
            className="h-10 min-w-0 flex-1 bg-white sm:w-48 sm:flex-none"
            value={sort ?? defaultSort}
            onChange={(event) => {
              const next = event.target.value
              updateParams({ sort: next === defaultSort ? null : next, page: null })
            }}
          >
            {PRODUCT_SORTS.filter((option) => option !== 'relevance' || q).map((option) => (
              <option key={option} value={option}>
                {PRODUCT_SORT_LABELS[option]}
              </option>
            ))}
          </Select>
        </div>
      </div>

      {chips.length > 0 && (
        <ul className="animate-fade-up -mt-1 flex flex-wrap items-center gap-2" aria-label="Active filters">
          {chips.map((chip) => (
            <li key={chip.key}>
              <button
                type="button"
                onClick={() => updateParams({ ...chip.clear, page: null })}
                className="group inline-flex h-8 items-center gap-1.5 rounded-full bg-slate-900 pr-1.5 pl-3 text-xs font-semibold text-white shadow-sm transition hover:bg-slate-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
                aria-label={`Remove filter ${chip.label}`}
              >
                {chip.label}
                <span className="flex size-5 items-center justify-center rounded-full bg-white/20 transition group-hover:bg-white group-hover:text-slate-900">
                  <Icon name="close" className="size-3" />
                </span>
              </button>
            </li>
          ))}
          <li>
            <button
              type="button"
              onClick={clearFilters}
              className="h-8 rounded-full px-2.5 text-xs font-semibold text-slate-600 hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
            >
              Clear all
            </button>
          </li>
        </ul>
      )}

      <div className="grid gap-6 lg:grid-cols-[15.5rem_minmax(0,1fr)]">
        <aside
          id={filtersId}
          aria-label="Filters"
          className={cn('lg:sticky lg:top-20 lg:block lg:self-start', showFilters ? 'animate-fade-up block' : 'hidden')}
        >
          <div className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.03] lg:max-h-[calc(100dvh-6rem)] lg:overflow-y-auto">
            <div className="flex items-center gap-2 px-4 pt-4 pb-1">
              <span
                aria-hidden="true"
                className="flex size-8 items-center justify-center rounded-lg bg-slate-50 ring-1 ring-slate-200"
              >
                <Icon name="sliders" className="size-4" />
              </span>
              <h2 className="text-sm font-semibold text-slate-900">Filters</h2>
              {chips.length > 0 && (
                <button
                  type="button"
                  onClick={clearFilters}
                  className="ml-auto rounded-full px-2 py-1 text-xs font-semibold text-emerald-700 hover:bg-emerald-50 focus-visible:outline-2 focus-visible:outline-emerald-600"
                >
                  Reset ({chips.length})
                </button>
              )}
            </div>
            <div className="divide-y divide-slate-100">
              <section aria-label="Availability" className="px-4 py-4">
                <h3 className={FILTER_HEADING}>Availability</h3>
                <Switch
                  size="sm"
                  label="In stock only"
                  checked={inStock}
                  onChange={(checked) => updateParams({ inStock: checked ? 'true' : null, page: null })}
                />
              </section>
              <div className="px-4 py-4">
                <PriceRangeFilter
                  key={`${priceRange.minPrice ?? ''}-${priceRange.maxPrice ?? ''}`}
                  value={priceRange}
                  onApply={(range) => updateParams({ ...priceRangeToParams(range), page: null })}
                />
              </div>
              <div className="px-2.5 py-4">
                {data ? (
                  <SearchFacets
                    facets={data.facets}
                    selection={{ categoryId, brandId, foodType }}
                    selectedCategoryName={categoryName}
                    onSelect={selectFacet}
                    disabled={search.isFetching}
                  />
                ) : (
                  <p className="px-1.5 text-sm text-slate-500">
                    {search.isError ? 'Filters are unavailable.' : 'Loading filters…'}
                  </p>
                )}
              </div>
            </div>
            <div className="border-t border-slate-100 p-3 lg:hidden">
              <Button className="w-full" onClick={() => setShowFilters(false)}>
                Show results
              </Button>
            </div>
          </div>
        </aside>

        <div ref={resultsRef} className="@container min-w-0 scroll-mt-24">
          {noneDeliver && scope.location ? (
            <EmptyState
              size="lg"
              illustration={<SearchIllustration />}
              title={`No store delivers to ${deliveryPlaceText(scope.location)} yet`}
              description={`Stores deliver up to ${scope.deliveryLimitKm ?? 15} km. Choose another location, or search every store.`}
              action={
                <>
                  <Button onClick={openChooser}>Choose another location</Button>
                  <Button variant="secondary" onClick={() => updateParams({ stores: 'all', page: null })}>
                    Search all stores
                  </Button>
                </>
              }
            />
          ) : search.isPending ? (
            <div role="status" aria-label="Loading products" className={PRODUCT_GRID}>
              {Array.from({ length: 8 }, (_, index) => (
                <ProductTileSkeleton key={index} />
              ))}
            </div>
          ) : search.isError ? (
            <ErrorState
              title={
                hasErrorCode(search.error, 'INVALID_PARAMETER')
                  ? 'This search could not be run'
                  : 'Could not load products'
              }
              message={search.error.message}
              correlationId={search.error instanceof ApiError ? search.error.correlationId : null}
              onRetry={() => void search.refetch()}
            />
          ) : data && data.content.length === 0 ? (
            <div className="space-y-6">
              <EmptyState
                size="lg"
                illustration={<SearchIllustration />}
                title="No products found"
                description={
                  q
                    ? `Nothing matches “${q}”${hasFilters ? ' with these filters' : ''}${scope.mode === 'near' && scoped ? ' in the stores near you' : ''}. Check the spelling or try a more general word.`
                    : 'There are no products in this selection yet.'
                }
                action={
                  hasFilters || q || (scope.mode === 'near' && scoped) ? (
                    <>
                      {hasFilters && (
                        <Button variant="secondary" onClick={clearFilters}>
                          Clear filters
                        </Button>
                      )}
                      {scope.mode === 'near' && scoped && (
                        <Button variant="secondary" onClick={() => updateParams({ stores: 'all', page: null })}>
                          Search all stores
                        </Button>
                      )}
                      <Link
                        to="/search"
                        className="inline-flex h-10 items-center rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-600/25 hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
                      >
                        Browse all products
                      </Link>
                    </>
                  ) : undefined
                }
              />
            </div>
          ) : data ? (
            <>
              <ul
                aria-label="Products"
                className={cn(PRODUCT_GRID, 'transition-opacity', search.isPlaceholderData && 'opacity-60')}
              >
                {data.content.map((product) => (
                  // min-w-0: without it a long name made the card wider than its column (sideways scroll on phones).
                  <li key={product.id} className="flex min-w-0">
                    <div className="min-w-0 flex-1">
                      <ProductTile
                        product={product}
                        storeDistanceKm={scope.distances.get(product.vendor.id)}
                        action={<ProductCartControl product={product} block />}
                      />
                    </div>
                  </li>
                ))}
              </ul>
              <Pagination
                page={data.page}
                size={data.size}
                totalElements={Math.min(data.totalElements, maxPages * data.size)}
                totalPages={Math.min(data.totalPages, maxPages)}
                disabled={search.isFetching}
                onPageChange={(next) => {
                  updateParams({ page: pageToParam(next) })
                  resultsRef.current?.scrollIntoView?.({ block: 'start' })
                }}
              />
            </>
          ) : null}
        </div>
      </div>
    </div>
  )
}
