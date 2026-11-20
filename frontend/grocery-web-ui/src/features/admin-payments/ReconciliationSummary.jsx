import { useId } from 'react'
import { Link } from 'react-router'
import { Badge, Skeleton, StatCard } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { formatCount, formatDateTime, formatRelativeTime, humanizeToken } from '../../shared/utils/format'
import { adminOrderLink } from '../admin-orders/labels'
import { usePaymentReconciliation } from './hooks'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */

/**
 * What the provider reported for a recovered payment (Phase 10 §3: captured, authorized → captured, failed).
 * @type {Record<string, string>}
 */
const OUTCOME_LABELS = {
  CAPTURED: 'Captured',
  AUTHORIZED: 'Authorized, then captured',
  FAILED: 'Failed attempt recorded',
}
/** @type {Record<string, BadgeTone>} */
const OUTCOME_TONES = { CAPTURED: 'green', AUTHORIZED: 'green', FAILED: 'red' }

/**
 * Phase 10: the payment reconciliation job's last run (`GET /api/admin/payments/reconciliation`) — how many pending
 * payments it checked at Razorpay, how many it recovered (lost verify call and webhook) and how many checks failed.
 * The three counts are colourful stat tiles; a load error stays a quiet line (the payments list below still works).
 */
export function ReconciliationSummary() {
  const titleId = useId()
  const reconciliation = usePaymentReconciliation()
  const data = reconciliation.data

  return (
    <section
      aria-labelledby={titleId}
      className="animate-fade-up rounded-2xl border border-slate-200/70 bg-gradient-to-br from-white via-white to-sky-50/60 p-4 shadow-sm shadow-slate-900/[0.04] motion-reduce:animate-none sm:p-5"
    >
      <div className="flex items-start gap-3">
        <span
          aria-hidden="true"
          className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-sky-50 to-blue-50 ring-1 ring-sky-100"
        >
          <Icon name="scale" className="size-5" />
        </span>
        <div className="min-w-0">
          <h2 id={titleId} className="text-base font-semibold text-slate-900">
            Payment reconciliation
          </h2>
          <p className="mt-0.5 text-sm text-slate-500">
            {data ? (
              data.lastRunAt ? (
                <>
                  Last run{' '}
                  <time
                    dateTime={data.lastRunAt}
                    title={formatDateTime(data.lastRunAt)}
                    className="font-medium text-slate-700"
                  >
                    {formatRelativeTime(data.lastRunAt)}
                  </time>
                  . Pending payments are checked at Razorpay, so a lost webhook still confirms the order.
                </>
              ) : (
                'Not run yet. Pending payments are checked at Razorpay every few minutes.'
              )
            ) : reconciliation.isError ? (
              <>
                Reconciliation status is unavailable: {reconciliation.error.message}{' '}
                <button
                  type="button"
                  className="font-medium text-emerald-700 hover:text-emerald-800 hover:underline"
                  onClick={() => void reconciliation.refetch()}
                >
                  Try again
                </button>
              </>
            ) : (
              'Checking the last run…'
            )}
          </p>
        </div>
      </div>

      {data ? (
        <dl aria-label="Last reconciliation run" className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
          <StatCard
            label="Checked"
            value={formatCount(data.checked)}
            icon="search"
            accent="sky"
            hint="Pending payments looked up"
          />
          <StatCard
            label="Recovered"
            value={formatCount(data.recovered)}
            icon="checkCircle"
            accent="emerald"
            valueClassName={data.recovered > 0 ? 'text-emerald-700' : undefined}
            hint="Confirmed without a webhook"
          />
          <StatCard
            label="Failures"
            value={formatCount(data.failures)}
            icon="alertCircle"
            accent="rose"
            valueClassName={data.failures > 0 ? 'text-red-700' : undefined}
            hint="Checks that did not succeed"
          />
        </dl>
      ) : (
        reconciliation.isPending && (
          <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
            {[0, 1, 2].map((index) => (
              <Skeleton key={index} className="h-[4.5rem] rounded-2xl" />
            ))}
          </div>
        )
      )}

      {data && data.lastRecovered.length > 0 && (
        <details className="group mt-4 rounded-xl border border-slate-200/70 bg-white/80 px-3 py-2">
          <summary className="flex cursor-pointer list-none items-center gap-1.5 text-sm font-medium text-emerald-700 hover:text-emerald-800 [&::-webkit-details-marker]:hidden">
            <Icon
              name="chevronRight"
              className="size-4 transition-transform duration-200 group-open:rotate-90 motion-reduce:transition-none"
            />
            Last recovered payments ({data.lastRecovered.length})
          </summary>
          <ul aria-label="Recovered payments" className="mt-2 divide-y divide-slate-100 text-sm">
            {data.lastRecovered.map((entry) => (
              <li key={`${entry.orderId}-${entry.at}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2">
                <Link to={adminOrderLink(entry)} className="font-mono font-semibold text-emerald-700 hover:underline">
                  {entry.orderNumber}
                </Link>
                <Badge tone={OUTCOME_TONES[entry.outcome] ?? 'gray'} dot>
                  {OUTCOME_LABELS[entry.outcome] ?? humanizeToken(entry.outcome)}
                </Badge>
                <span className="text-xs text-slate-500">{formatDateTime(entry.at)}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}
