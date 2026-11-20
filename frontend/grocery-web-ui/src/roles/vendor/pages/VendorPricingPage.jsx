import { useState } from 'react'
import { VENDOR_NOT_ACTIVE } from '../../../features/vendor-products/api'
import { ProductStatusBadge, VendorNotActiveNotice } from '../../../features/vendor-products/ProductStatusActions'
import {
  DEFAULT_APPROVAL_THRESHOLD_PERCENT,
  isPriceFilter,
  PRICE_FILTER_LABELS,
  PRICE_FILTERS,
} from '../../../features/vendor-pricing/api'
import { usePricingSummary, useVendorPrices } from '../../../features/vendor-pricing/hooks'
import { MyPriceRequests } from '../../../features/vendor-pricing/MyPriceRequests'
import { PriceHistoryDrawer } from '../../../features/vendor-pricing/PriceHistoryDrawer'
import { SetPriceDialog } from '../../../features/vendor-pricing/SetPriceDialog'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
  Badge,
  Button,
  DataTable,
  EmptyState,
  ErrorState,
  Input,
  PageHeader,
  Pagination,
  Spinner,
  SummaryStrip,
  Tabs,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { formatDateTime, formatMoney } from '../../../shared/utils/format'

/** @typedef {import('../../../features/vendor-pricing/api').PriceFilter} PriceFilter */
/** @typedef {import('../../../features/vendor-pricing/api').PriceView} PriceView */
/** @typedef {import('../../../shared/components/ui').TabItem} TabItem */

/** @typedef {'ALL' | PriceFilter | 'REQUESTS'} PricingTab */

/** @type {readonly TabItem<PricingTab>[]} */
const TABS = [
  { id: 'ALL', label: 'All' },
  ...PRICE_FILTERS.map((filter) => ({ id: filter, label: PRICE_FILTER_LABELS[filter] })),
  { id: 'REQUESTS', label: 'My requests' },
]

/** @type {Record<'ALL' | PriceFilter, { title: string, description: string }>} */
const EMPTY_TEXT = {
  ALL: { title: 'No products yet', description: 'Products you add to your catalog appear here, ready for a price.' },
  PRICED: { title: 'No priced products', description: 'Set a price to make a product purchasable.' },
  UNPRICED: { title: 'Every product has a price', description: 'Customers see a price for all your products.' },
  PENDING_APPROVAL: {
    title: 'Nothing is waiting for approval',
    description: 'Big price changes wait here until an admin decides.',
  },
}

/** @typedef {{ kind: 'price' | 'history', view: PriceView } | null} Panel */

