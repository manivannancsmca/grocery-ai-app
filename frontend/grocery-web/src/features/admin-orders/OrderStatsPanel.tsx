import { type ReactNode, useId } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { DonutChart, seriesColor } from '../../shared/components/charts'
import { Button, Skeleton, Spinner, StatCard } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCount, formatDate, formatPrice } from '../../shared/utils/format'
import { ORDER_STATUSES, ORDER_TYPES, type OrderStatus } from '../orders/api'
import { ORDER_STATUS_LABELS, ORDER_TYPE_LABELS } from '../orders/labels'
import { ORDER_STATUS_ICONS } from '../orders/progress'
import { useOrderStats } from './hooks'

/**
 * The eleven statuses folded into four stages, so the split reads at a glance. Each stage keeps one colour (its
 * donut segment, and the accent of its status tiles below), in the chart palette's order.
 */
const STAGES: ReadonlyArray<{ key: string; label: string; color: string; statuses: readonly OrderStatus[] }> = [
  { key: 'open', label: 'Awaiting payment', color: seriesColor(0), statuses: ['CREATED', 'AWAITING_PAYMENT'] },
  { key: 'progress', label: 'In progress', color: seriesColor(1), statuses: ['CONFIRMED', 'ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP', 'OUT_FOR_DELIVERY'] },
  { key: 'delivered', label: 'Delivered', color: seriesColor(2), statuses: ['DELIVERED'] },
  { key: 'ended', label: 'Cancelled or failed', color: seriesColor(3), statuses: ['CANCELLED', 'REJECTED', 'DELIVERY_FAILED'] },
]

const percent = new Intl.NumberFormat('en-IN', { style: 'percent', maximumFractionDigits: 0 })

/**
 * One status: its picture, today's count over its label, and its share of the day's orders, accented in its stage's
 * colour. The label and count sit in one element, so the tile reads "Confirmed 30". Statuses with no orders recede.
 */
function StatusTile({ status, count, total }: { status: OrderStatus; count: number; total: number }) {
  const color = STAGES.find((stage) => stage.statuses.includes(status))?.color ?? seriesColor(0)
  const share = total > 0 ? count / total : 0
  const empty = count === 0
  return (
    <li
      className={cn(
        'group/status relative flex flex-col overflow-hidden rounded-xl border border-slate-200/70 bg-white py-3 pr-3 pl-4 shadow-xs transition-all duration-200',
        empty ? 'opacity-60' : 'hover:-translate-y-0.5 hover:border-slate-300 hover:shadow-md',
      )}
    >
      <span aria-hidden="true" className="absolute inset-y-0 left-0 w-1" style={{ backgroundColor: color }} />
      <div className="flex items-start gap-2.5">
        <span
          aria-hidden="true"
          className={cn('flex size-9 shrink-0 items-center justify-center rounded-lg transition-transform duration-200 group-hover/status:scale-110', empty && 'grayscale')}
          style={{ backgroundColor: `${color}1a` }}
        >
          <Icon name={ORDER_STATUS_ICONS[status]} className="size-5" />
        </span>
        <span className="flex min-w-0 flex-col-reverse text-xs font-medium text-slate-500">
          {ORDER_STATUS_LABELS[status]}{' '}
          <span className={cn('text-xl leading-tight font-bold tabular-nums', empty ? 'text-slate-400' : 'text-slate-900')}>{formatCount(count)}</span>
        </span>
      </div>
      {/* At the bottom, so the bars line up across a row even when a label wraps. */}
      <div aria-hidden="true" className="mt-auto flex items-center gap-2 pt-2.5">
        <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-slate-100">
          <span className="chart-grow-x block h-full rounded-full" style={{ width: `${share * 100}%`, backgroundColor: color }} />
        </span>
        <span className="w-8 text-right text-[11px] font-medium text-slate-400 tabular-nums">{percent.format(share)}</span>
      </div>
    </li>
  )
}

