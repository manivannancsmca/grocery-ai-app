import { useId, useRef } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { Badge, Button, EmptyState, ErrorState, Pagination, Select, Switch } from '../../shared/components/ui'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../shared/icons'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import { SearchIllustration } from '../../shared/illustrations'
import { cn } from '../../shared/utils/cn'
import { ProductCartControl } from '../cart/CartItemControl'
import { MAX_SEARCH_RESULTS, PRODUCT_SORT_LABELS, SEARCH_PAGE_SIZE } from './api'
import { PRODUCT_GRID } from './catalogLayout'
import { CategoryIcon } from './categoryVisuals'
import { LIVE_CATALOG_FALLBACK_POLL_MS, useCategoryTree, useLiveProductCatalog } from './hooks'
import { useProductChanges } from './productChanges'
import { ProductTile, ProductTileSkeleton } from './ProductTile'
import { deliveryPlaceText, useDeliveryLocation } from '../stores/deliveryLocation'
import { useStoreScope } from '../stores/hooks'
import { StoreScopeBar } from '../stores/StoreScopeBar'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('./productChanges').ProductChange} ProductChange */

/** No `relevance`: there is no search text here. The first one is the API default without text. */
const SORTS = ['newest', 'price_asc', 'price_desc', 'rating', 'name_asc']
/** @typedef {(typeof SORTS)[number]} CatalogSort */
/** @type {CatalogSort} */
const DEFAULT_SORT = 'newest'

/**
 * @param {string | null} value
 * @returns {value is CatalogSort}
 */
function isCatalogSort(value) {
  return SORTS.includes(value ?? '')
}

/** Contract: page × size may not exceed 1,000 results. */
const MAX_PAGES = Math.floor(MAX_SEARCH_RESULTS / SEARCH_PAGE_SIZE)

/** @type {Record<ProductChange, { label: string, tone: BadgeTone, ring: string }>} */
const CHANGE_STYLES = {
  PRICE_DROP: { label: 'Price dropped', tone: 'green', ring: 'ring-emerald-400' },
  PRICE_RISE: { label: 'Price went up', tone: 'amber', ring: 'ring-amber-400' },
  BACK_IN_STOCK: { label: 'Back in stock', tone: 'green', ring: 'ring-emerald-400' },
  SOLD_OUT: { label: 'Just sold out', tone: 'red', ring: 'ring-red-300' },
}

/** @param {{ live: boolean }} props */
function LiveStatus({ live }) {
  if (!live) return <Badge tone="gray">Auto-refresh</Badge>
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-semibold text-emerald-700 ring-1 ring-emerald-200 ring-inset">
      <span className="relative flex size-2" aria-hidden="true">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-75 motion-reduce:hidden" />
        <span className="relative inline-flex size-2 rounded-full bg-emerald-500" />
      </span>
      Live
    </span>
  )
}

/** @param {{ selected: boolean, onClick: () => void, icon: ReactNode, children: ReactNode }} props */
function CategoryChip({ selected, onClick, icon, children }) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        'inline-flex h-9 shrink-0 items-center gap-1.5 rounded-full pr-3.5 pl-2.5 text-sm font-medium whitespace-nowrap ring-1 ring-inset transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
        selected
          ? 'bg-emerald-600 text-white shadow-sm shadow-emerald-600/25 ring-emerald-600'
          : 'bg-white text-slate-700 ring-slate-200 hover:bg-emerald-50/60 hover:text-emerald-800 hover:ring-emerald-200',
      )}
    >
      {/* A white disc keeps the colourful icon readable on the selected chip's emerald background. */}
      <span className={cn('-ml-1 flex size-6 items-center justify-center rounded-full', selected && 'bg-white')}>
        {icon}
      </span>
      {children}
    </button>
  )
}

/**
 * Storefront "All products": every published product with its current price and stock, kept up to date while
 * the page is open (live signals, plus a poll for new products). Filters live in the query string
 * (`category`, `sort`, `inStock`, `page`), so they survive the back button. Products whose price or stock just
 * changed are highlighted for a few seconds.
 */