/** `/vendor/pricing` — MRP and selling price per product, price history and the requests that need approval. */
export function VendorPricingPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const showRequests = searchParams.get('view') === 'requests'
  const filterParam = searchParams.get('filter')
  const filter = isPriceFilter(filterParam) ? filterParam : undefined
  const queryText = searchParams.get('q') ?? ''
  const debouncedQuery = useDebouncedValue(queryText.trim(), 300)
  const page = pageFromParam(searchParams.get('page'))
  const [panel, setPanel] = useState(null)

  const summary = usePricingSummary()
  const prices = useVendorPrices({ q: debouncedQuery || undefined, filter, page, size: DEFAULT_PAGE_SIZE })
  const notActive = hasErrorCode(prices.error, VENDOR_NOT_ACTIVE) || hasErrorCode(summary.error, VENDOR_NOT_ACTIVE)
  const threshold = prices.data?.content[0]?.approvalThresholdPercent ?? DEFAULT_APPROVAL_THRESHOLD_PERCENT
  const close = () => setPanel(null)

  function selectTab(next) {
    if (next === 'REQUESTS') updateParams({ view: 'requests', filter: null, q: null, page: null }, { replace: true })
    else updateParams({ view: null, filter: next === 'ALL' ? null : next, page: null }, { replace: true })
  }

  const columns = [
    {
      key: 'product',
      header: 'Product',
      cell: (row) => (
        <div className="min-w-0">
          <p className="font-medium text-slate-900">{row.productName}</p>
          <p className="truncate text-xs text-slate-500">
            {row.sku ? `SKU ${row.sku}` : 'No SKU'}
            <span className="md:hidden"> · {row.unitLabel}</span>
          </p>
          {row.productStatus !== 'PUBLISHED' && (
            <div className="mt-1">
              <ProductStatusBadge status={row.productStatus} />
            </div>
          )}
        </div>
      ),
    },
    {
      key: 'unit',
      header: 'Unit',
      hideOnMobile: true,
      cell: (row) => <span className="whitespace-nowrap">{row.unitLabel}</span>,
    },
    {
      key: 'mrp',
      header: 'MRP',
      className: 'text-right',
      hideOnMobile: true,
      cell: (row) => <span className="tabular-nums">{row.price ? formatMoney(row.price.mrp) : '—'}</span>,
    },
    {
      key: 'selling',
      header: 'Selling price',
      className: 'text-right',
      cell: (row) =>
        row.price ? (
          <span className="font-semibold text-slate-900 tabular-nums">{formatMoney(row.price.sellingPrice)}</span>
        ) : (
          <Badge tone="amber">No price</Badge>
        ),
    },
    {
      key: 'discount',
      header: 'Discount',
      className: 'text-right',
      hideOnMobile: true,
      cell: (row) =>
        row.price && row.price.discountPercent > 0 ? (
          `${row.price.discountPercent}%`
        ) : (
          <span className="text-slate-400">—</span>
        ),
    },
    {
      key: 'pending',
      header: 'Approval',
      cell: (row) =>
        row.pendingRequest ? (
          <Badge tone="amber" dot>
            Pending: {formatMoney(row.pendingRequest.requestedSellingPrice)}
          </Badge>
        ) : (
          <span className="text-slate-400">—</span>
        ),
    },
    {
      key: 'updated',
      header: 'Updated',
      hideOnMobile: true,
      cell: (row) => <span className="whitespace-nowrap">{formatDateTime(row.price?.updatedAt)}</span>,
    },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'text-right',
      cell: (row) => (
        <div className="flex flex-wrap items-center justify-end gap-2">
          <Button
            size="sm"
            variant="secondary"
            aria-label={`Set the price of ${row.productName}`}
            onClick={() => setPanel({ kind: 'price', view: row })}
          >
            Set price
          </Button>
          <Button
            size="sm"
            variant="ghost"
            aria-label={`Price history of ${row.productName}`}
            onClick={() => setPanel({ kind: 'history', view: row })}
          >
            History
          </Button>
        </div>
      ),
    },
  ]

  return (
    <div className="space-y-4">
      <PageHeader
        icon="tag"
        title="Pricing"
        description={`MRP and selling price per product. Selling-price changes of more than ${threshold}% need admin approval.`}
        actions={
          <ExportButton
            path="/vendor/prices/export"
            label="prices"
            params={{ q: debouncedQuery || undefined, filter }}
            total={prices.data?.totalElements}
          />
        }
      />

      {notActive ? (
        <VendorNotActiveNotice area="Pricing" task="set your prices" />
      ) : (
        <>
          <SummaryStrip
            label="Price summary"
            loading={summary.isPending}
            items={[
              { label: 'Products', value: summary.data?.products, icon: 'bag' },
              { label: 'Priced', value: summary.data?.priced, icon: 'tag' },
              { label: 'Unpriced', value: summary.data?.unpriced, tone: 'amber', icon: 'warning' },
              { label: 'Pending approval', value: summary.data?.pendingApproval, tone: 'amber', icon: 'hourglass' },
            ]}
          />
          <Tabs
            label="Price filter"
            tabs={TABS}
            value={showRequests ? 'REQUESTS' : (filter ?? 'ALL')}
            onChange={selectTab}
          >
            {showRequests ? (
              <MyPriceRequests />
            ) : (
              <div className="space-y-4">
                <label className="block sm:max-w-md">
                  <span className="sr-only">Search your prices</span>
                  <Input
                    type="search"
                    value={queryText}
                    placeholder="Search by product name or exact SKU"
                    onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
                  />
                </label>

                {prices.isPending ? (
                  <div className="flex justify-center py-12">
                    <Spinner size="lg" label="Loading prices" />
                  </div>
                ) : prices.isError ? (
                  <ErrorState
                    title="Could not load your prices"
                    message={prices.error.message}
                    correlationId={prices.error instanceof ApiError ? prices.error.correlationId : null}
                    onRetry={() => void prices.refetch()}
                  />
                ) : (
                  <div>
                    <DataTable
                      caption="Prices per product"
                      columns={columns}
                      rows={prices.data.content}
                      getRowKey={(row) => row.productId}
                      fetching={prices.isFetching && prices.isPlaceholderData}
                      empty={
                        debouncedQuery ? (
                          <EmptyState title="No products found" description="Try a different name, or the exact SKU." />
                        ) : (
                          <EmptyState {...EMPTY_TEXT[filter ?? 'ALL']} />
                        )
                      }
                    />
                    <Pagination
                      page={prices.data.page}
                      size={prices.data.size}
                      totalElements={prices.data.totalElements}
                      totalPages={prices.data.totalPages}
                      disabled={prices.isFetching}
                      onPageChange={(next) => updateParams({ page: pageToParam(next) })}
                    />
                  </div>
                )}
              </div>
            )}
          </Tabs>
        </>
      )}

      <SetPriceDialog price={panel?.kind === 'price' ? panel.view : null} onClose={close} />
      <PriceHistoryDrawer price={panel?.kind === 'history' ? panel.view : null} onClose={close} />
    </div>
  )
}