/** Today's order numbers from `GET /api/admin/stats` (India time), in the dashboard's tile style. */
export function OrderStatsPanel() {
  const titleId = useId()
  const stats = useOrderStats()
  const data = stats.data
  const loading = <Spinner size="sm" label="Loading" />
  const value = (render: (loaded: NonNullable<typeof data>) => ReactNode) => (data ? render(data) : stats.isPending ? loading : '—')

  return (
    <section aria-labelledby={titleId} className="space-y-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id={titleId} className="text-base font-semibold text-slate-900">
          Today’s orders
          {data && <span className="ml-2 text-sm font-normal text-slate-500">{formatDate(data.date)} · India time</span>}
        </h2>
        <div className="flex gap-4 text-sm font-medium">
          <Link to="/admin/orders" className="text-emerald-700 transition-colors hover:text-emerald-900">
            Orders →
          </Link>
          <Link to="/admin/payments" className="text-emerald-700 transition-colors hover:text-emerald-900">
            Payments →
          </Link>
        </div>
      </div>

      {stats.isError && !data && (
        <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
          <span>
            Could not load today’s order stats: {stats.error.message}
            {stats.error instanceof ApiError && stats.error.correlationId && (
              <span className="ml-1 font-mono text-xs text-red-600/80">Ref: {stats.error.correlationId}</span>
            )}
          </span>
          <Button variant="secondary" size="sm" onClick={() => void stats.refetch()}>
            Try again
          </Button>
        </div>
      )}

      <dl aria-label="Today’s order stats" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          icon="receipt"
          accent="emerald"
          label="Orders placed"
          value={value((loaded) => formatCount(loaded.orders))}
          hint={data && ORDER_TYPES.map((type) => `${ORDER_TYPE_LABELS[type]} ${formatCount(data.byType[type] ?? 0)}`).join(' · ')}
        />
        <StatCard
          icon="savings"
          accent="sky"
          label="Confirmed revenue"
          value={value((loaded) => formatPrice(loaded.confirmedRevenue, loaded.currency))}
          hint="Paid orders confirmed today"
        />
        <StatCard
          icon="trendUp"
          accent="violet"
          label="Average order value"
          value={value((loaded) => formatPrice(loaded.averageOrderValue, loaded.currency))}
          hint="Of confirmed orders"
        />
        <StatCard
          icon="clock"
          accent="amber"
          label="Open orders"
          value={value((loaded) => formatCount((loaded.byStatus.AWAITING_PAYMENT ?? 0) + (loaded.byStatus.CREATED ?? 0)))}
          hint="Placed today, not paid yet"
        />
      </dl>
      {/* The four stages at a glance, then every status. */}
      <div className="animate-fade-up rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm shadow-slate-900/[0.04]">
        {data ? (
          <>
            <DonutChart
              title="Where today’s orders are"
              totalLabel="orders"
              format={formatCount}
              className="max-w-xl"
              parts={STAGES.map((stage) => ({
                key: stage.key,
                label: stage.label,
                color: stage.color,
                value: stage.statuses.reduce((sum, status) => sum + (data.byStatus[status] ?? 0), 0),
              }))}
            />
            <div className="mt-6 border-t border-slate-100 pt-5">
              <h3 className="mb-3 text-xs font-semibold tracking-wider text-slate-500 uppercase">Every status, in order</h3>
              <ul aria-label="Today’s orders by status" className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-6">
                {ORDER_STATUSES.map((status) => (
                  <StatusTile key={status} status={status} count={data.byStatus[status] ?? 0} total={data.orders} />
                ))}
              </ul>
            </div>
          </>
        ) : (
          <div className="flex min-h-40 items-center justify-center">
            {stats.isPending ? <Skeleton className="size-36 rounded-full" /> : <span className="text-sm text-slate-400">—</span>}
          </div>
        )}
      </div>
    </section>
  )
}
