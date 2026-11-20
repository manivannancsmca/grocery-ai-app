import { Link } from 'react-router'
import { ProductImage } from '../../../features/catalog/ProductImage'
import {
  isProductStatus,
  PRODUCT_STATUS_LABELS,
  PRODUCT_STATUSES,
  type ProductStatus,
  type ProductView,
  VENDOR_NOT_ACTIVE,
  vendorProductPath,
} from '../../../features/vendor-products/api'
import { type ProductCounts, useVendorProductCounts, useVendorProducts } from '../../../features/vendor-products/hooks'
import { EditProductLink, ListingGapChips, ProductCategory, UnitPill, UpdatedAgo } from '../../../features/vendor-products/ProductListBits'
import { ProductsOverview } from '../../../features/vendor-products/ProductsOverview'
import { ProductStatusActions, ProductStatusBadge, VendorNotActiveNotice } from '../../../features/vendor-products/ProductStatusActions'
import { useListingGaps } from '../../../features/vendor-products/useListingGaps'
import { VendorProductCard } from '../../../features/vendor-products/VendorProductCard'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import { FoodTypeMark } from '../../../shared/components/FoodTypeMark'
import {
  type Column,
  DataTable,
  EmptyState,
  ErrorState,
  Input,
  PageHeader,
  Pagination,
  Skeleton,
  type TabItem,
  Tabs,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { useMediaQuery } from '../../../shared/hooks/useMediaQuery'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon, type IconName } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'

type StatusTab = 'ALL' | ProductStatus
type Layout = 'list' | 'grid'

const TAB_ICONS: Record<StatusTab, IconName> = {
  ALL: 'grid',
  DRAFT: 'pencil',
  PUBLISHED: 'checkCircle',
  UNPUBLISHED: 'eyeOff',
  ARCHIVED: 'box',
}

/** A status tab: icon, label and a count pill (the count is decoration: the tab's name stays "Published" etc.). */
function tabsWithCounts(counts: ProductCounts): readonly TabItem<StatusTab>[] {
  const label = (id: StatusTab, text: string) => (
    <>
      <Icon name={TAB_ICONS[id]} className="size-4" />
      {text}
      {counts[id] !== undefined && (
        <span aria-hidden="true" className="rounded-full bg-slate-200/70 px-1.5 py-px text-[11px] font-bold text-slate-600 tabular-nums">
          {counts[id]}
        </span>
      )}
    </>
  )
  return [{ id: 'ALL', label: label('ALL', 'All') }, ...PRODUCT_STATUSES.map((status) => ({ id: status, label: label(status, PRODUCT_STATUS_LABELS[status]) }))]
}

const PRIMARY_LINK =
  'inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-900/20 transition-colors hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/** List / grid switch (wide screens; phones always get cards). */
function LayoutSwitch({ value, onChange }: { value: Layout; onChange: (layout: Layout) => void }) {
  const options: ReadonlyArray<{ id: Layout; label: string; icon: IconName }> = [
    { id: 'list', label: 'List view', icon: 'list' },
    { id: 'grid', label: 'Grid view', icon: 'grid' },
  ]
  return (
    <div role="group" aria-label="Layout" className="inline-flex shrink-0 rounded-xl bg-slate-100/80 p-1 ring-1 ring-slate-200/60 ring-inset">
      {options.map((option) => (
        <button
          key={option.id}
          type="button"
          aria-pressed={value === option.id}
          aria-label={option.label}
          title={option.label}
          onClick={() => onChange(option.id)}
          className={cn(
            'flex size-8 items-center justify-center rounded-lg transition-all focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-emerald-600',
            value === option.id ? 'bg-white text-emerald-700 shadow-sm ring-1 ring-slate-200/80' : 'text-slate-500 hover:bg-white/60 hover:text-slate-800',
          )}
        >
          <Icon name={option.icon} className="size-4" />
        </button>
      ))}
    </div>
  )
}

