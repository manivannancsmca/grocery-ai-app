import { useState } from 'react'
import { AdminComplaintCard } from '../../../features/complaints/AdminComplaintCard'
import {
  COMPLAINT_CATEGORIES,
  COMPLAINT_CATEGORY_INFO,
  COMPLAINT_STATUS_LABELS,
  COMPLAINTS_PAGE_SIZE,
  type ComplaintStatus,
  isComplaintCategory,
  isComplaintStatus,
} from '../../../features/complaints/api'
import { useAdminComplaints } from '../../../features/complaints/hooks'
import { ApiError } from '../../../shared/api/problemDetail'
import { useAuth } from '../../../shared/auth/useAuth'
import {
  Alert,
  Badge,
  Button,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Skeleton,
  type TabItem,
  Tabs,
  TextField,
} from '../../../shared/components/ui'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'

/** The status tabs; `ALL` asks without a status. Without `?status=` the queue opens on Open. */
type StatusTab = ComplaintStatus | 'ALL'
const TAB_ORDER: readonly StatusTab[] = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'ALL']

const EMPTY_TEXT: Record<StatusTab, { title: string; description: string }> = {
  OPEN: { title: 'No open complaints', description: 'Nothing is waiting. New complaints from customers appear here.' },
  IN_PROGRESS: { title: 'Nothing in progress', description: 'Start an open complaint to show the customer someone is on it.' },
  RESOLVED: { title: 'No resolved complaints yet', description: 'Resolved complaints and their answers appear here.' },
  ALL: { title: 'No complaints yet', description: 'Complaints customers raise appear here.' },
}

function tabLabel(tab: StatusTab): string {
  return tab === 'ALL' ? 'All' : COMPLAINT_STATUS_LABELS[tab]
}

/** Two placeholder cards while the first page loads. */
function SkeletonCards() {
  return (
    <div className="grid gap-4 lg:grid-cols-2" aria-hidden="true">
      {[0, 1].map((index) => (
        <div key={index} className="rounded-2xl bg-white p-5 ring-1 ring-slate-200/80">
          <div className="flex items-start gap-3">
            <Skeleton className="size-11 rounded-xl" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-3 w-1/3" />
            </div>
          </div>
          <Skeleton className="mt-4 h-7 w-2/3 rounded-full" />
          <Skeleton className="mt-3 h-16 w-full rounded-xl" />
          <Skeleton className="mt-4 h-10 w-40 rounded-xl" />
        </div>
      ))}
    </div>
  )
}

/**
 * `/admin/complaints` — customers' complaints, newest first: status tabs (Open by default), a category filter and a
 * search by complaint or order number, all in the URL. Every admin may read; super, operations and support admins
 * start and resolve complaints.
 */
