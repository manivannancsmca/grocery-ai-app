import { useState } from 'react'
import { APPROVAL_STATUSES, type ApprovalScope, type ApprovalView, isApprovalStatus } from '../../../features/ai/api'
import { ApprovalStatusBadge } from '../../../features/ai/ApprovalCard'
import { ApprovalDrawer } from '../../../features/ai/ApprovalDrawer'
import { useApprovals } from '../../../features/ai/hooks'
import { agentLabel, APPROVAL_STATUS_LABELS, approvalActionLabel, approvalStatusLabel } from '../../../features/ai/labels'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
  Badge,
  Button,
  type Column,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
  type TabItem,
  Tabs,
  type TableSort,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon, type IconName } from '../../../shared/icons'
import { formatDateTime, formatMoney } from '../../../shared/utils/format'

type ApprovalsTab = 'to-decide' | 'all'

/** A picture per proposed action (decorative: the action is written under the title); a sparkle for anything else. */
const ACTION_ICONS: Record<string, IconName> = {
  PLACE_ORDER: 'cart',
  CANCEL_ORDER: 'ban',
  ADMIN_CANCEL_ORDER: 'ban',
  REFUND_PAYMENT: 'return',
  SUSPEND_VENDOR: 'store',
  SUSPEND_RESTAURANT: 'cloche',
  REQUEST_RETURN: 'return',
  ADJUST_STOCK: 'box',
  CHANGE_PRICE: 'tag',
  SEND_PURCHASE_ORDER: 'clipboard',
  SET_MENU_ITEM_AVAILABILITY: 'cloche',
  ACCEPT_STORE_ORDER: 'checkCircle',
  REJECT_STORE_ORDER: 'ban',
  REASSIGN_DELIVERY: 'scooter',
}

