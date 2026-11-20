import { type ReactNode, useState } from 'react'
import { Link } from 'react-router'
import { INVOCATION_STATUSES, type ToolInvocationView } from '../../../features/ai/api'
import { useAiSummary, useToolInvocations } from '../../../features/ai/hooks'
import {
  AGENT_LABELS,
  AGENT_TOOLS,
  agentLabel,
  blockReasonLabel,
  INVOCATION_STATUS_LABELS,
  invocationStatusLabel,
  invocationStatusTone,
  toolLabel,
} from '../../../features/ai/labels'
import { HealthPill } from '../../../features/system/SystemStatusGrid'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import { roleLabel } from '../../../shared/auth/roles'
import {
  Alert,
  Badge,
  type Column,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
  SummaryStrip,
  TextField,
  type TableSort,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon, type IconName } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { formatDateTime } from '../../../shared/utils/format'
import { isIsoDay } from '../../../shared/utils/indiaDate'

const AGENTS = Object.keys(AGENT_LABELS)

/** The model's arguments as one line of JSON (objects) or as sent (text). */
function argumentsText(value: unknown): string | null {
  if (value === null || value === undefined) return null
  if (typeof value === 'string') return value || null
  try {
    const text = JSON.stringify(value)
    return text === '{}' ? null : text
  } catch {
    return String(value)
  }
}