function LoadingProducts({ layout }: { layout: Layout | 'cards' }) {
  return (
    <div role="status" aria-label="Loading products" className={layout === 'grid' ? 'grid gap-4 sm:grid-cols-2 xl:grid-cols-4' : 'space-y-3'}>
      {Array.from({ length: layout === 'grid' ? 8 : 5 }, (_, index) =>
        layout === 'grid' ? (
          <Skeleton key={index} className="h-80 rounded-2xl" />
        ) : (
          <div key={index} className="flex items-center gap-3 rounded-xl border border-slate-100 p-3">
            <Skeleton className="size-12 rounded-xl" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-1/3" />
              <Skeleton className="h-3 w-1/4" />
            </div>
          </div>
        ),
      )}
    </div>
  )
}

/** `/vendor/products` — the vendor's catalog: overview, status tabs, search, list or grid, publish / unpublish / archive. */
export function VendorProductsPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const statusParam = searchParams.get('status')
  const status = isProductStatus(statusParam) ? statusParam : undefined
  const queryText = searchParams.get('q') ?? ''
  const debouncedQuery = useDebouncedValue(queryText.trim(), 300)
  const page = pageFromParam(searchParams.get('page'))
  const layout: Layout = searchParams.get('view') === 'grid' ? 'grid' : 'list'
  const phone = useMediaQuery('(max-width: 639px)')
  const shown = phone ? 'cards' : layout

  const products = useVendorProducts({ status, q: debouncedQuery || undefined, page, size: DEFAULT_PAGE_SIZE })
  const notActive = hasErrorCode(products.error, VENDOR_NOT_ACTIVE)
  const counts = useVendorProductCounts()
  // Published products customers cannot buy yet: two list calls for the whole page, not one per row.
  const gaps = useListingGaps()
  const gapsOf = (product: ProductView) => ({
    noPrice: product.status === 'PUBLISHED' && Boolean(gaps.unpriced?.has(product.id)),
    noStock: product.status === 'PUBLISHED' && Boolean(gaps.outOfStock?.has(product.id)),
  })
  const filtered = Boolean(debouncedQuery || status)

  const columns: Column<ProductView>[] = [
    {
      key: 'product',
      header: 'Product',
      cell: (product) => (
        <div className="flex min-w-0 items-center gap-3">
          <ProductImage
            src={product.imageUrls[0]}
            alt=""
            className="size-12 shrink-0 rounded-xl border border-slate-200 bg-white object-contain p-0.5"
            iconClassName="size-6"
          />
          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <FoodTypeMark foodType={product.foodType} className="shrink-0" />
              <Link to={vendorProductPath(product)} className="font-semibold text-slate-900 hover:text-emerald-700 hover:underline">
                {product.name}
              </Link>
            </div>
            <p className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-slate-500">
              {product.brand?.name && (
                <span className="inline-flex items-center gap-1">
                  <Icon name="tag" className="size-3" />
                  {product.brand.name}
                </span>
              )}
              {product.sku && <span className="font-mono text-[11px] text-slate-400">SKU {product.sku}</span>}
              {!product.brand?.name && !product.sku && '—'}
            </p>
          </div>
        </div>
      ),
    },
    {
      key: 'category',
      header: 'Category',
      hideOnMobile: true,
      cell: (product) => <ProductCategory product={product} className="text-sm text-slate-700" />,
    },
    { key: 'unit', header: 'Pack', hideOnMobile: true, cell: (product) => <UnitPill label={product.unitLabel} /> },
    {
      key: 'status',
      header: 'Status',
      cell: (product) => (
        <div className="flex flex-col items-start gap-1.5">
          <ProductStatusBadge status={product.status} />
          <ListingGapChips product={product} {...gapsOf(product)} />
        </div>
      ),
    },
    {
      key: 'updated',
      header: 'Updated',
      hideOnMobile: true,
      cell: (product) => <UpdatedAgo at={product.updatedAt} className="text-sm text-slate-600" />,
    },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'text-right',
      cell: (product) => (
        <div className="flex items-center justify-end gap-2 whitespace-nowrap">
          <EditProductLink product={product} />
          <ProductStatusActions product={product} />
        </div>
      ),
    },
  ]

  const empty = (
    <EmptyState
      title={filtered ? 'No products found' : 'No products yet'}
      description={filtered ? 'Try a different search or status.' : 'Add your first product. It starts as a draft until you publish it.'}
      action={
        filtered ? undefined : (
          <Link to="/vendor/products/new" className={PRIMARY_LINK}>
            <Icon name="plus" className="size-4" />
            Add product
          </Link>
        )
      }
    />
  )

  let content
  if (products.isPending) content = <LoadingProducts layout={shown} />
  else if (products.isError) {
    content = (
      <ErrorState
        title="Could not load your products"
        message={products.error.message}
        correlationId={products.error instanceof ApiError ? products.error.correlationId : null}
        onRetry={() => void products.refetch()}
      />
    )
  } else {
    const rows = products.data.content
    const dimmed = products.isFetching && products.isPlaceholderData
    content = (
      <div>
        {shown === 'list' ? (
          <DataTable
            caption="Your products"
            columns={columns}
            rows={rows}
            getRowKey={(product) => product.id}
            fetching={dimmed}
            rowClassName={(product) => (gaps.blocked?.has(product.id) ? 'bg-amber-50/40' : undefined)}
            empty={empty}
          />
        ) : rows.length === 0 ? (
          empty
        ) : (
          <ul
            aria-label="Your products"
            className={cn(
              shown === 'grid' ? 'grid gap-4 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4' : 'space-y-3',
              dimmed && 'opacity-60 transition-opacity',
            )}
          >
            {rows.map((product) => (
              // min-w-0 (on the item and the card): without it a long line (SKU, brand, chips) made the card wider
              // than the screen on phones and the page scrolled sideways.
              <li key={product.id} className="flex min-w-0 [&>*]:min-w-0 [&>*]:flex-1">
                <VendorProductCard product={product} layout={shown === 'grid' ? 'tile' : 'row'} {...gapsOf(product)} />
              </li>
            ))}
          </ul>
        )}
        <Pagination
          page={products.data.page}
          size={products.data.size}
          totalElements={products.data.totalElements}
          totalPages={products.data.totalPages}
          disabled={products.isFetching}
          onPageChange={(next) => updateParams({ page: pageToParam(next) })}
        />
      </div>
    )
  }

  return (
    <div className="space-y-5">
      <PageHeader
        icon="bag"
        title="Products"
        description="Your catalog. New products start as drafts; publish them to show them to customers."
        actions={
          notActive ? undefined : (
            <div className="flex flex-wrap items-center gap-2">
              <ExportButton
                path="/vendor/products/export"
                label="products"
                params={{ status, q: debouncedQuery || undefined }}
                total={products.data?.totalElements}
              />
              <Link to="/vendor/products/new" className={PRIMARY_LINK}>
                <Icon name="plus" className="size-4" />
                Add product
              </Link>
            </div>
          )
        }
      />

      {notActive ? (
        <VendorNotActiveNotice />
      ) : (
        <>
          <ProductsOverview
            counts={counts}
            blocked={gaps.blocked?.size}
            publishedUnpriced={gaps.publishedUnpriced}
            publishedOutOfStock={gaps.publishedOutOfStock}
          />

          <section aria-label="Your catalog" className="rounded-2xl border border-slate-200/80 bg-white p-3 shadow-sm shadow-slate-900/[0.03] sm:p-5">
            <Tabs
              label="Product status"
              tabs={tabsWithCounts(counts)}
              value={status ?? 'ALL'}
              onChange={(next) => updateParams({ status: next === 'ALL' ? null : next, page: null }, { replace: true })}
            >
              <div className="space-y-4">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
                  <label className="relative block w-full sm:max-w-sm">
                    <span className="sr-only">Search your products</span>
                    <Icon name="search" className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-slate-400" />
                    <Input
                      type="search"
                      value={queryText}
                      placeholder="Search by name, brand or SKU"
                      className="pl-9"
                      onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
                    />
                  </label>
                  <div className="flex items-center justify-between gap-3 sm:ml-auto">
                    {products.data && (
                      <p className="text-sm text-slate-500" aria-live="polite">
                        <span className="font-semibold text-slate-900 tabular-nums">{products.data.totalElements}</span>{' '}
                        product{products.data.totalElements === 1 ? '' : 's'}
                        {debouncedQuery && <> for “{debouncedQuery}”</>}
                      </p>
                    )}
                    {!phone && <LayoutSwitch value={layout} onChange={(next) => updateParams({ view: next === 'grid' ? 'grid' : null }, { replace: true })} />}
                  </div>
                </div>
                {content}
              </div>
            </Tabs>
          </section>
        </>
      )}
    </div>
  )
}
