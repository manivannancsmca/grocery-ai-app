import { useState } from 'react'
import { ApiError } from '../../shared/api/problemDetail'
import {
  Button,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
} from '../../shared/components/ui'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../shared/icons'
import { formatDateTime, formatMoney } from '../../shared/utils/format'
import { APPROVAL_STATUSES, isApprovalStatus } from './api'
import { ApprovalCard, ApprovalStatusBadge } from './ApprovalCard'
import { ApprovalDrawer } from './ApprovalDrawer'
import { useAssistant } from './assistantContext'
import { useAgents, useApprovals } from './hooks'
import { APPROVAL_STATUS_LABELS, approvalActionLabel } from './labels'

/** @typedef {import('../../shared/components/ui').Column} Column */
/** @typedef {import('./api').ApprovalView} ApprovalView */

const HISTORY_PAGE_SIZE = 10

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
 * The pending list, plus cards decided on this visit (they keep their outcome until the page is left).
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

/** @param {{ emptyDescription: string }} props */
function PendingApprovals({ emptyDescription }) {
  const pending = useApprovals({ scope: 'mine', status: 'PENDING_APPROVAL', page: 0, size: 20 })
  const cards = useShownCards(pending.data?.content)
  const { available, openAssistant } = useAssistant()

  return (
    <section aria-labelledby="role-assistant-pending" className="space-y-3">
      <h2 id="role-assistant-pending" className="text-lg font-semibold text-slate-900">
        Waiting for you
      </h2>
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
          description={emptyDescription}
          action={
            available ? (
              <Button variant="secondary" onClick={openAssistant}>
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
 * What the agent can be asked (its example prompts from `GET /api/ai/agents`), with a way to open it.
 * @param {{ agentId: string }} props
 */
function AgentIntro({ agentId }) {
  const agents = useAgents(true)
  const { available, openAssistant } = useAssistant()
  const agent = agents.data?.find((candidate) => candidate.id === agentId)
  if (!agent) return null
  return (
    <Card>
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2 text-base font-semibold text-slate-900">
            <Icon name="sparkle" className="size-5" />
            {agent.name}
          </h2>
          <p className="mt-1 text-sm text-slate-500">{agent.description}</p>
          {agent.examplePrompts.length > 0 && (
            <ul aria-label="You can ask" className="mt-3 flex flex-wrap gap-2">
              {agent.examplePrompts.map((prompt) => (
                <li
                  key={prompt}
                  className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-sm text-slate-700"
                >
                  “{prompt}”
                </li>
              ))}
            </ul>
          )}
        </div>
        {available && (
          <Button className="shrink-0" onClick={openAssistant}>
            <Icon name="sparkle" className="size-4" />
            Ask the assistant
          </Button>
        )}
      </div>
    </Card>
  )
}

/**
 * @typedef {object} RoleAssistantPageProps
 * @property {string} agentId The agent whose prompts are shown (`vendor`, `restaurant`, `delivery`).
 * @property {string} title
 * @property {string} description
 * @property {string} emptyDescription Shown when nothing waits for a decision.
 */

/**
 * Phase 12 §6 — `/vendor/assistant`, `/restaurant/assistant`, `/delivery/assistant`: what the store or delivery
 * assistant prepared. The vendor and the restaurant owner confirm their own requests here ("Confirm" / "Not
 * now"); a delivery manager decides reassignments. The history lists every request with its outcome; `?id=` opens
 * one (the notification links here).
 * @param {RoleAssistantPageProps} props
 */
export function RoleAssistantPage({ agentId, title, description, emptyDescription }) {
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
      <PageHeader icon="sparkle" title={title} description={description} />
      <AgentIntro agentId={agentId} />
      <PendingApprovals emptyDescription={emptyDescription} />

      <section aria-labelledby="role-assistant-history" className="space-y-3">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <h2 id="role-assistant-history" className="text-lg font-semibold text-slate-900">
            History
          </h2>
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
