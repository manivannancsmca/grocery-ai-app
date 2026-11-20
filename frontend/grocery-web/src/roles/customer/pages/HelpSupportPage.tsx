import { useState } from 'react'
import { COMPLAINTS_PAGE_SIZE, type ComplaintStatus, isComplaintStatus } from '../../../features/complaints/api'
import { ComplaintCard } from '../../../features/complaints/ComplaintCard'
import { useMyComplaints } from '../../../features/complaints/hooks'
import { RaiseComplaintForm } from '../../../features/complaints/RaiseComplaintForm'
import { type ChipTab, ChipTabs } from '../../../features/orders/ChipTabs'
import { ApiError } from '../../../shared/api/problemDetail'
import { Button, EmptyState, ErrorState, Pagination, Skeleton } from '../../../shared/components/ui'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { ShopperPageHeader } from '../components/ShopperPageHeader'

type StatusFilter = 'ALL' | ComplaintStatus

const FILTERS: readonly ChipTab<StatusFilter>[] = [
  { id: 'ALL', label: 'All', icon: 'list' },
  { id: 'OPEN', label: 'Open', icon: 'clock' },
  { id: 'IN_PROGRESS', label: 'In progress', icon: 'hourglass' },
  { id: 'RESOLVED', label: 'Resolved', icon: 'checkCircle' },
]

const EMPTY_TEXT: Record<StatusFilter, { title: string; description: string }> = {
  ALL: {
    title: 'No complaints yet',
    description: 'If something goes wrong with an order or the app, tell us with the form — our answer appears here.',
  },
  OPEN: { title: 'No open complaints', description: 'Complaints waiting for our support team show up here.' },
  IN_PROGRESS: { title: 'Nothing in progress', description: 'Complaints our support team is working on show up here.' },
  RESOLVED: { title: 'No answered complaints yet', description: 'Once we answer a complaint, you can read our answer here.' },
}

/** Placeholder cards while the first page loads. */
function ComplaintsSkeleton() {
  return (
    <div role="status" aria-label="Loading your complaints" className="space-y-3">
      {[0, 1].map((index) => (
        <div key={index} className="rounded-2xl bg-white p-5 ring-1 ring-slate-200/80">
          <div className="flex items-start gap-3">
            <Skeleton className="size-11 rounded-xl" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-1/3" />
              <Skeleton className="h-3 w-1/2" />
            </div>
          </div>
          <Skeleton className="mt-4 h-14 w-full rounded-xl" />
          <Skeleton className="mt-3 h-3 w-2/3" />
        </div>
      ))}
    </div>
  )
}

/**
 * `/account/complaints` — Help & support: raise a complaint (about an order or the app; `?orderId=` preselects an
 * order) and follow every complaint with its status and our answer (`?status=` and `?page=` in the URL).
 */
export function HelpSupportPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const statusParam = searchParams.get('status')
  const filter: StatusFilter = isComplaintStatus(statusParam) ? statusParam : 'ALL'
  const page = pageFromParam(searchParams.get('page'))
  const preselectedOrderId = searchParams.get('orderId') ?? ''
  const complaints = useMyComplaints({ status: filter === 'ALL' ? undefined : filter, page, size: COMPLAINTS_PAGE_SIZE })
  // The complaint just sent is highlighted at the top of the list.
  const [newComplaintId, setNewComplaintId] = useState<string | null>(null)

  // The selected tab shows how many complaints it has (not while the previous tab's answer is still on screen).
  const total = complaints.data && !complaints.isPlaceholderData ? complaints.data.totalElements : undefined
  const tabs = FILTERS.map((tab) => (tab.id === filter && total !== undefined ? { ...tab, count: total } : tab))

  let content
  if (complaints.isPending) {
    content = <ComplaintsSkeleton />
  } else if (complaints.isError) {
    content = (
      <ErrorState
        title="Could not load your complaints"
        message={complaints.error.message}
        correlationId={complaints.error instanceof ApiError ? complaints.error.correlationId : null}
        onRetry={() => void complaints.refetch()}
      />
    )
  } else if (complaints.data.content.length === 0) {
    const text = EMPTY_TEXT[filter]
    content = (
      <EmptyState
        title={text.title}
        description={text.description}
        action={
          filter !== 'ALL' && (
            <Button variant="secondary" onClick={() => updateParams({ status: null, page: null }, { replace: true })}>
              <Icon name="list" className="size-4" />
              Show all complaints
            </Button>
          )
        }
      />
    )
  } else {
    content = (
      <>
        <ul aria-label="Your complaints" className={cn('animate-fade-up space-y-3 transition-opacity motion-reduce:animate-none', complaints.isPlaceholderData && 'opacity-60')}>
          {complaints.data.content.map((complaint) => (
            <ComplaintCard key={complaint.id} complaint={complaint} highlighted={complaint.id === newComplaintId} />
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
      </>
    )
  }

  return (
    <div className="w-full min-w-0">
      <ShopperPageHeader
        icon="chat"
        eyebrow="Account"
        title="Help & support"
        description="Tell us what went wrong. Our support team answers every complaint on this page."
      />
      {/* Wide screens: the form beside the list. Narrower: the form first, then the list. */}
      <div className="grid gap-6 xl:grid-cols-[minmax(0,28rem)_minmax(0,1fr)] xl:items-start">
        <RaiseComplaintForm
          // A new `?orderId=` link starts the form afresh with that order.
          key={preselectedOrderId}
          initialOrderId={preselectedOrderId}
          onCreated={(created) => {
            setNewComplaintId(created.id)
            // Show the new complaint at the top: first page of all complaints; the order link has done its job.
            updateParams({ status: null, page: null, orderId: null }, { replace: true })
          }}
        />
        <section aria-labelledby="your-complaints-heading" className="min-w-0">
          <h2 id="your-complaints-heading" className="mb-3 flex items-center gap-2 text-base font-bold text-slate-900">
            <Icon name="clipboard" className="size-5" />
            Your complaints
          </h2>
          <ChipTabs
            label="Filter complaints"
            tabs={tabs}
            value={filter}
            onChange={(next) => updateParams({ status: next === 'ALL' ? null : next, page: null }, { replace: true })}
          >
            {content}
          </ChipTabs>
        </section>
      </div>
    </div>
  )
}