export function LiveProductCatalog() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const sectionRef = useRef(null)
  const headingId = useId()
  const sortId = useId()
  const live = useRealtimeLive()
  const categories = useCategoryTree()

  const categoryId = searchParams.get('category') || undefined
  const sortParam = searchParams.get('sort')
  const sort = isCatalogSort(sortParam) ? sortParam : DEFAULT_SORT
  const inStock = searchParams.get('inStock') === 'true'
  const page = Math.min(pageFromParam(searchParams.get('page')), MAX_PAGES - 1)
  // Phase 15: only the stores that deliver to the delivery location, unless the shopper asked for every store.
  const showAllStores = searchParams.get('stores') === 'all'
  const scope = useStoreScope(showAllStores)
  const { openChooser } = useDeliveryLocation()

  const params = {
    categoryId,
    vendorId: scope.vendorIds,
    inStock: inStock ? true : undefined,
    // The default is left out, so page 1 shares its cache with the search page's "All products".
    sort: sort === DEFAULT_SORT ? undefined : sort,
    page,
    size: SEARCH_PAGE_SIZE,
  }
  const catalog = useLiveProductCatalog(params, { enabled: !scope.isPending && !scope.noneDeliver })
  const data = catalog.data
  const changes = useProductChanges(catalog.isPlaceholderData ? undefined : data?.content, JSON.stringify(params))

  const roots = categories.data ?? []
  const hasFilters = Boolean(categoryId) || inStock
  const refreshSeconds = LIVE_CATALOG_FALLBACK_POLL_MS / 1000
  const searchLink = categoryId ? `/search?categoryId=${encodeURIComponent(categoryId)}` : '/search'

  const announcements = (data?.content ?? [])
    .filter((product) => changes.has(product.id))
    .map((product) => `${product.name}: ${CHANGE_STYLES[changes.get(product.id)].label.toLowerCase()}.`)

  function clearFilters() {
    updateParams({ category: null, inStock: null, page: null })
  }

  return (
    <section ref={sectionRef} aria-labelledby={headingId} className="animate-fade-up @container scroll-mt-24">
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <h2 id={headingId} className="text-lg font-semibold text-slate-900">
              All products
            </h2>
            <LiveStatus live={live} />
          </div>
          <p className="mt-0.5 text-sm text-slate-500">
            {data && `${data.totalElements.toLocaleString('en-IN')} product${data.totalElements === 1 ? '' : 's'} · `}
            {live
              ? 'Prices and stock update the moment they change.'
              : `Prices and stock refresh every ${refreshSeconds} seconds.`}
          </p>
        </div>
        <Link
          to={searchLink}
          className="inline-flex h-9 items-center gap-1.5 rounded-full bg-white px-3.5 text-sm font-semibold text-emerald-700 shadow-xs ring-1 ring-slate-200 transition ring-inset hover:bg-emerald-50 hover:text-emerald-800 hover:ring-emerald-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
        >
          <Icon name="filter" className="size-4" />
          More filters in search
        </Link>
      </div>

      <div className="mb-5 space-y-3">
        {roots.length > 0 && (
          <div
            role="group"
            aria-label="Filter by category"
            className="-mx-1 flex min-w-0 gap-2 overflow-x-auto px-1 py-1 [scrollbar-width:thin]"
          >
            <CategoryChip
              selected={!categoryId}
              onClick={() => updateParams({ category: null, page: null })}
              icon={<Icon name="grid" className="size-4" />}
            >
              All
            </CategoryChip>
            {roots.map((category) => (
              <CategoryChip
                key={category.id}
                selected={categoryId === category.id}
                onClick={() => updateParams({ category: category.id, page: null })}
                icon={<CategoryIcon name={category.name} className="size-4" />}
              >
                {category.name}
              </CategoryChip>
            ))}
          </div>
        )}
        <StoreScopeBar scope={scope} onShowAll={(all) => updateParams({ stores: all ? 'all' : null, page: null })} />
        <div className="flex items-center justify-between gap-3 rounded-2xl border border-slate-200/80 bg-white/80 py-2 pr-2 pl-3.5 shadow-xs">
          <Switch
            size="sm"
            label="In stock only"
            checked={inStock}
            onChange={(checked) => updateParams({ inStock: checked ? 'true' : null, page: null })}
          />
          <div className="flex items-center gap-2">
            <label htmlFor={sortId} className="sr-only text-sm whitespace-nowrap text-slate-600 sm:not-sr-only">
              Sort by
            </label>
            <Select
              id={sortId}
              className="h-9 w-40 sm:w-44"
              value={sort}
              onChange={(event) => {
                const next = event.target.value
                updateParams({ sort: next === DEFAULT_SORT ? null : next, page: null })
              }}
            >
              {SORTS.map((option) => (
                <option key={option} value={option}>
                  {PRODUCT_SORT_LABELS[option]}
                </option>
              ))}
            </Select>
          </div>
        </div>
      </div>

      <p className="sr-only" aria-live="polite">
        {announcements.join(' ')}
      </p>

      {scope.noneDeliver && scope.location ? (
        <EmptyState
          illustration={<SearchIllustration />}
          title={`No store delivers to ${deliveryPlaceText(scope.location)} yet`}
          description={`Stores deliver up to ${scope.deliveryLimitKm ?? 15} km. Choose another location, or browse every store (checkout tells you which ones can deliver).`}
          action={
            <>
              <Button size="sm" onClick={openChooser}>
                Choose another location
              </Button>
              <Button variant="secondary" size="sm" onClick={() => updateParams({ stores: 'all', page: null })}>
                Show all stores
              </Button>
            </>
          }
        />
      ) : catalog.isPending ? (
        <div role="status" aria-label="Loading products" className={PRODUCT_GRID}>
          {Array.from({ length: 8 }, (_, index) => (
            <ProductTileSkeleton key={index} />
          ))}
        </div>
      ) : catalog.isError ? (
        <ErrorState
          title="Products could not be loaded"
          message={catalog.error.message}
          correlationId={catalog.error instanceof ApiError ? catalog.error.correlationId : null}
          onRetry={() => void catalog.refetch()}
        />
      ) : data && data.content.length === 0 ? (
        <EmptyState
          illustration={hasFilters ? <SearchIllustration /> : undefined}
          title={hasFilters ? 'No products match these filters' : 'No products yet'}
          description={
            hasFilters
              ? 'Try another category, or include products that are out of stock.'
              : 'Products appear here as soon as vendors publish them.'
          }
          action={
            hasFilters ? (
              <Button variant="secondary" size="sm" onClick={clearFilters}>
                Clear filters
              </Button>
            ) : undefined
          }
        />
      ) : data ? (
        <>
          <ul
            aria-label="All products"
            className={cn(PRODUCT_GRID, 'transition-opacity', catalog.isPlaceholderData && 'opacity-60')}
          >
            {data.content.map((product) => {
              const change = changes.get(product.id)
              const style = change ? CHANGE_STYLES[change] : null
              return (
                // min-w-0: a grid/flex item is at least as wide as its content by default, so a long product or store
                // name pushed the card past its column (the page scrolled sideways on phones); now the name truncates.
                <li key={product.id} className="flex min-w-0">
                  <div
                    className={cn(
                      'min-w-0 flex-1 rounded-2xl transition-shadow',
                      style && `ring-2 ring-offset-2 ${style.ring}`,
                    )}
                  >
                    <ProductTile
                      product={product}
                      storeDistanceKm={scope.distances.get(product.vendor.id)}
                      action={<ProductCartControl product={product} block />}
                      flag={
                        style && (
                          <Badge tone={style.tone} className="shadow-sm">
                            {style.label}
                          </Badge>
                        )
                      }
                    />
                  </div>
                </li>
              )
            })}
          </ul>
          <Pagination
            page={data.page}
            size={data.size}
            totalElements={Math.min(data.totalElements, MAX_PAGES * data.size)}
            totalPages={Math.min(data.totalPages, MAX_PAGES)}
            disabled={catalog.isPlaceholderData}
            onPageChange={(next) => {
              updateParams({ page: pageToParam(next) })
              sectionRef.current?.scrollIntoView?.({ block: 'start' })
            }}
          />
        </>
      ) : null}
    </section>
  )
}
