import { useState } from 'react'
import { ApiError } from '../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../shared/api/types'
import {
  Avatar,
  Button,
  type Column,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  Input,
  Pagination,
  Select,
  Spinner,
  type TableSort,
} from '../../shared/components/ui'
import { ExportButton } from '../../shared/export'
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../shared/icons'
import { formatDateTime } from '../../shared/utils/format'
import { flattenTree, indentLabel } from '../catalog/categoryTree'
import { ProductImage } from '../catalog/ProductImage'
import { categoryLabel, isProductStatus, PRODUCT_STATUS_LABELS, PRODUCT_STATUSES } from '../vendor-products/api'
import { ProductStatusBadge } from '../vendor-products/ProductStatusActions'
import { AdminProductDrawer } from './AdminProductDrawer'
import type { AdminProductView } from './api'
import { useAdminCategories, useAdminProducts, useVendorOptions } from './hooks'

/** Read-only list of every vendor's products, filterable by vendor, category, status and text (in the URL). */
/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['product', 'status', 'updated'] as const

export function ProductsTab() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [selected, setSelected] = useState<AdminProductView | null>(null)
  const [sort, setSort] = useState<TableSort | null>(null)
  const vendors = useVendorOptions()
  const categories = useAdminCategories()

  const queryText = searchParams.get('q') ?? ''
  const debouncedQuery = useDebouncedValue(queryText.trim(), 300)
  const statusParam = searchParams.get('status')
  const status = isProductStatus(statusParam) ? statusParam : undefined
  const vendorId = searchParams.get('vendorId') || undefined
  const categoryId = searchParams.get('categoryId') || undefined
  const page = pageFromParam(searchParams.get('page'))

  const products = useAdminProducts({ q: debouncedQuery || undefined, status, vendorId, categoryId, page, size: DEFAULT_PAGE_SIZE })
  const activeFilters = [queryText.trim(), vendorId, categoryId, status].filter(Boolean).length

  // Removes only the filters: `?tab=` stays on Products.
  function clearFilters() {
    updateParams({ q: null, vendorId: null, categoryId: null, status: null, page: null })
  }

  const columns: Column<AdminProductView>[] = [
    {
      key: 'product',
      header: 'Product',
      sortValue: (product) => product.name,
      cell: (product) => (
        <div className="flex min-w-0 items-center gap-3">
          <ProductImage src={product.imageUrls[0]} alt="" className="size-10 shrink-0 rounded-xl border border-slate-200" iconClassName="size-5" />
          <div className="min-w-0">
            <p className="font-medium text-slate-900">{product.name}</p>
            <p className="truncate text-xs text-slate-500">{[product.brand?.name, product.unitLabel].filter(Boolean).join(' · ')}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'vendor',
      header: 'Vendor',
      sortValue: (product) => product.vendorName,
      cell: (product) => (
        <div className="flex min-w-0 items-center gap-2.5">
          <Avatar name={product.vendorName} size="sm" />
          <span className="text-slate-900">{product.vendorName}</span>
        </div>
      ),
    },
    {
      key: 'category',
      header: 'Category',
      hideOnMobile: true,
      sortValue: (product) => categoryLabel(product.category),
      cell: (product) => categoryLabel(product.category),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (product) => PRODUCT_STATUS_LABELS[product.status],
      cell: (product) => <ProductStatusBadge status={product.status} />,
    },
    {
      key: 'updated',
      header: 'Updated',
      hideOnMobile: true,
      sortValue: (product) => product.updatedAt,
      cell: (product) => <span className="whitespace-nowrap">{formatDateTime(product.updatedAt)}</span>,
    },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'text-right',
      cell: (product) => (
        <Button variant="secondary" size="sm" onClick={() => setSelected(product)} aria-label={`View ${product.name}`}>
          <Icon name="eye" className="size-4" />
          View
        </Button>
      ),
    },
  ]

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <ExportButton
          path="/admin/products/export"
          label="products"
          params={{ q: debouncedQuery || undefined, status, vendorId, categoryId }}
          total={products.data?.totalElements}
          sort={sort}
          sortable={EXPORT_SORTS}
        />
      </div>
      <FilterBar activeCount={activeFilters} onClear={clearFilters}>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <label>
            <span className="sr-only">Search products</span>
            <Input
              type="search"
              value={queryText}
              placeholder="Search name, brand or SKU"
              onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
            />
          </label>
          <label>
            <span className="sr-only">Filter by vendor</span>
            <Select value={vendorId ?? ''} onChange={(event) => updateParams({ vendorId: event.target.value, page: null })}>
              <option value="">All vendors</option>
              {(vendors.data ?? []).map((vendor) => (
                <option key={vendor.id} value={vendor.id}>
                  {vendor.name}
                </option>
              ))}
            </Select>
          </label>
          <label>
            <span className="sr-only">Filter by category</span>
            <Select value={categoryId ?? ''} onChange={(event) => updateParams({ categoryId: event.target.value, page: null })}>
              <option value="">All categories</option>
              {flattenTree(categories.data ?? []).map(({ node, depth }) => (
                <option key={node.id} value={node.id}>
                  {indentLabel(node.name, depth)}
                </option>
              ))}
            </Select>
          </label>
          <label>
            <span className="sr-only">Filter by status</span>
            <Select value={status ?? ''} onChange={(event) => updateParams({ status: event.target.value, page: null })}>
              <option value="">Active statuses</option>
              {PRODUCT_STATUSES.map((value) => (
                <option key={value} value={value}>
                  {PRODUCT_STATUS_LABELS[value]}
                </option>
              ))}
            </Select>
          </label>
        </div>
      </FilterBar>

      {products.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading products" />
        </div>
      ) : products.isError ? (
        <ErrorState
          title="Could not load products"
          message={products.error.message}
          correlationId={products.error instanceof ApiError ? products.error.correlationId : null}
          onRetry={() => void products.refetch()}
        />
      ) : (
        <div>
          <DataTable
            caption="Products"
            columns={columns}
            rows={products.data.content}
            getRowKey={(product) => product.id}
            sort={sort}
            onSortChange={setSort}
            fetching={products.isFetching && products.isPlaceholderData}
            empty={<EmptyState title="No products found" description="Try different filters." />}
          />
          <Pagination
            page={products.data.page}
            size={products.data.size}
            totalElements={products.data.totalElements}
            totalPages={products.data.totalPages}
            disabled={products.isFetching}
            onPageChange={(next) => updateParams({ page: pageToParam(next) })}
          />
        </div>
      )}

      {selected && <AdminProductDrawer id={selected.id} initial={selected} onClose={() => setSelected(null)} />}
    </div>
  )
}
