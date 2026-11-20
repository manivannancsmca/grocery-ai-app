import { useId, useRef } from 'react'
import { Link, useParams } from 'react-router'
import { ProductCartControl } from '../../../features/cart/CartItemControl'
import { MAX_SEARCH_RESULTS, PRODUCT_SORT_LABELS, SEARCH_PAGE_SIZE } from '../../../features/catalog/api'
import { PRODUCT_GRID } from '../../../features/catalog/catalogLayout'
import { CategoryIcon } from '../../../features/catalog/categoryVisuals'
import { useProductSearch } from '../../../features/catalog/hooks'
import { ProductTile, ProductTileSkeleton } from '../../../features/catalog/ProductTile'
import { RestaurantCover } from '../../../features/restaurant-discovery/RestaurantCover'
import { formatDistance, STORE_NOT_FOUND, storePlace } from '../../../features/stores/api'
import { deliveryPlaceText, useDeliveryLocation } from '../../../features/stores/deliveryLocation'
import { useStore } from '../../../features/stores/hooks'
import { StoreDistance } from '../../../features/stores/StoreCard'
import { storeTheme } from '../../../features/stores/theme'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import {
  Alert,
  Button,
  EmptyState,
  ErrorState,
  Input,
  Pagination,
  Select,
  Skeleton,
  Switch,
} from '../../../shared/components/ui'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { LostIllustration, SearchIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'
import { PillToggle, STICKY_TOOLBAR } from '../components/ShopperPageHeader'

const SORTS = ['newest', 'price_asc', 'price_desc', 'rating', 'name_asc']
const MAX_PAGES = Math.floor(MAX_SEARCH_RESULTS / SEARCH_PAGE_SIZE)

/**
 * @param {string | null} value
 * @returns {value is (typeof SORTS)[number]}
 */
function isSort(value) {
  return SORTS.includes(value ?? '')
}

/**
 * `/stores/:storeId` — one grocery store: where it is, how far from the delivery location (and whether it delivers
 * there), and its shelves: search within the store, categories, stock and sort, all in the URL.
 */
export function StorePage() {
  const { storeId = '' } = useParams()
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const resultsRef = useRef(null)
  const sortId = useId()
  const delivery = useDeliveryLocation()
  const store = useStore(storeId, delivery.location)

  const queryText = searchParams.get('q') ?? ''
  const q = useDebouncedValue(queryText.trim(), 300)
  const categoryId = searchParams.get('category') || undefined
  const inStock = searchParams.get('inStock') === 'true'
  const sortParam = searchParams.get('sort')
  const sort = isSort(sortParam) ? sortParam : undefined
  const page = Math.min(pageFromParam(searchParams.get('page')), MAX_PAGES - 1)

  const params = {
    vendorId: storeId,
    q: q || undefined,
    categoryId,
    inStock: inStock ? true : undefined,
    sort,
    page,
    size: SEARCH_PAGE_SIZE,
  }
  const products = useProductSearch(params, { enabled: store.isSuccess })
  // The store's whole range (one tiny search) keeps the category chips steady while a filter is on.
  const range = useProductSearch({ vendorId: storeId, size: 1 }, { enabled: store.isSuccess })
  const categories = range.data?.facets.categories ?? []
  const data = products.data
  const hasFilters = Boolean(q) || Boolean(categoryId) || inStock

  if (store.isPending) {
    return (
      <div role="status" aria-label="Loading the store" className="space-y-5">
        <Skeleton className="h-44 w-full rounded-3xl" />
        <div className={PRODUCT_GRID}>
          {Array.from({ length: 5 }, (_, index) => (
            <ProductTileSkeleton key={index} />
          ))}
        </div>
      </div>
    )
  }

  if (store.isError) {
    if (hasErrorCode(store.error, STORE_NOT_FOUND) || (store.error instanceof ApiError && store.error.status === 404)) {
      return (
        <EmptyState
          size="lg"
          illustration={<LostIllustration />}
          title="This store is not available"
          description="It may have closed or moved. Stores that deliver to you are listed on the home page."
          action={
            <Link
              to="/"
              className="inline-flex h-10 items-center rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
            >
              See stores near you
            </Link>
          }
        />
      )
    }
    return (
      <ErrorState
        title="Could not load this store"
        message={store.error.message}
        correlationId={store.error instanceof ApiError ? store.error.correlationId : null}
        onRetry={() => void store.refetch()}
      />
    )
  }

  const info = store.data
  const place = storePlace(info)
  const address = [info.line1, place, info.postalCode].filter(Boolean).join(', ')
  const productCount = range.data?.totalElements

  return (
    <div className="space-y-5">
      <nav aria-label="Breadcrumb" className="animate-fade-up text-sm text-slate-500">
        <ol className="flex flex-wrap items-center gap-1.5">
          <li>
            <Link to="/" className="hover:text-emerald-700 hover:underline">
              Home
            </Link>
          </li>
          <li aria-hidden="true">
            <Icon name="chevronRight" className="size-3.5" />
          </li>
          <li>Stores</li>
          <li aria-hidden="true">
            <Icon name="chevronRight" className="size-3.5" />
          </li>
          <li aria-current="page" className="font-medium text-slate-700">
            {info.name}
          </li>
        </ol>
      </nav>

      <section
        aria-labelledby="store-name"
        className="animate-fade-up overflow-hidden rounded-3xl border border-slate-200/80 bg-white shadow-sm"
      >
        <RestaurantCover theme={storeTheme(info.id)} className="h-28 sm:h-36" disc="none" />
        <div className="relative px-5 pb-5 sm:px-7">
          <span
            aria-hidden="true"
            className="-mt-10 flex size-20 items-center justify-center rounded-3xl bg-white shadow-lg ring-4 shadow-emerald-900/10 ring-white"
          >
            <Icon name={storeTheme(info.id).icon} className="size-11" />
          </span>
          <div className="mt-3 flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
            <div className="min-w-0">
              <p className="text-[0.7rem] font-semibold tracking-wider text-emerald-700 uppercase">Grocery store</p>
              <h1 id="store-name" className="text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
                {info.name}
              </h1>
              {address && (
                <p className="mt-1 flex items-start gap-1.5 text-sm text-slate-600">
                  <Icon name="mapPin" className="mt-0.5 size-4 shrink-0" />
                  {address}
                </p>
              )}
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <StoreDistance store={info} className="px-2.5 py-1 text-sm" />
              {productCount !== undefined && (
                <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1 text-sm font-semibold text-slate-700">
                  <Icon name="basket" className="size-4" />
                  {productCount} product{productCount === 1 ? '' : 's'}
                </span>
              )}
            </div>
          </div>
          {info.deliversHere === false && delivery.location && info.distanceKm !== null && (
            <Alert
              tone="warning"
              className="mt-4"
              title={`Too far to deliver to ${deliveryPlaceText(delivery.location)}`}
              actions={
                <Button variant="secondary" size="sm" onClick={delivery.openChooser}>
                  Change location
                </Button>
              }
            >
              This store is {formatDistance(info.distanceKm)} away and delivers up to 15 km. You can browse, but
              checkout will not accept this address.
            </Alert>
          )}
          {!delivery.location && !delivery.isPending && (
            <p className="mt-4 flex flex-wrap items-center gap-2 text-sm text-slate-600">
              <Icon name="info" className="size-4" />
              Set your delivery location to see whether this store delivers to you.
              <Button variant="ghost" size="sm" onClick={delivery.openChooser}>
                Choose location
              </Button>
            </p>
          )}
        </div>
      </section>

      <div className={cn(STICKY_TOOLBAR, 'space-y-3')}>
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
          <label className="relative block flex-1">
            <span className="sr-only">Search in {info.name}</span>
            <Icon name="search" className="pointer-events-none absolute top-1/2 left-3.5 size-5 -translate-y-1/2" />
            <Input
              type="search"
              value={queryText}
              placeholder={`Search in ${info.name}`}
              className="h-11 rounded-full bg-slate-50 pl-11 focus:bg-white"
              onChange={(event) => updateParams({ q: event.target.value || null, page: null }, { replace: true })}
            />
          </label>
          <div className="flex items-center gap-3">
            <Switch
              size="sm"
              label="In stock only"
              checked={inStock}
              onChange={(checked) => updateParams({ inStock: checked ? 'true' : null, page: null })}
            />
            <label htmlFor={sortId} className="sr-only">
              Sort by
            </label>
            <Select
              id={sortId}
              className="h-10 w-44"
              value={sort ?? 'newest'}
              onChange={(event) =>
                updateParams({ sort: event.target.value === 'newest' ? null : event.target.value, page: null })
              }
            >
              {SORTS.map((option) => (
                <option key={option} value={option}>
                  {PRODUCT_SORT_LABELS[option]}
                </option>
              ))}
            </Select>
          </div>
        </div>
        {/* relative: the chips' screen-reader-only counts are absolutely positioned; without a positioned scroller they
            escaped the scrolling and widened the whole page on phones. */}
        {categories.length > 1 && (
          <div
            role="group"
            aria-label="Categories in this store"
            className="relative -mx-1 flex gap-2 overflow-x-auto px-1 pb-0.5 [scrollbar-width:none]"
          >
            <PillToggle
              pressed={!categoryId}
              onClick={() => updateParams({ category: null, page: null })}
              icon={<Icon name="grid" className="size-4" />}
            >
              All
            </PillToggle>
            {categories.map((category) => (
              <PillToggle
                key={category.id}
                pressed={categoryId === category.id}
                onClick={() => updateParams({ category: categoryId === category.id ? null : category.id, page: null })}
                icon={<CategoryIcon name={category.name} className="size-4" />}
                count={category.count}
              >
                {category.name}
              </PillToggle>
            ))}
          </div>
        )}
      </div>

      <div ref={resultsRef} className="@container scroll-mt-40">
        {products.isPending ? (
          <div role="status" aria-label="Loading products" className={PRODUCT_GRID}>
            {Array.from({ length: 10 }, (_, index) => (
              <ProductTileSkeleton key={index} />
            ))}
          </div>
        ) : products.isError ? (
          <ErrorState
            title="Could not load the products"
            message={products.error.message}
            correlationId={products.error instanceof ApiError ? products.error.correlationId : null}
            onRetry={() => void products.refetch()}
          />
        ) : data && data.content.length === 0 ? (
          <EmptyState
            illustration={<SearchIllustration />}
            title={hasFilters ? 'Nothing here matches' : 'No products yet'}
            description={
              hasFilters
                ? 'Try another word or category, or include products that are out of stock.'
                : 'This store has not published products yet.'
            }
            action={
              hasFilters ? (
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => updateParams({ q: null, category: null, inStock: null, page: null })}
                >
                  Clear filters
                </Button>
              ) : undefined
            }
          />
        ) : data ? (
          <>
            <ul
              aria-label={`Products of ${info.name}`}
              className={cn(PRODUCT_GRID, 'transition-opacity', products.isPlaceholderData && 'opacity-60')}
            >
              {data.content.map((product) => (
                // min-w-0: without it a long name made the card wider than its column (sideways scroll on phones).
                <li key={product.id} className="flex min-w-0">
                  <div className="min-w-0 flex-1">
                    <ProductTile
                      product={product}
                      storeDistanceKm={info.distanceKm ?? undefined}
                      action={<ProductCartControl product={product} block />}
                    />
                  </div>
                </li>
              ))}
            </ul>
            <Pagination
              page={data.page}
              size={data.size}
              totalElements={Math.min(data.totalElements, MAX_PAGES * data.size)}
              totalPages={Math.min(data.totalPages, MAX_PAGES)}
              disabled={products.isFetching}
              onPageChange={(next) => {
                updateParams({ page: pageToParam(next) })
                resultsRef.current?.scrollIntoView?.({ block: 'start' })
              }}
            />
          </>
        ) : null}
      </div>
    </div>
  )
}
