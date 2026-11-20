import { useId, useState } from 'react'
import { ApiError, hasErrorCode } from '../../shared/api/problemDetail'
import { BarList, SERIES_COLORS, TrendChart } from '../../shared/components/charts'
import {
  Button,
  Card,
  DataTable,
  EmptyState,
  ErrorState,
  PageHeader,
  Spinner,
  StatCard,
  TABLE_HEAD_CLASS,
  TABLE_HEAD_ROW_CLASS,
  TextField,
} from '../../shared/components/ui'
import { useSearchParamsUpdater } from '../../shared/hooks/useSearchParamsUpdater'
import { cn } from '../../shared/utils/cn'
import { formatCount, formatPercent, formatPrice } from '../../shared/utils/format'
import { formatLongDay, formatShortDay, indiaDay } from '../../shared/utils/indiaDate'
import { MAX_REPORT_DAYS, REPORT_ERRORS } from './api'
import { useSalesReport } from './hooks'
import { defaultReportRange, lastDays, reportRangeError } from './range'

/** @typedef {import('../store-orders/api').StoreKind} StoreKind */
/** @typedef {import('./api').SalesReport} SalesReport */

const compactMoney = new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 })

/** @param {{ report: SalesReport }} props */
function Kpis({ report }) {
  const money = (value) => formatPrice(value, report.currency)
  return (
    <dl aria-label="Sales summary" className="animate-fade-up grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <StatCard icon="receipt" accent="emerald" label="Orders received" value={formatCount(report.ordersReceived)} />
      <StatCard
        icon="checkCircle"
        accent="sky"
        label="Orders accepted"
        value={formatCount(report.ordersAccepted)}
        hint="Including ones cancelled later"
      />
      <StatCard
        icon="trendUp"
        accent="violet"
        label="Acceptance rate"
        value={formatPercent(report.acceptanceRate)}
        hint="Accepted of received"
      />
      <StatCard
        icon="ban"
        accent="amber"
        label="Rejected"
        value={formatCount(report.ordersRejected)}
        hint={`${formatCount(report.autoRejected)} automatically (not accepted in time)`}
      />
      <StatCard
        icon="xCircle"
        accent="rose"
        label="Cancelled"
        value={formatCount(report.ordersCancelled)}
        hint="By customers or support"
      />
      <StatCard
        icon="savings"
        accent="teal"
        label="Gross sales"
        value={money(report.grossSales)}
        hint="Item totals of accepted orders not cancelled"
      />
      <StatCard icon="chart" accent="emerald" label="Average order value" value={money(report.averageOrderValue)} />
      <StatCard icon="bag" accent="sky" label="Items sold" value={formatCount(report.itemsSold)} />
    </dl>
  )
}

/**
 * The bars and, for screen readers always (for everyone on request), the same numbers as a table.
 * @param {{ report: SalesReport }} props
 */