export function AdminComplaintsPage() {
  const { can } = useAuth()
  const canHandle = can('complaints.handle')
  const [searchParams, updateParams] = useSearchParamsUpdater()

  const statusParam = searchParams.get('status')
  const tab: StatusTab = statusParam === 'ALL' ? 'ALL' : isComplaintStatus(statusParam) ? statusParam : 'OPEN'
  const categoryParam = searchParams.get('category')
  const category = isComplaintCategory(categoryParam) ? categoryParam : undefined
  const searchText = searchParams.get('q') ?? ''
  const q = useDebouncedValue(searchText.trim(), 300)
  const page = pageFromParam(searchParams.get('page'))

  const complaints = useAdminComplaints({ status: tab === 'ALL' ? undefined : tab, category, q: q || undefined, page, size: COMPLAINTS_PAGE_SIZE })
  // Why the last Start / Resolve did not go through (someone else was quicker); shown above the refreshed list.
  const [conflict, setConflict] = useState<string | null>(null)

  const activeFilters = (category ? 1 : 0) + (searchText.trim() ? 1 : 0)
  const refreshing = complaints.isFetching && complaints.isPlaceholderData
  // The selected tab shows how many complaints match (not while the previous answer is still on screen).
  const total = complaints.data && !complaints.isPlaceholderData ? complaints.data.totalElements : undefined
  const tabs: TabItem<StatusTab>[] = TAB_ORDER.map((id) => ({
    id,
    label:
      id === tab && total !== undefined ? (
        <>
          {tabLabel(id)}
          <Badge tone={id === 'OPEN' && total > 0 ? 'amber' : 'gray'}>{total.toLocaleString('en-IN')}</Badge>
        </>
      ) : (
        tabLabel(id)
      ),
  }))

  let content
  if (complaints.isPending) {
    content = (
      <>
        <span className="sr-only" role="status">
          Loading complaints
        </span>
        <SkeletonCards />
      </>
    )
  } else if (complaints.isError) {
    content = (
      <ErrorState
        title="Could not load complaints"
        message={complaints.error.message}
        correlationId={complaints.error instanceof ApiError ? complaints.error.correlationId : null}
        onRetry={() => void complaints.refetch()}
      />
    )
  } else if (complaints.data.content.length === 0) {
    content =
      activeFilters > 0 ? (
        <EmptyState title="No complaints match" description="Try another category or search, or clear the filters." />
      ) : (
        <EmptyState title={EMPTY_TEXT[tab].title} description={EMPTY_TEXT[tab].description} />
      )
  } else {
    content = (
      <div>
        <ul aria-label="Complaints" aria-busy={refreshing} className={cn('grid gap-4 transition-opacity lg:grid-cols-2', refreshing && 'opacity-60')}>
          {complaints.data.content.map((complaint, index) => (
            // A short stagger so the cards arrive one after another.
            <li key={complaint.id} className="min-w-0 animate-fade-up motion-reduce:animate-none" style={{ animationDelay: `${Math.min(index, 8) * 45}ms` }}>
              <AdminComplaintCard complaint={complaint} canHandle={canHandle} onConflict={setConflict} />
            </li>
          ))}
        </ul>
        <Pagination
          page={complaints.data.page}
          size={complaints.data.size}
          totalElements={complaints.data.totalElements}
          totalPages={complaints.data.totalPages}
          disabled={complaints.isFetching}
          onPageChange={(next) => updateParams({ page: pageToParam(next) })}
        />
      </div>
    )
  }

  return (
    <div>
      <PageHeader
        icon="chat"
        title="Complaints"
        description="What customers complained about, newest first. Start one to show you are on it, then resolve it with an answer the customer reads."
      />
      <Tabs
        label="Complaint status"
        tabs={tabs}
        value={tab}
        onChange={(next) => {
          setConflict(null)
          updateParams({ status: next === 'OPEN' ? null : next, page: null }, { replace: true })
        }}
      >
        <div className="space-y-5">
          <FilterBar activeCount={activeFilters} onClear={() => updateParams({ category: null, q: null, page: null })}>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <SelectField label="Category" value={category ?? ''} onChange={(event) => updateParams({ category: event.target.value, page: null })}>
                <option value="">All categories</option>
                {COMPLAINT_CATEGORIES.map((value) => (
                  <option key={value} value={value}>
                    {COMPLAINT_CATEGORY_INFO[value].label}
                  </option>
                ))}
              </SelectField>
              <TextField
                label="Search"
                type="search"
                value={searchText}
                autoComplete="off"
                placeholder="Complaint or order number"
                onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
              />
            </div>
          </FilterBar>

          {!canHandle && (
            <p className="flex items-center gap-2 text-sm text-slate-500">
              <Icon name="info" className="size-4" />
              Your role can view complaints; support, operations and super admins start and resolve them.
            </p>
          )}

          {conflict && (
            <Alert
              tone="warning"
              role="alert"
              actions={
                <Button variant="secondary" onClick={() => setConflict(null)}>
                  Dismiss
                </Button>
              }
            >
              {conflict}
            </Alert>
          )}

          {content}
        </div>
      </Tabs>
    </div>
  )
}
