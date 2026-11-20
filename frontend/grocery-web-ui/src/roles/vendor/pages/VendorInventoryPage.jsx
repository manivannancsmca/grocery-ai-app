import { useState } from 'react'
import { AdjustStockDialog } from '../../../features/vendor-inventory/AdjustStockDialog'
import { isStockFilter, STOCK_FILTER_LABELS, STOCK_FILTERS } from '../../../features/vendor-inventory/api'
import { BulkUpdateDialog } from '../../../features/vendor-inventory/BulkUpdateDialog'
import { useInventorySummary, useVendorInventory } from '../../../features/vendor-inventory/hooks'
import { MovementsDrawer } from '../../../features/vendor-inventory/MovementsDrawer'
import { StockStatusBadge } from '../../../features/vendor-inventory/StockStatusBadge'
import { ThresholdDialog } from '../../../features/vendor-inventory/ThresholdDialog'
import { VENDOR_NOT_ACTIVE } from '../../../features/vendor-products/api'
import { ProductStatusBadge, VendorNotActiveNotice } from '../../../features/vendor-products/ProductStatusActions'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
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
import { formatCount, formatDateTime } from '../../../shared/utils/format'

/** @typedef {import('../../../features/vendor-inventory/api').StockFilter} StockFilter */
/** @typedef {import('../../../features/vendor-inventory/api').StockView} StockView */
/** @typedef {import('../../../shared/components/ui').TabItem} TabItem */

/** @typedef {'ALL' | StockFilter} FilterTab */

/** @type {readonly TabItem<FilterTab>[]} */
const TABS = [
  { id: 'ALL', label: 'All' },
  ...STOCK_FILTERS.map((filter) => ({ id: filter, label: STOCK_FILTER_LABELS[filter] })),
]

/** @type {Record<FilterTab, { title: string, description: string }>} */
const EMPTY_TEXT = {
  ALL: { title: 'No products yet', description: 'Products you add to your catalog appear here with 0 units on hand.' },
  LOW_STOCK: {
    title: 'Nothing is running low',
    description: 'Products whose available stock drops to their alert level show up here.',
  },
  OUT_OF_STOCK: { title: 'Nothing is out of stock', description: 'Every product has at least one unit available.' },
  IN_STOCK: { title: 'Nothing is in stock', description: 'Add stock to a product to make it available to customers.' },
}

/** @typedef {{ kind: 'adjust' | 'threshold' | 'movements', stock: StockView } | null} Panel */

