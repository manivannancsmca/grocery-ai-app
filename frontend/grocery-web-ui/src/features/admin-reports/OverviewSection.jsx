/** Reports → Overview: the headline figures of the period (sales, orders, refunds, coupon discounts, new customers). */

import { StatCard } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCount, formatPercent } from '../../shared/utils/format'
import { useReportOverview } from './hooks'
import { KPI_GRID, money } from './reportFormat'
import { KpiSkeleton, QueryBody, ReportExportButton } from './ReportLayout'

/** @typedef {import('./api').ReportOverview} ReportOverview */
/** @typedef {import('./api').ReportRange} ReportRange */

/**
 * Who funded the coupon discounts. Orders placed before the funding was recorded count in the total only, so their
 * share is named rather than silently missing from the split.
 * @param {ReportOverview['couponDiscounts']} options
 * @returns {string}
 */
function couponFunding({ platform, store, total }) {
  if (platform === null && store === null) return 'Funding not recorded'
  const parts = [`Platform ${money(platform ?? 0)}`, `stores ${money(store ?? 0)}`]
  // in paise, so rounding never leaves a stray remainder
  const notRecorded = Math.round(total * 100) - Math.round((platform ?? 0) * 100) - Math.round((store ?? 0) * 100)
  if (notRecorded > 0) parts.push(`not recorded ${money(notRecorded / 100)}`)
  return parts.join(' · ')
}

/** @param {{ overview: ReportOverview }} props */
function Kpis({ overview }) {
  return (
    <dl aria-label="Key figures" className={cn('animate-fade-up motion-reduce:animate-none', KPI_GRID)}>
      <StatCard
        icon="savings"
        accent="emerald"
        label="Gross sales (GMV)"
        value={money(overview.grossSales)}
        hint="Confirmed orders"
      />
      <StatCard
        icon="trendUp"
        accent="sky"
        label="Net sales"
        value={money(overview.netSales)}
        hint="GMV minus refunds"
      />
      <StatCard icon="receipt" accent="violet" label="Orders" value={formatCount(overview.orders)} />
      <StatCard icon="chart" accent="amber" label="Average order value" value={money(overview.averageOrderValue)} />
      <StatCard
        icon="xCircle"
        accent="rose"
        label="Cancellation rate"
        value={formatPercent(overview.cancellationRate)}
        hint="Cancelled or rejected"
      />
      <StatCard icon="return" accent="teal" label="Refunds" value={money(overview.refunds)} />
      <StatCard
        icon="ticket"
        accent="violet"
        label="Coupon discounts"
        value={money(overview.couponDiscounts.total)}
        hint={couponFunding(overview.couponDiscounts)}
      />
      <StatCard
        icon="users"
        accent="emerald"
        label="New customers"
        value={formatCount(overview.newCustomers)}
        hint="First order in this period"
      />
    </dl>
  )
}

/** @param {{ range: ReportRange, enabled: boolean }} props */
export function OverviewSection({ range, enabled }) {
  const overview = useReportOverview(range, enabled)
  return (
    <section aria-labelledby="report-overview" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span
            aria-hidden="true"
            className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-50 to-sky-50 ring-1 ring-emerald-100"
          >
            <Icon name="sparkle" className="size-5" />
          </span>
          <h2 id="report-overview" className="text-lg font-semibold text-slate-900">
            Overview
          </h2>
        </div>
        <ReportExportButton report="overview" params={{ ...range }} label="Overview" range={range} />
      </div>
      <QueryBody query={overview} label="Overview" skeleton={<KpiSkeleton />}>
        {(data) => <Kpis overview={data} />}
      </QueryBody>
    </section>
  )
}