const columns = (onView: (approval: ApprovalView) => void): Column<ApprovalView>[] => [
  {
    key: 'requested',
    header: 'Requested',
    sortValue: (approval) => approval.createdAt,
    cell: (approval) => <span className="whitespace-nowrap">{formatDateTime(approval.createdAt)}</span>,
  },
  {
    key: 'request',
    header: 'Request',
    sortValue: (approval) => approval.summary.title,
    cell: (approval) => (
      <div className="flex min-w-0 items-start gap-3">
        <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-violet-50 to-sky-50 ring-1 ring-violet-100">
          <Icon name={ACTION_ICONS[approval.action] ?? 'sparkle'} className="size-5" />
        </span>
        <div className="min-w-0">
          <p className="font-medium break-words text-slate-900">{approval.summary.title}</p>
          <p className="text-xs text-slate-500">
            {approvalActionLabel(approval.action)} · {agentLabel(approval.agent)}
          </p>
        </div>
      </div>
    ),
  },
  {
    key: 'amount',
    header: 'Amount',
    className: 'text-right',
    sortValue: (approval) => approval.summary.amount,
    cell: (approval) =>
      approval.summary.amount !== null ? (
        <span className="font-semibold whitespace-nowrap text-slate-900 tabular-nums">
          {formatMoney(approval.summary.amount, approval.summary.currency ?? 'INR')}
        </span>
      ) : (
        <span className="text-slate-400">—</span>
      ),
  },
  {
    key: 'requester',
    header: 'Requested by',
    hideOnMobile: true,
    sortValue: (approval) => approval.requestedBy,
    cell: (approval) => (
      <span className="block max-w-32 truncate font-mono text-xs" title={approval.requestedBy}>
        {approval.requestedBy}
      </span>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    sortValue: (approval) => approvalStatusLabel(approval.status),
    cell: (approval) => (
      <span className="flex flex-col items-start gap-1">
        <ApprovalStatusBadge status={approval.status} />
        {approval.fourEyes && <Badge tone="violet">Four-eyes</Badge>}
      </span>
    ),
  },
  {
    key: 'when',
    header: 'Expires / decided',
    hideOnMobile: true,
    sortValue: (approval) => (approval.status === 'PENDING_APPROVAL' ? approval.expiresAt : approval.decidedAt),
    cell: (approval) => (
      <span className="text-xs whitespace-nowrap text-slate-500">
        {approval.status === 'PENDING_APPROVAL' ? `Expires ${formatDateTime(approval.expiresAt)}` : approval.decidedAt ? formatDateTime(approval.decidedAt) : '—'}
      </span>
    ),
  },
  {
    key: 'actions',
    header: <span className="sr-only">Actions</span>,
    className: 'text-right',
    // One action per row: a visible button, highlighted as "Decide" when this admin may decide it, otherwise "View".
    cell: (approval) => {
      const decide = approval.canDecide && approval.status === 'PENDING_APPROVAL'
      return (
        <Button variant={decide ? 'primary' : 'secondary'} size="sm" onClick={() => onView(approval)} aria-label={`Open request ${approval.summary.title}`}>
          <Icon name={decide ? 'scale' : 'eye'} className="size-4" />
          {decide ? 'Decide' : 'View'}
        </Button>
      )
    },
  },
]

/**
 * `/admin/ai/approvals` (Phase 11 §9) — actions the business assistant proposed: "To decide" (`scope=to-decide`,
 * the pending ones this admin may decide) and "All" (`scope=mine`: requested by or for this admin, any status,
 * with a status filter). `?id=` opens one (the notifications link here); the drawer approves (optional comment),
 * rejects (comment required) or cancels the admin's own request.
 */
/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['requested', 'request', 'requester'] as const

export function AiApprovalsPage() {
  const [sort, setSort] = useState<TableSort | null>(null)
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [selected, setSelected] = useState<ApprovalView | null>(null)
  const tab: ApprovalsTab = searchParams.get('tab') === 'all' ? 'all' : 'to-decide'
  const statusParam = searchParams.get('status')
  const status = tab === 'all' && isApprovalStatus(statusParam) ? statusParam : undefined
  const page = pageFromParam(searchParams.get('page'))
  const linkedId = searchParams.get('id') || null
  const drawerId = selected?.id ?? linkedId

  const scope: ApprovalScope = tab === 'all' ? 'mine' : 'to-decide'
  const approvals = useApprovals({ scope, status, page, size: DEFAULT_PAGE_SIZE })
  // The "To decide" count: the list itself on that tab, a one-row page on the other.
  const toDecide = useApprovals({ scope: 'to-decide', page: 0, size: 1 }, tab === 'all')
  const toDecideCount = (tab === 'all' ? toDecide.data?.totalElements : approvals.data?.totalElements) ?? 0

  function closeDrawer() {
    setSelected(null)
    if (linkedId) updateParams({ id: null }, { replace: true })
  }

  // The pictures are decorative: a tab's name stays its text (and count).
  const tabs: readonly TabItem<ApprovalsTab>[] = [
    {
      id: 'to-decide',
      label: (
        <>
          <Icon name="hourglass" className="size-4" />
          To decide {toDecideCount > 0 && <Badge tone="amber">{toDecideCount}</Badge>}
        </>
      ),
    },
    {
      id: 'all',
      label: (
        <>
          <Icon name="list" className="size-4" />
          All
        </>
      ),
    },
  ]

  return (
    <div className="space-y-4">
      <PageHeader
        icon="sparkleQuestion"
        title="AI approvals"
        description="Refunds, cancellations and suspensions the business assistant proposed. Nothing runs until an eligible admin approves it."
        actions={
          <ExportButton
            path="/approvals/export"
            label="AI approvals"
            params={{ scope, status }}
            total={approvals.data?.totalElements}
            sort={sort}
            sortable={EXPORT_SORTS}
          />
        }
      />
      <Tabs
        label="AI approval lists"
        tabs={tabs}
        value={tab}
        onChange={(next) => updateParams({ tab: next === 'to-decide' ? null : next, status: null, page: null }, { replace: true })}
      >
        <div className="space-y-4">
          {tab === 'all' ? (
            // "Clear all" drops the status only: the tab and an open request (?id=) stay.
            <FilterBar activeCount={status ? 1 : 0} onClear={() => updateParams({ status: null, page: null })}>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                <SelectField label="Status" value={status ?? ''} onChange={(event) => updateParams({ status: event.target.value, page: null })}>
                  <option value="">All statuses</option>
                  {APPROVAL_STATUSES.map((value) => (
                    <option key={value} value={value}>
                      {APPROVAL_STATUS_LABELS[value]}
                    </option>
                  ))}
                </SelectField>
              </div>
            </FilterBar>
          ) : (
            <div className="flex items-start gap-3 rounded-2xl bg-gradient-to-r from-violet-50 via-white to-sky-50 p-4 ring-1 ring-violet-100 ring-inset">
              <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-violet-100">
                <Icon name="shield" className="size-5" />
              </span>
              <p className="text-sm text-slate-600">
                Pending requests your role may decide, newest first. Four-eyes requests are decided by a second admin, never by the one who
                proposed them.
              </p>
            </div>
          )}

          {approvals.isPending ? (
            <div className="flex justify-center rounded-2xl bg-white/60 py-12 ring-1 ring-slate-200/70">
              <Spinner size="lg" label="Loading approval requests" />
            </div>
          ) : approvals.isError ? (
            <ErrorState
              title="Could not load approval requests"
              message={approvals.error.message}
              correlationId={approvals.error instanceof ApiError ? approvals.error.correlationId : null}
              onRetry={() => void approvals.refetch()}
            />
          ) : (
            <div>
              <DataTable
                caption={tab === 'all' ? 'All approval requests' : 'Approval requests to decide'}
                columns={columns(setSelected)}
                rows={approvals.data.content}
                getRowKey={(approval) => approval.id}
                sort={sort}
                onSortChange={setSort}
                fetching={approvals.isFetching && approvals.isPlaceholderData}
                empty={
                  <EmptyState
                    title={tab === 'all' ? 'No approval requests' : 'Nothing to decide'}
                    description={
                      tab === 'all'
                        ? status
                          ? 'No requests with this status.'
                          : 'Requests you made through the assistant, or that are for you, appear here.'
                        : 'Requests that need your decision appear here, and in your notifications.'
                    }
                  />
                }
              />
              <Pagination
                page={approvals.data.page}
                size={approvals.data.size}
                totalElements={approvals.data.totalElements}
                totalPages={approvals.data.totalPages}
                disabled={approvals.isFetching}
                onPageChange={(next) => updateParams({ page: pageToParam(next) })}
              />
            </div>
          )}
        </div>
      </Tabs>

      {drawerId && <ApprovalDrawer key={drawerId} id={drawerId} initial={selected ?? undefined} onClose={closeDrawer} />}
    </div>
  )
}
