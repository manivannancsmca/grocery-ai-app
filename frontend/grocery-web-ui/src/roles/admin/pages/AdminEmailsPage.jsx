import { useState } from 'react'
import { EMAIL_STATUSES, isEmailStatus, useEmailLog } from '../../../features/admin-emails/api'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
  Avatar,
  Badge,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
  StatCard,
  TextField,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { formatCount, formatDateTime, humanizeToken } from '../../../shared/utils/format'

/** @typedef {import('../../../features/admin-emails/api').EmailLogEntry} EmailLogEntry */
/** @typedef {import('../../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../../../shared/components/ui').Column} Column */

/** @type {Record<string, string>} */
const STATUS_LABELS = { PENDING: 'Pending', SENT: 'Sent', FAILED: 'Failed' }
/** @type {Record<string, BadgeTone>} */
const STATUS_TONES = { PENDING: 'amber', SENT: 'green', FAILED: 'red' }

/**
 * @param {string} status
 * @returns {string}
 */
function statusLabel(status) {
  return STATUS_LABELS[status] ?? humanizeToken(status)
}

/** @type {Column<EmailLogEntry>[]} */
const columns = [
  {
    key: 'to',
    header: 'To',
    sortValue: (email) => email.to,
    cell: (email) => (
      <span className="flex min-w-0 items-center gap-2.5">
        <Avatar name={email.to} size="sm" />
        <span className="min-w-0 break-all text-slate-900">{email.to}</span>
      </span>
    ),
  },
  {
    key: 'subject',
    header: 'Subject',
    sortValue: (email) => email.subject,
    cell: (email) => (
      <div className="min-w-0">
        <p className="font-medium break-words text-slate-900">{email.subject}</p>
        <p
          className="mt-0.5 inline-block rounded-md bg-slate-100 px-1.5 py-0.5 font-mono text-[11px] text-slate-600"
          title={email.type}
        >
          {humanizeToken(email.type)}
        </p>
      </div>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    sortValue: (email) => statusLabel(email.status),
    cell: (email) => (
      <div className="flex flex-col items-start gap-1">
        <Badge tone={STATUS_TONES[email.status] ?? 'gray'} dot>
          {statusLabel(email.status)}
        </Badge>
        {email.lastError && (
          <p className="max-w-64 text-xs break-words text-red-700" title={email.lastError}>
            {email.lastError}
          </p>
        )}
      </div>
    ),
  },
  {
    key: 'attempts',
    header: 'Attempts',
    className: 'text-right',
    hideOnMobile: true,
    sortValue: (email) => email.attempts,
    cell: (email) => <span className="font-medium tabular-nums">{email.attempts}</span>,
  },
  {
    key: 'when',
    header: 'Created / sent',
    hideOnMobile: true,
    sortValue: (email) => email.createdAt,
    cell: (email) => (
      <span className="text-xs whitespace-nowrap text-slate-600">
        {formatDateTime(email.createdAt)}
        <br />
        {email.sentAt ? `Sent ${formatDateTime(email.sentAt)}` : '—'}
      </span>
    ),
  },
]

/**
 * Headline numbers: how many emails match, and how many on this page failed or are still being retried.
 * @param {{ total: number, rows: readonly EmailLogEntry[], filtered: boolean }} props
 */
function EmailStats({ total, rows, filtered }) {
  const failed = rows.filter((email) => email.status === 'FAILED').length
  const pending = rows.filter((email) => email.status === 'PENDING').length
  return (
    <section aria-label="Email log summary" className="animate-fade-up motion-reduce:animate-none">
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <StatCard
          label="Emails"
          value={formatCount(total)}
          hint={filtered ? 'Matching the filters' : 'All emails'}
          icon="mail"
          accent="sky"
        />
        <StatCard
          label="Failed on this page"
          value={formatCount(failed)}
          hint="Gave up after the retries"
          icon="xCircle"
          accent="rose"
          valueClassName={failed > 0 ? 'text-rose-700' : undefined}
        />
        <StatCard
          label="Pending on this page"
          value={formatCount(pending)}
          hint="Waiting to be sent or retried"
          icon="hourglass"
          accent="amber"
          className="col-span-2 lg:col-span-1"
        />
      </dl>
    </section>
  )
}

/**
 * `/admin/emails` (Phase 12 §5) — the email log: what was emailed to whom, sent, pending (being retried) or failed
 * with the last error. Locally the emails land in Mailpit (http://localhost:8025). Filters live in the URL.
 */
/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['to', 'subject', 'status', 'attempts', 'when']

export function AdminEmailsPage() {
  const [sort, setSort] = useState(null)
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const statusParam = searchParams.get('status')
  const status = isEmailStatus(statusParam) ? statusParam : undefined
  const toText = searchParams.get('to') ?? ''
  const to = useDebouncedValue(toText.trim(), 300)
  const page = pageFromParam(searchParams.get('page'))
  const emails = useEmailLog({ status, to: to || undefined, page, size: DEFAULT_PAGE_SIZE })
  const activeFilters = (status ? 1 : 0) + (toText.trim() ? 1 : 0)

  return (
    <div className="space-y-5">
      <PageHeader
        icon="mail"
        title="Email log"
        description="Emails for orders, refunds, returns, accounts and AI approvals. Failed sends are retried after 1, 5 and 30 minutes."
        actions={
          <ExportButton
            path="/admin/notifications/emails/export"
            label="emails"
            params={{ status, to: to || undefined }}
            total={emails.data?.totalElements}
            sort={sort}
            sortable={EXPORT_SORTS}
          />
        }
      />
      <FilterBar activeCount={activeFilters} onClear={() => updateParams({ status: null, to: null, page: null })}>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <SelectField
            label="Status"
            value={status ?? ''}
            onChange={(event) => updateParams({ status: event.target.value, page: null })}
          >
            <option value="">All statuses</option>
            {EMAIL_STATUSES.map((value) => (
              <option key={value} value={value}>
                {STATUS_LABELS[value]}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Recipient"
            type="search"
            value={toText}
            placeholder="Email address"
            autoComplete="off"
            onChange={(event) => updateParams({ to: event.target.value, page: null }, { replace: true })}
          />
        </div>
      </FilterBar>

      {emails.data && (
        <EmailStats total={emails.data.totalElements} rows={emails.data.content} filtered={activeFilters > 0} />
      )}

      {emails.isPending ? (
        <div className="flex justify-center rounded-2xl border border-slate-200/80 bg-white py-12 shadow-sm">
          <Spinner size="lg" label="Loading the email log" />
        </div>
      ) : emails.isError ? (
        <ErrorState
          title="Could not load the email log"
          message={emails.error.message}
          correlationId={emails.error instanceof ApiError ? emails.error.correlationId : null}
          onRetry={() => void emails.refetch()}
        />
      ) : (
        <div>
          <DataTable
            caption="Email log"
            columns={columns}
            rows={emails.data.content}
            getRowKey={(email) => email.id ?? `${email.to}:${email.createdAt}:${email.subject}`}
            sort={sort}
            onSortChange={setSort}
            fetching={emails.isFetching && emails.isPlaceholderData}
            rowClassName={(email) => (email.status === 'FAILED' ? 'bg-red-50/40' : undefined)}
            empty={
              <EmptyState
                title="No emails"
                description={status || to ? 'Try different filters.' : 'Emails appear here as notifications are sent.'}
              />
            }
          />
          <Pagination
            page={emails.data.page}
            size={emails.data.size}
            totalElements={emails.data.totalElements}
            totalPages={emails.data.totalPages}
            disabled={emails.isFetching}
            onPageChange={(next) => updateParams({ page: pageToParam(next) })}
          />
        </div>
      )}
    </div>
  )
}
