import { useState } from 'react'
import { APPROVAL_STATUSES, isApprovalStatus } from '../../../features/ai/api'
import { ApprovalCard, ApprovalStatusBadge } from '../../../features/ai/ApprovalCard'
import { ApprovalDrawer } from '../../../features/ai/ApprovalDrawer'
import { useAssistant } from '../../../features/ai/assistantContext'
import { useApprovals } from '../../../features/ai/hooks'
import { APPROVAL_STATUS_LABELS, approvalActionLabel } from '../../../features/ai/labels'
import { ApiError } from '../../../shared/api/problemDetail'
import {
  Button,
  DataTable,
  EmptyState,
  ErrorState,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { AssistantIllustration } from '../../../shared/illustrations'
import { formatDateTime, formatMoney } from '../../../shared/utils/format'

/** @typedef {import('../../../features/ai/api').ApprovalView} ApprovalView */
/** @typedef {import('../../../shared/components/ui').Column} Column */
/** @typedef {import('../../../shared/icons').IconName} IconName */

const HISTORY_PAGE_SIZE = 10

/** @type {ReadonlyArray<{ icon: IconName, text: string }>} */
const HOW_IT_WORKS = [
  { icon: 'chat', text: 'You ask in the chat' },
  { icon: 'sparkle', text: 'The assistant prepares it' },
  { icon: 'shield', text: 'Nothing runs until you confirm' },
]

/**
 * @param {(approval: ApprovalView) => void} onView
 * @returns {Column<ApprovalView>[]}
 */
const columns = (onView) => [
  {
    key: 'when',
    header: 'Requested',
    cell: (approval) => <span className="whitespace-nowrap">{formatDateTime(approval.createdAt)}</span>,
  },
  {
    key: 'request',
    header: 'Action',
    cell: (approval) => (
      <div className="min-w-0">
        <p className="font-medium break-words text-slate-900">{approval.summary.title}</p>
        <p className="text-xs text-slate-500">{approvalActionLabel(approval.action)}</p>
      </div>
    ),
  },
  {
    key: 'amount',
    header: 'Amount',
    className: 'text-right',
    hideOnMobile: true,
    cell: (approval) =>
      approval.summary.amount !== null ? (
        <span className="whitespace-nowrap tabular-nums">
          {formatMoney(approval.summary.amount, approval.summary.currency ?? 'INR')}
        </span>
      ) : (
        <span className="text-slate-400">—</span>
      ),
  },
  { key: 'status', header: 'Status', cell: (approval) => <ApprovalStatusBadge status={approval.status} /> },
  {
    key: 'actions',
    header: <span className="sr-only">Actions</span>,
    className: 'text-right',
    cell: (approval) => (
      <Button
        variant="secondary"
        size="sm"
        onClick={() => onView(approval)}
        aria-label={`View ${approval.summary.title}`}
      >
        View
      </Button>
    ),
  },
]

/**
 * @typedef {object} ShownCards
 * @property {ApprovalView[] | undefined} source The list answer the cards were last merged from.
 * @property {ApprovalView[]} cards
 */

/**
 * The pending list, plus the cards shown earlier on this visit that have left it since (just confirmed, declined,
 * expired): they stay with their outcome — e.g. the placed order's link — until the page is left.
 * @param {ApprovalView[] | undefined} current
 * @returns {ApprovalView[]}
 */
function useShownCards(current) {
  const [shown, setShown] = useState({ source: undefined, cards: [] })
  if (current && current !== shown.source) {
    const ids = new Set(current.map((approval) => approval.id))
    setShown({ source: current, cards: [...current, ...shown.cards.filter((approval) => !ids.has(approval.id))] })
  }
  return current && current !== shown.source ? current : shown.cards
}

function PendingApprovals() {
  const pending = useApprovals({ scope: 'mine', status: 'PENDING_APPROVAL', page: 0, size: 20 })
  const cards = useShownCards(pending.data?.content)
  const { available, openAssistant } = useAssistant()
  const waiting = pending.data?.totalElements ?? 0

  return (
    <section aria-labelledby="assistant-pending" className="animate-fade-up space-y-4">
      <div className="flex items-center gap-3">
        <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-amber-100">
          <Icon name="clock" className="size-[1.15rem]" />
        </span>
        <h2 id="assistant-pending" className="text-lg font-semibold text-slate-900">
          Waiting for you
        </h2>
        {waiting > 0 && (
          <span className="rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-semibold text-amber-800 tabular-nums">
            {waiting} to confirm
          </span>
        )}
      </div>
      {pending.isPending ? (
        <div className="flex justify-center py-8">
          <Spinner label="Loading pending actions" />
        </div>
      ) : pending.isError ? (
        <ErrorState
          title="Could not load your pending actions"
          message={pending.error.message}
          correlationId={pending.error instanceof ApiError ? pending.error.correlationId : null}
          onRetry={() => void pending.refetch()}
        />
      ) : cards.length === 0 ? (
        <EmptyState
          title="Nothing to confirm"
          description="When the assistant prepares an order or a cancellation, you confirm it here or in the chat."
          action={
            available ? (
              <Button variant="secondary" onClick={openAssistant}>
                <Icon name="sparkle" className="size-4" />
                Ask the assistant
              </Button>
            ) : undefined
          }
        />
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {cards.map((approval) => (
            <ApprovalCard key={approval.id} approval={approval} />
          ))}
        </div>
      )}
    </section>
  )
}

/**
 * `/assistant/approvals` (Phase 11 §9) — "Assistant actions": orders and cancellations the shopping assistant
 * prepared for this customer. Pending ones can be confirmed ("Confirm order") or declined ("Not now") right here;
 * the history lists every request with its outcome. `?id=` opens one (the notifications link here).
 */
export function AssistantApprovalsPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [selected, setSelected] = useState(null)
  const statusParam = searchParams.get('status')
  const status = isApprovalStatus(statusParam) ? statusParam : undefined
  const page = pageFromParam(searchParams.get('page'))
  const linkedId = searchParams.get('id') || null
  const drawerId = selected?.id ?? linkedId
  const history = useApprovals({ scope: 'mine', status, page, size: HISTORY_PAGE_SIZE })

  function closeDrawer() {
    setSelected(null)
    if (linkedId) updateParams({ id: null }, { replace: true })
  }

  return (
    <div className="space-y-8">
      <div>
        <PageHeader
          eyebrow="Your account"
          title="Assistant actions"
          description="The shopping assistant never orders or cancels on its own: you confirm each action, and only then does it run."
          illustration={<AssistantIllustration />}
          actions={
            <ExportButton
              path="/approvals/export"
              label="assistant actions"
              params={{ scope: 'mine', status }}
              total={history.data?.totalElements}
            />
          }
        />
        <ol aria-label="How assistant actions work" className="-mt-2 grid gap-2 sm:grid-cols-3">
          {HOW_IT_WORKS.map((step, index) => (
            <li
              key={step.text}
              className="flex items-center gap-3 rounded-xl border border-slate-200/80 bg-white px-3.5 py-3 text-sm text-slate-700 shadow-xs"
            >
              <span
                aria-hidden="true"
                className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-emerald-50"
              >
                <Icon name={step.icon} className="size-4" />
              </span>
              <span>
                <span className="sr-only">Step {index + 1}: </span>
                {step.text}
              </span>
            </li>
          ))}
        </ol>
      </div>
      <PendingApprovals />

      <section aria-labelledby="assistant-history" className="animate-fade-up space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div className="flex items-center gap-3">
            <span
              aria-hidden="true"
              className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-50"
            >
              <Icon name="list" className="size-[1.15rem]" />
            </span>
            <h2 id="assistant-history" className="text-lg font-semibold text-slate-900">
              History
            </h2>
          </div>
          <SelectField
            label="Status"
            containerClassName="sm:w-56"
            value={status ?? ''}
            onChange={(event) => updateParams({ status: event.target.value, page: null })}
          >
            <option value="">All statuses</option>
            {APPROVAL_STATUSES.map((value) => (
              <option key={value} value={value}>
                {APPROVAL_STATUS_LABELS[value]}
              </option>
            ))}
          </SelectField>
        </div>
        {history.isPending ? (
          <div className="flex justify-center py-8">
            <Spinner label="Loading the history" />
          </div>
        ) : history.isError ? (
          <ErrorState
            title="Could not load the history"
            message={history.error.message}
            correlationId={history.error instanceof ApiError ? history.error.correlationId : null}
            onRetry={() => void history.refetch()}
          />
        ) : (
          <div>
            <DataTable
              caption="Assistant action history"
              columns={columns(setSelected)}
              rows={history.data.content}
              getRowKey={(approval) => approval.id}
              fetching={history.isFetching && history.isPlaceholderData}
              empty={
                <EmptyState
                  size="md"
                  title="No assistant actions yet"
                  description={
                    status ? 'None with this status.' : 'Actions the assistant prepares for you are listed here.'
                  }
                />
              }
            />
            <Pagination
              page={history.data.page}
              size={history.data.size}
              totalElements={history.data.totalElements}
              totalPages={history.data.totalPages}
              disabled={history.isFetching}
              onPageChange={(next) => updateParams({ page: pageToParam(next) })}
            />
          </div>
        )}
      </section>

      {drawerId && (
        <ApprovalDrawer key={drawerId} id={drawerId} initial={selected ?? undefined} onClose={closeDrawer} />
      )}
    </div>
  )
}