/** `/vendor/inventory` — stock per product: filters, search, adjustments, alert levels, history and bulk updates. */
export function VendorInventoryPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const filterParam = searchParams.get('filter')
  const filter = isStockFilter(filterParam) ? filterParam : undefined
  const queryText = searchParams.get('q') ?? ''
  const debouncedQuery = useDebouncedValue(queryText.trim(), 300)
  const page = pageFromParam(searchParams.get('page'))
  const [panel, setPanel] = useState(null)
  const [bulkOpen, setBulkOpen] = useState(false)

  const summary = useInventorySummary()
  const stock = useVendorInventory({ q: debouncedQuery || undefined, filter, page, size: DEFAULT_PAGE_SIZE })
  const notActive = hasErrorCode(stock.error, VENDOR_NOT_ACTIVE) || hasErrorCode(summary.error, VENDOR_NOT_ACTIVE)
  const close = () => setPanel(null)

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
      key: 'onHand',
      header: 'On hand',
      className: 'text-right',
      cell: (row) => <span className="tabular-nums">{formatCount(row.onHand)}</span>,
    },
    {
      key: 'reserved',
      header: 'Reserved',
      className: 'text-right',
      hideOnMobile: true,
      cell: (row) => <span className="tabular-nums">{formatCount(row.reserved)}</span>,
    },
    {
      key: 'available',
      header: 'Available',
      className: 'text-right',
      cell: (row) => <span className="font-semibold text-slate-900 tabular-nums">{formatCount(row.available)}</span>,
    },
    {
      key: 'threshold',
      header: 'Alert level',
      className: 'text-right',
      hideOnMobile: true,
      cell: (row) =>
        row.lowStockThreshold > 0 ? (
          <span className="tabular-nums">{formatCount(row.lowStockThreshold)}</span>
        ) : (
          <span className="text-xs text-slate-400">Off</span>
        ),
    },
    {
      key: 'status',
      header: 'Status',
      cell: (row) => (
        <div className="flex flex-col items-start gap-1">
          <StockStatusBadge stock={row} />
          {row.productStatus !== 'PUBLISHED' && <ProductStatusBadge status={row.productStatus} />}
        </div>
      ),
    },
    {
      key: 'updated',
      header: 'Updated',
      hideOnMobile: true,
      cell: (row) => <span className="whitespace-nowrap">{formatDateTime(row.updatedAt)}</span>,
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
            aria-label={`Adjust stock of ${row.productName}`}
            onClick={() => setPanel({ kind: 'adjust', stock: row })}
          >
            Adjust
          </Button>
          <Button
            size="sm"
            variant="ghost"
            aria-label={`Set low-stock alert level of ${row.productName}`}
            onClick={() => setPanel({ kind: 'threshold', stock: row })}
          >
            Alert level
          </Button>
          <Button
            size="sm"
            variant="ghost"
            aria-label={`Stock history of ${row.productName}`}
            onClick={() => setPanel({ kind: 'movements', stock: row })}
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
        icon="box"
        title="Inventory"
        description="Stock is counted in sellable units. Customers can order a product only while units are available."
        actions={
          notActive ? undefined : (
            <div className="flex flex-wrap items-center gap-2">
              <ExportButton
                path="/vendor/inventory/export"
                label="inventory"
                params={{ q: debouncedQuery || undefined, filter }}
                total={stock.data?.totalElements}
              />
              <Button onClick={() => setBulkOpen(true)}>Bulk update</Button>
            </div>
          )
        }
      />

      {notActive ? (
        <VendorNotActiveNotice area="Inventory" task="manage your stock" />
      ) : (
        <>
          <SummaryStrip
            label="Stock summary"
            loading={summary.isPending}
            items={[
              { label: 'Products', value: summary.data?.products, icon: 'bag' },
              { label: 'In stock', value: summary.data?.inStock, icon: 'checkCircle' },
              { label: 'Low stock', value: summary.data?.lowStock, tone: 'amber', icon: 'warning' },
              { label: 'Out of stock', value: summary.data?.outOfStock, tone: 'red', icon: 'xCircle' },
            ]}
          />
          <Tabs
            label="Stock filter"
            tabs={TABS}
            value={filter ?? 'ALL'}
            onChange={(next) => updateParams({ filter: next === 'ALL' ? null : next, page: null }, { replace: true })}
          >
            <div className="space-y-4">
              <label className="block sm:max-w-md">
                <span className="sr-only">Search your stock</span>
                <Input
                  type="search"
                  value={queryText}
                  placeholder="Search by product name or exact SKU"
                  onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
                />
              </label>

              {stock.isPending ? (
                <div className="flex justify-center py-12">
                  <Spinner size="lg" label="Loading stock" />
                </div>
              ) : stock.isError ? (
                <ErrorState
                  title="Could not load your stock"
                  message={stock.error.message}
                  correlationId={stock.error instanceof ApiError ? stock.error.correlationId : null}
                  onRetry={() => void stock.refetch()}
                />
              ) : (
                <div>
                  <DataTable
                    caption="Stock per product"
                    columns={columns}
                    rows={stock.data.content}
                    getRowKey={(row) => row.productId}
                    fetching={stock.isFetching && stock.isPlaceholderData}
                    empty={
                      debouncedQuery ? (
                        <EmptyState title="No products found" description="Try a different name, or the exact SKU." />
                      ) : (
                        <EmptyState {...EMPTY_TEXT[filter ?? 'ALL']} />
                      )
                    }
                  />
                  <Pagination
                    page={stock.data.page}
                    size={stock.data.size}
                    totalElements={stock.data.totalElements}
                    totalPages={stock.data.totalPages}
                    disabled={stock.isFetching}
                    onPageChange={(next) => updateParams({ page: pageToParam(next) })}
                  />
                </div>
              )}
            </div>
          </Tabs>
        </>
      )}

      <AdjustStockDialog stock={panel?.kind === 'adjust' ? panel.stock : null} onClose={close} />
      <ThresholdDialog stock={panel?.kind === 'threshold' ? panel.stock : null} onClose={close} />
      <MovementsDrawer stock={panel?.kind === 'movements' ? panel.stock : null} onClose={close} />
      <BulkUpdateDialog open={bulkOpen} onClose={() => setBulkOpen(false)} />
    </div>
  )
}