const columns: Column<ToolInvocationView>[] = [
  {
    key: 'when',
    header: 'Time',
    sortValue: (row) => row.createdAt,
    cell: (row) => <span className="whitespace-nowrap">{formatDateTime(row.createdAt)}</span>,
  },
  {
    key: 'tool',
    header: 'Tool',
    sortValue: (row) => toolLabel(row.tool),
    cell: (row) => {
      const args = argumentsText(row.arguments)
      return (
        <div className="max-w-64 min-w-0">
          <p className="font-medium text-slate-900">{toolLabel(row.tool)}</p>
          <p className="font-mono text-xs text-slate-500">
            {row.tool ?? '—'} · {agentLabel(row.agent)}
          </p>
          {args && (
            <p className="mt-0.5 truncate rounded-md bg-slate-50 px-1.5 py-0.5 font-mono text-xs text-slate-500 ring-1 ring-slate-100 ring-inset" title={args}>
              {args}
            </p>
          )}
        </div>
      )
    },
  },
  {
    key: 'user',
    header: 'User',
    hideOnMobile: true,
    sortValue: (row) => row.userId,
    cell: (row) => (
      <div className="min-w-0">
        <p className="max-w-32 truncate font-mono text-xs" title={row.userId}>
          {row.userId}
        </p>
        {row.roles.length > 0 && <p className="text-xs text-slate-500">{row.roles.map(roleLabel).join(', ')}</p>}
      </div>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    sortValue: (row) => invocationStatusLabel(row.status),
    cell: (row) => (
      <span className="flex flex-col items-start gap-1">
        <Badge tone={invocationStatusTone(row.status)} dot>
          {invocationStatusLabel(row.status)}
        </Badge>
        {row.httpStatus !== null && <span className="text-xs text-slate-500 tabular-nums">HTTP {row.httpStatus}</span>}
      </span>
    ),
  },
  {
    key: 'result',
    header: 'Reason / result',
    cell: (row) => {
      const reason = row.status === 'BLOCKED' ? blockReasonLabel(row.reason) : row.reason
      return (
        <div className="max-w-72 min-w-0 text-xs">
          {reason && (
            <p className={cn('font-semibold', row.status === 'BLOCKED' ? 'text-red-800' : 'text-red-700')}>
              {reason}
              {row.status === 'BLOCKED' && row.reason && row.reason !== reason && (
                <>
                  {' '}
                  <span className="font-mono font-normal">({row.reason})</span>
                </>
              )}
            </p>
          )}
          {row.resultSummary && (
            <p className="line-clamp-2 text-slate-600" title={row.resultSummary}>
              {row.resultSummary}
            </p>
          )}
          {row.approvalId && (
            <Link
              to={`/admin/ai/approvals?tab=all&id=${encodeURIComponent(row.approvalId)}`}
              className="font-semibold text-emerald-700 underline-offset-2 hover:text-emerald-800 hover:underline"
            >
              Approval request →
            </Link>
          )}
        </div>
      )
    },
  },
  {
    key: 'latency',
    header: 'Latency',
    hideOnMobile: true,
    className: 'text-right',
    sortValue: (row) => row.latencyMs,
    cell: (row) => <span className="text-xs whitespace-nowrap tabular-nums">{row.latencyMs !== null ? `${row.latencyMs} ms` : '—'}</span>,
  },
]

/** A small caption above a row of numbers (the row itself is named for screen readers). */
function StripTitle({ icon, children }: { icon: IconName; children: ReactNode }) {
  return (
    <p className="mb-2 flex items-center gap-1.5 text-xs font-semibold tracking-wide text-slate-500 uppercase">
      <Icon name={icon} className="size-4" />
      {children}
    </p>
  )
}

function SummarySection({ date }: { date: string | null }) {
  const summary = useAiSummary(date)
  const data = summary.data
  return (
    <section
      aria-label="AI activity"
      className="space-y-5 rounded-3xl border border-slate-200/70 bg-gradient-to-br from-white via-white to-violet-50/60 p-4 shadow-sm shadow-slate-900/[0.04] sm:p-5"
    >
      <div className="flex flex-wrap items-center gap-2 text-sm text-slate-600">
        <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-violet-50 to-sky-50 ring-1 ring-violet-100">
          <Icon name="sparkle" className="size-5" />
        </span>
        {data && (
          <>
            <HealthPill tone={data.available ? 'green' : 'red'}>{data.available ? 'Model available' : 'Model unavailable'}</HealthPill>
            <span className="inline-flex min-w-0 flex-wrap items-center gap-x-1 rounded-full bg-white px-3 py-1 text-xs text-slate-600 ring-1 ring-slate-200 ring-inset">
              <Icon name="bolt" className="size-3.5" />
              {data.provider}
              {data.model ? ` · ${data.model}` : ''}
              {data.avgModelLatencyMs !== null && <span>· average model latency {Math.round(data.avgModelLatencyMs).toLocaleString('en-IN')} ms</span>}
            </span>
          </>
        )}
        {summary.isFetching && <Spinner size="sm" label="Loading the summary" />}
      </div>
      {summary.isError && (
        <Alert tone="danger" role="alert" title="Could not load the AI summary">
          {summary.error.message}
        </Alert>
      )}
      <div>
        <StripTitle icon="chat">Usage</StripTitle>
        <SummaryStrip
          label="Assistant usage"
          loading={summary.isPending}
          items={[
            { label: 'Conversations', value: data?.conversations, icon: 'chat' },
            { label: 'Messages', value: data?.messages, icon: 'mail' },
            { label: 'Tool calls', value: data?.toolCalls, icon: 'settings' },
            { label: 'Model errors', value: data?.modelErrors, tone: 'red', icon: 'alertCircle' },
          ]}
        />
      </div>
      <div>
        <StripTitle icon="shield">Guardrails</StripTitle>
        <SummaryStrip
          label="Blocked and failed tool calls"
          loading={summary.isPending}
          items={[
            { label: 'Blocked attempts', value: data?.blocked, tone: 'red', icon: 'ban' },
            { label: 'Failed tool calls', value: data?.failed, tone: 'red', icon: 'xCircle' },
            { label: 'Approvals requested', value: data?.approvals.requested, icon: 'sparkleQuestion' },
            { label: 'Approvals pending', value: data?.approvals.pending, tone: 'amber', icon: 'hourglass' },
          ]}
        />
      </div>
      <div>
        <StripTitle icon="sparkleCheck">Approval outcomes</StripTitle>
        <SummaryStrip
          label="Approval outcomes"
          loading={summary.isPending}
          items={[
            { label: 'Approvals completed', value: data?.approvals.completed, icon: 'sparkleCheck' },
            { label: 'Approvals rejected', value: data?.approvals.rejected, icon: 'sparklePause' },
            { label: 'Approvals expired', value: data?.approvals.expired, tone: 'amber', icon: 'clock' },
            { label: 'Approvals failed', value: data?.approvals.failed, tone: 'red', icon: 'sparkleCross' },
          ]}
        />
      </div>
      {data && data.approvals.cancelled > 0 && (
        <p className="text-xs text-slate-500">
          {data.approvals.cancelled === 1 ? '1 request was' : `${data.approvals.cancelled} requests were`} cancelled by the requester.
        </p>
      )}
    </section>
  )
}

/**
 * `/admin/ai/audit` (Phase 11 §6, §9) — the day's assistant activity (`/api/admin/ai/summary`) and every audited tool
 * call (`/api/admin/ai/tool-invocations`), newest first: filters agent, tool, status, user and dates live in the
 * URL; blocked attempts (ToolGuard) are highlighted with their reason. Any admin may look.
 */
/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['when', 'user', 'status'] as const

export function AiAuditPage() {
  const [sort, setSort] = useState<TableSort | null>(null)
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const dayParam = searchParams.get('day')
  const day = isIsoDay(dayParam) ? dayParam : null
  const agentParam = searchParams.get('agent')
  const agent = agentParam && AGENTS.includes(agentParam) ? agentParam : undefined
  const tool = searchParams.get('tool') || undefined
  const statusParam = searchParams.get('status')
  const status = statusParam && (INVOCATION_STATUSES as readonly string[]).includes(statusParam) ? statusParam : undefined
  const userText = searchParams.get('userId') ?? ''
  const userId = useDebouncedValue(userText.trim(), 300) || undefined
  const fromParam = searchParams.get('from')
  const from = isIsoDay(fromParam) ? fromParam : undefined
  const toParam = searchParams.get('to')
  const to = isIsoDay(toParam) ? toParam : undefined
  const page = pageFromParam(searchParams.get('page'))

  const invocations = useToolInvocations({ agent, tool, status, userId, from, to, page, size: DEFAULT_PAGE_SIZE })
  const filtered = Boolean(agent || tool || status || userId || from || to)
  const tools = agent ? (AGENT_TOOLS[agent] ?? []) : [...new Set(Object.values(AGENT_TOOLS).flat())]
  const activeFilters = [agent, tool, status, userText.trim(), from, to].filter(Boolean).length

  return (
    <div className="space-y-6">
      <PageHeader
        icon="sparkleCheck"
        title="AI audit"
        description="What the assistants did: every tool call with its outcome, the attempts ToolGuard blocked, and the approvals."
        actions={
          <TextField
            label="Summary day"
            type="date"
            value={day ?? ''}
            onChange={(event) => updateParams({ day: event.target.value }, { replace: true })}
          />
        }
      />
      <SummarySection date={day} />

      <section aria-labelledby="ai-audit-invocations" className="space-y-4">
        <div className="flex items-center gap-3">
          <span aria-hidden="true" className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-50 to-teal-50 ring-1 ring-emerald-100">
            <Icon name="settings" className="size-5" />
          </span>
          <h2 id="ai-audit-invocations" className="text-lg font-semibold text-slate-900">
            Tool calls
          </h2>
          <ExportButton
            className="ml-auto"
            path="/admin/ai/tool-invocations/export"
            label="tool calls"
            params={{ agent, tool, status, userId, from, to }}
            total={invocations.data?.totalElements}
            sort={sort}
            sortable={EXPORT_SORTS}
          />
        </div>
        {/* "Clear all" resets the list's filters; the summary day above stays. */}
        <FilterBar
          activeCount={activeFilters}
          onClear={() => updateParams({ agent: null, tool: null, status: null, userId: null, from: null, to: null, page: null })}
          note="Dates are days in India time, both inclusive."
        >
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-6">
            <SelectField
              label="Assistant"
              value={agent ?? ''}
              onChange={(event) => updateParams({ agent: event.target.value, tool: null, page: null })}
            >
              <option value="">All assistants</option>
              {AGENTS.map((value) => (
                <option key={value} value={value}>
                  {agentLabel(value)}
                </option>
              ))}
            </SelectField>
            <SelectField label="Tool" value={tool ?? ''} onChange={(event) => updateParams({ tool: event.target.value, page: null })}>
              <option value="">All tools</option>
              {tools.map((value) => (
                <option key={value} value={value}>
                  {toolLabel(value)} ({value})
                </option>
              ))}
            </SelectField>
            <SelectField label="Status" value={status ?? ''} onChange={(event) => updateParams({ status: event.target.value, page: null })}>
              <option value="">All statuses</option>
              {INVOCATION_STATUSES.map((value) => (
                <option key={value} value={value}>
                  {INVOCATION_STATUS_LABELS[value]}
                </option>
              ))}
            </SelectField>
            <TextField
              label="User ID"
              type="search"
              value={userText}
              placeholder="Exact user ID"
              autoComplete="off"
              onChange={(event) => updateParams({ userId: event.target.value, page: null }, { replace: true })}
            />
            <TextField label="From" type="date" value={from ?? ''} max={to} onChange={(event) => updateParams({ from: event.target.value, page: null })} />
            <TextField label="To" type="date" value={to ?? ''} min={from} onChange={(event) => updateParams({ to: event.target.value, page: null })} />
          </div>
          <p className="mt-3 flex items-center gap-2 text-xs text-slate-500">
            <span aria-hidden="true" className="size-3 rounded-sm bg-red-100 ring-1 ring-red-300" />
            Blocked attempts are highlighted.
          </p>
        </FilterBar>

        {invocations.isPending ? (
          <div className="flex justify-center rounded-2xl bg-white/60 py-12 ring-1 ring-slate-200/70">
            <Spinner size="lg" label="Loading tool calls" />
          </div>
        ) : invocations.isError ? (
          <ErrorState
            title="Could not load the tool calls"
            message={invocations.error.message}
            correlationId={invocations.error instanceof ApiError ? invocations.error.correlationId : null}
            onRetry={() => void invocations.refetch()}
          />
        ) : (
          <div>
            <DataTable
              caption="Tool calls"
              columns={columns}
              rows={invocations.data.content}
              getRowKey={(row) => row.id}
              sort={sort}
              onSortChange={setSort}
              fetching={invocations.isFetching && invocations.isPlaceholderData}
              // Blocked attempts: a red tint and a red bar on the left (the `!` wins over the table's hover bar).
              rowClassName={(row) => (row.status === 'BLOCKED' ? 'bg-red-50/70 hover:bg-red-50 [&>td:first-child]:shadow-[inset_3px_0_0_#f43f5e]!' : undefined)}
              empty={<EmptyState title="No tool calls" description={filtered ? 'Nothing matches these filters.' : 'Tool calls of the assistants appear here.'} />}
            />
            <Pagination
              page={invocations.data.page}
              size={invocations.data.size}
              totalElements={invocations.data.totalElements}
              totalPages={invocations.data.totalPages}
              disabled={invocations.isFetching}
              onPageChange={(next) => updateParams({ page: pageToParam(next) })}
            />
          </div>
        )}
      </section>
    </div>
  )
}