function DailyCharts({ report }) {
  const tableId = useId()
  const [showTable, setShowTable] = useState(false)
  const money = (value) => formatPrice(value, report.currency)
  return (
    <Card
      icon="chart"
      title="Day by day"
      description="By the day orders were received, India time."
      actions={
        <Button
          variant="secondary"
          size="sm"
          aria-expanded={showTable}
          aria-controls={tableId}
          onClick={() => setShowTable((shown) => !shown)}
        >
          {showTable ? 'Hide table' : 'Show as table'}
        </Button>
      }
    >
      <div className="grid gap-8 xl:grid-cols-2">
        <TrendChart
          kind="area"
          title="Gross sales"
          subtitle={`Highest day ${money(Math.max(0, ...report.daily.map((day) => day.grossSales)))}`}
          categories={report.daily.map((day) => day.date)}
          formatCategory={formatShortDay}
          series={[{ key: 'sales', name: 'Gross sales', values: report.daily.map((day) => day.grossSales) }]}
          format={money}
          formatTick={(value) => `₹${compactMoney.format(value)}`}
        />
        <TrendChart
          kind="column"
          title="Orders received and accepted"
          categories={report.daily.map((day) => day.date)}
          formatCategory={formatShortDay}
          series={[
            { key: 'received', name: 'Received', values: report.daily.map((day) => day.ordersReceived) },
            { key: 'accepted', name: 'Accepted', values: report.daily.map((day) => day.ordersAccepted) },
          ]}
          format={formatCount}
        />
      </div>
      <div
        id={tableId}
        className={showTable ? 'mt-6 max-h-96 overflow-auto rounded-lg border border-slate-200' : 'sr-only'}
      >
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <caption className="sr-only">Daily sales and orders</caption>
          <thead className={cn('sticky top-0', TABLE_HEAD_CLASS)}>
            <tr className={TABLE_HEAD_ROW_CLASS}>
              <th scope="col" className="px-3 py-2 text-left">
                Day
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                Orders received
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                Orders accepted
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                Gross sales
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {report.daily.map((day) => (
              <tr key={day.date}>
                <th scope="row" className="px-3 py-2 text-left font-normal whitespace-nowrap text-slate-700">
                  {formatLongDay(day.date)}
                </th>
                <td className="px-3 py-2 text-right tabular-nums">{formatCount(day.ordersReceived)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{formatCount(day.ordersAccepted)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{money(day.grossSales)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}

/** @param {{ report: SalesReport }} props */
function TopItems({ report }) {
  const columns = [
    { key: 'rank', header: '#', className: 'w-10 text-slate-400', cell: (item) => item.rank },
    { key: 'name', header: 'Item', cell: (item) => <span className="font-medium text-slate-900">{item.name}</span> },
    {
      key: 'quantity',
      header: 'Quantity',
      className: 'text-right',
      cell: (item) => <span className="tabular-nums">{formatCount(item.quantity)}</span>,
    },
    {
      key: 'sales',
      header: 'Sales',
      className: 'text-right',
      cell: (item) => <span className="tabular-nums">{formatPrice(item.sales, report.currency)}</span>,
    },
  ]
  return (
    <Card title="Top items" description="The five best sellers by quantity." icon="star">
      {report.topItems.length > 0 && (
        <BarList
          className="mb-6"
          title="Quantity sold"
          color={SERIES_COLORS[1]}
          format={formatCount}
          items={report.topItems.map((item) => ({ key: item.itemId, label: item.name, value: item.quantity }))}
        />
      )}
      <DataTable
        caption="Top items"
        columns={columns}
        rows={report.topItems.map((item, index) => ({ ...item, rank: index + 1 }))}
        getRowKey={(item) => item.itemId}
        empty={
          <EmptyState
            title="No items sold in this period"
            description="Accepted orders that are not cancelled count here."
          />
        }
      />
    </Card>
  )
}

/**
 * `/vendor/reports` and `/restaurant/reports` — sales between two India-time days (`?from=&to=`, default the
 * last 7 days, at most 92): KPI tiles, daily bars with a table alternative, and the top items.
 * @param {{ kind: StoreKind }} props
 */
export function StoreSalesReport({ kind }) {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [today] = useState(() => indiaDay())
  const fallback = defaultReportRange(today)
  const from = searchParams.get('from') ?? fallback.from
  const to = searchParams.get('to') ?? fallback.to
  const rangeError = reportRangeError(from, to)
  const report = useSalesReport(kind, { from, to }, rangeError === null)

  const presets = [
    { label: 'Today', range: lastDays(1, today) },
    { label: 'Last 7 days', range: lastDays(7, today) },
    { label: 'Last 30 days', range: lastDays(30, today) },
  ]

  let content
  if (rangeError) {
    content = null
  } else if (report.isPending) {
    content = (
      <div className="flex justify-center py-12">
        <Spinner size="lg" label="Loading the report" />
      </div>
    )
  } else if (report.isError) {
    content = (
      <ErrorState
        title={
          hasErrorCode(report.error, REPORT_ERRORS.invalidParameter)
            ? 'This range cannot be shown'
            : 'Could not load the report'
        }
        message={report.error.message}
        correlationId={report.error instanceof ApiError ? report.error.correlationId : null}
        onRetry={() => void report.refetch()}
      />
    )
  } else {
    const data = report.data
    content = (
      <div className={cn('space-y-6 transition-opacity', report.isPlaceholderData && 'opacity-60')}>
        <p className="text-sm text-slate-600">
          {formatLongDay(data.from)} – {formatLongDay(data.to)} · India time
        </p>
        <Kpis report={data} />
        <DailyCharts report={data} />
        <TopItems report={data} />
      </div>
    )
  }

  return (
    <div className="space-y-5">
      <PageHeader
        icon="chart"
        title="Sales reports"
        description="Orders, acceptance and sales by the day orders were received (India time)."
      />

      <section aria-label="Report period" className="space-y-2">
        <div className="flex flex-wrap items-end gap-3">
          <TextField
            label="From"
            type="date"
            value={from}
            max={today}
            containerClassName="w-44"
            onChange={(event) => updateParams({ from: event.target.value }, { replace: true })}
          />
          <TextField
            label="To"
            type="date"
            value={to}
            max={today}
            containerClassName="w-44"
            onChange={(event) => updateParams({ to: event.target.value }, { replace: true })}
          />
          <div className="flex flex-wrap gap-2">
            {presets.map((preset) => {
              const selected = preset.range.from === from && preset.range.to === to
              return (
                <Button
                  key={preset.label}
                  size="sm"
                  variant={selected ? 'primary' : 'secondary'}
                  aria-pressed={selected}
                  onClick={() => updateParams({ from: preset.range.from, to: preset.range.to })}
                >
                  {preset.label}
                </Button>
              )
            })}
          </div>
        </div>
        {rangeError ? (
          <p role="alert" className="text-sm font-medium text-red-700">
            {rangeError}
          </p>
        ) : (
          <p className="text-xs text-slate-500">Both days are included. Up to {MAX_REPORT_DAYS} days at a time.</p>
        )}
      </section>

      {content}
    </div>
  )
}
