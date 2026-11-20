import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { type CustomerProfile, customerKeys, fetchAdminCustomers } from '../../../features/customer/api'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
  Avatar,
  Button,
  type Column,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  Spinner,
  TextField,
  type TableSort,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { Icon } from '../../../shared/icons'
import { formatDate } from '../../../shared/utils/format'

const columns: Column<CustomerProfile>[] = [
  {
    key: 'name',
    header: 'Customer',
    sortValue: (customer) => customer.fullName,
    cell: (customer) => (
      <div className="flex min-w-0 items-center gap-3">
        <Avatar name={customer.fullName} size="sm" />
        <div className="min-w-0">
          <p className="font-medium text-slate-900">{customer.fullName}</p>
          <p className="truncate text-xs text-slate-500">{customer.email}</p>
        </div>
      </div>
    ),
  },
  {
    key: 'phone',
    header: 'Phone',
    sortValue: (customer) => customer.phone,
    cell: (customer) => (customer.phone ? <span className="whitespace-nowrap tabular-nums">{customer.phone}</span> : '—'),
  },
  // ISO timestamps sort correctly as text.
  { key: 'joined', header: 'Joined', hideOnMobile: true, sortValue: (customer) => customer.createdAt, cell: (customer) => formatDate(customer.createdAt) },
  {
    key: 'updated',
    header: 'Profile updated',
    hideOnMobile: true,
    sortValue: (customer) => customer.updatedAt,
    cell: (customer) => formatDate(customer.updatedAt),
  },
]

/** Read-only customer directory (any admin role). */
/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['name', 'joined', 'updated'] as const

export function CustomersPage() {
  const [sort, setSort] = useState<TableSort | null>(null)
  const [searchParams, setSearchParams] = useSearchParams()
  const queryText = searchParams.get('q') ?? ''
  const pageNumber = Number(searchParams.get('page'))
  const page = Number.isInteger(pageNumber) && pageNumber > 0 ? pageNumber - 1 : 0
  const debouncedQuery = useDebouncedValue(queryText.trim(), 300)
  const params = { query: debouncedQuery || undefined, page, size: DEFAULT_PAGE_SIZE }

  const customers = useQuery({
    queryKey: customerKeys.adminList(params),
    queryFn: () => fetchAdminCustomers(params),
    placeholderData: keepPreviousData,
  })

  function setParam(key: string, value: string | null) {
    setSearchParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (value) next.set(key, value)
        else next.delete(key)
        if (key !== 'page') next.delete('page')
        return next
      },
      { replace: true },
    )
  }

  // The search is the page's only filter; clearing it also goes back to page 1 (see setParam).
  const searching = queryText.trim() !== ''
  const clearSearch = () => setParam('q', null)

  return (
    <div className="space-y-5">
      <PageHeader
        icon="users"
        title="Customers"
        description="Customer profiles. Addresses stay private to each customer."
        actions={
          <ExportButton
            path="/admin/customers/export"
            label="customers"
            params={{ query: debouncedQuery || undefined }}
            total={customers.data?.totalElements}
            sort={sort}
            sortable={EXPORT_SORTS}
          />
        }
      />

      <FilterBar activeCount={searching ? 1 : 0} onClear={clearSearch}>
        <div className="grid grid-cols-1 gap-3 lg:grid-cols-3">
          <TextField
            label="Search customers"
            type="search"
            value={queryText}
            placeholder="Search by name, email or phone"
            containerClassName="lg:col-span-2"
            onChange={(event) => setParam('q', event.target.value)}
          />
        </div>
      </FilterBar>

      {customers.isPending ? (
        <div className="flex justify-center rounded-2xl border border-slate-200/80 bg-white py-12 shadow-sm">
          <Spinner size="lg" label="Loading customers" />
        </div>
      ) : customers.isError ? (
        <ErrorState
          title="Could not load customers"
          message={customers.error.message}
          correlationId={customers.error instanceof ApiError ? customers.error.correlationId : null}
          onRetry={() => void customers.refetch()}
        />
      ) : (
        <div>
          <DataTable
            caption="Customers"
            columns={columns}
            rows={customers.data.content}
            getRowKey={(customer) => customer.id}
            sort={sort}
            onSortChange={setSort}
            fetching={customers.isFetching && customers.isPlaceholderData}
            empty={
              <EmptyState
                title="No customers found"
                description={
                  debouncedQuery
                    ? 'No name, email or phone number matches your search. Check the spelling or try fewer words.'
                    : 'Customers appear here once they create an account.'
                }
                action={
                  searching ? (
                    <Button variant="secondary" size="sm" onClick={clearSearch}>
                      <Icon name="close" className="size-4" />
                      Clear search
                    </Button>
                  ) : undefined
                }
              />
            }
          />
          <Pagination
            page={customers.data.page}
            size={customers.data.size}
            totalElements={customers.data.totalElements}
            totalPages={customers.data.totalPages}
            disabled={customers.isFetching}
            onPageChange={(next) => setParam('page', next > 0 ? String(next + 1) : null)}
          />
        </div>
      )}
    </div>
  )
}
