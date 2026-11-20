import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { BarList, ChartPanel, DonutChart, Meter, TrendChart } from '../../shared/components/charts'
import { ErrorState, Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { formatCount, formatMoney, formatPercent } from '../../shared/utils/format'
import { formatShortDay } from '../../shared/utils/indiaDate'
import { useMyMenu } from '../menu/hooks'
import { useInventorySummary } from '../vendor-inventory/hooks'
import { useSalesReport } from './hooks'
import { lastDays } from './range'

/** @typedef {import('../store-orders/api').StoreKind} StoreKind */
/** @typedef {import('./api').SalesReport} SalesReport */

/** Chart colours: the brand green for what went well, rose for refusals, amber for missed, slate for the rest. */
const COLORS = {
  received: '#94a3b8',
  accepted: '#059669',
  rejected: '#e11d48',
  missed: '#f59e0b',
  other: '#cbd5e1',
  sales: '#0d9488',
}

/** @type {Record<StoreKind, { items: string, reportsPath: string }>} */
const NOUNS = {
  VENDOR: { items: 'products', reportsPath: '/vendor/reports' },
  RESTAURANT: { items: 'dishes', reportsPath: '/restaurant/reports' },
}

/**
 * How the orders of the period ended, as parts that add up to the orders received: accepted (also those cancelled
 * later), refused by the store, refused automatically (not answered in time), and the rest — still waiting for an
 * answer, or cancelled before one.
 * @param {SalesReport} report
 */
function outcomes(report) {
  const refused = Math.max(0, report.ordersRejected - report.autoRejected)
  const rest = Math.max(0, report.ordersReceived - report.ordersAccepted - report.ordersRejected)
  return [
    { key: 'accepted', label: 'Accepted', value: report.ordersAccepted, color: COLORS.accepted },
    { key: 'rejected', label: 'Refused by you', value: refused, color: COLORS.rejected },
    { key: 'missed', label: 'Not answered in time', value: report.autoRejected, color: COLORS.missed },
    { key: 'other', label: 'Waiting or cancelled', value: rest, color: COLORS.other },
  ]
}

/** Vendors: how many products have healthy stock, are running low or are out (from the inventory summary). */
function StockHealthPanel() {
  const summary = useInventorySummary()
  const data = summary.data
  return (
    <ChartPanel title="Stock health" icon="box">
      {data ? (
        <DonutChart
          title={`${formatCount(data.products)} products`}
          variant="pie"
          totalLabel="products"
          parts={[
            {
              key: 'healthy',
              label: 'Enough stock',
              value: Math.max(0, data.inStock - data.lowStock),
              color: COLORS.accepted,
            },
            { key: 'low', label: 'Running low', value: data.lowStock, color: COLORS.missed },
            { key: 'out', label: 'Out of stock', value: data.outOfStock, color: COLORS.rejected },
          ]}
          format={(value) => formatCount(value)}
        />
      ) : summary.isError ? (
        <p className="text-sm text-slate-600">Stock figures are unavailable right now.</p>
      ) : (
        <Skeleton className="h-40 rounded-xl" />
      )}
    </ChartPanel>
  )
}

/** Restaurants: dishes on the menu that can be ordered now versus sold out. */
function MenuAvailabilityPanel() {
  const menu = useMyMenu()
  const items = menu.data?.categories.flatMap((category) => category.items) ?? []
  const soldOut = items.filter((item) => !item.available).length
  return (
    <ChartPanel title="Menu availability" icon="menuBook">
      {menu.data ? (
        <DonutChart
          title={`${formatCount(items.length)} dishes`}
          variant="pie"
          totalLabel="dishes"
          parts={[
            { key: 'available', label: 'Available now', value: items.length - soldOut, color: COLORS.accepted },
            { key: 'soldOut', label: 'Sold out', value: soldOut, color: COLORS.rejected },
          ]}
          format={(value) => formatCount(value)}
        />
      ) : menu.isError ? (
        <p className="text-sm text-slate-600">The menu figures are unavailable right now.</p>
      ) : (
        <Skeleton className="h-40 rounded-xl" />
      )}
    </ChartPanel>
  )
}

/** @param {{ kind: StoreKind, report: SalesReport }} props */
function Charts({ kind, report }) {
  const categories = report.daily.map((day) => day.date)
  const currency = report.currency
  return (
    <div className="space-y-4">
      <div className="grid gap-4 lg:grid-cols-5">
        <ChartPanel title="Orders by day" icon="chart" className="lg:col-span-3">
          <TrendChart
            title={`${formatCount(report.ordersReceived)} orders received`}
            subtitle={`${formatCount(report.ordersAccepted)} accepted`}
            categories={categories}
            formatCategory={formatShortDay}
            kind="column"
            series={[
              {
                key: 'received',
                name: 'Received',
                values: report.daily.map((day) => day.ordersReceived),
                color: COLORS.received,
              },
              {
                key: 'accepted',
                name: 'Accepted',
                values: report.daily.map((day) => day.ordersAccepted),
                color: COLORS.accepted,
              },
            ]}
            format={(value) => formatCount(value)}
            height={220}
          />
        </ChartPanel>
        <ChartPanel title="How orders ended" icon="checkCircle" className="lg:col-span-2">
          <DonutChart
            title="Orders received in these 7 days"
            variant="pie"
            totalLabel="orders"
            parts={outcomes(report)}
            format={(value) => formatCount(value)}
            subtitle={report.ordersReceived === 0 ? 'None yet' : undefined}
          />
        </ChartPanel>
      </div>

      <div className="grid gap-4 lg:grid-cols-5">
        <ChartPanel title="Sales by day" icon="savings" className="lg:col-span-3">
          <TrendChart
            title={formatMoney(report.grossSales, currency)}
            subtitle="Item totals of accepted orders (delivery fees excluded)"
            categories={categories}
            formatCategory={formatShortDay}
            kind="column"
            series={[
              { key: 'sales', name: 'Sales', values: report.daily.map((day) => day.grossSales), color: COLORS.sales },
            ]}
            format={(value) => formatMoney(value, currency)}
            formatTick={(value) => `₹${Math.round(value)}`}
            height={200}
          />
        </ChartPanel>
        <ChartPanel title={`Top ${NOUNS[kind].items}`} icon="trendUp" className="lg:col-span-2">
          {report.topItems.length === 0 ? (
            <p className="text-sm text-slate-600">Your best sellers appear here after your first accepted order.</p>
          ) : (
            <BarList
              title="By quantity sold"
              items={report.topItems.map((item) => ({
                key: item.itemId,
                label: item.name,
                detail: formatMoney(item.sales, currency),
                value: item.quantity,
              }))}
              format={(value) => `${formatCount(value)} sold`}
            />
          )}
        </ChartPanel>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        {kind === 'VENDOR' ? <StockHealthPanel /> : <MenuAvailabilityPanel />}
        <ChartPanel title="Week in numbers" icon="receipt">
          <dl className="grid grid-cols-2 gap-3">
            {[
              { label: 'Items sold', value: formatCount(report.itemsSold) },
              { label: 'Average order', value: formatMoney(report.averageOrderValue, currency) },
              { label: 'Refused', value: formatCount(report.ordersRejected) },
              { label: 'Cancelled', value: formatCount(report.ordersCancelled) },
            ].map((fact) => (
              <div key={fact.label} className="rounded-xl bg-slate-50 px-3 py-2.5 ring-1 ring-slate-100 ring-inset">
                <dt className="text-xs font-medium text-slate-500">{fact.label}</dt>
                <dd className="text-lg font-bold text-slate-900 tabular-nums">{fact.value}</dd>
              </div>
            ))}
          </dl>
          {report.ordersReceived > 0 && (
            <Meter
              className="mt-4"
              label="Acceptance rate"
              ratio={report.acceptanceRate}
              valueText={formatPercent(report.acceptanceRate)}
              tone={report.acceptanceRate >= 0.9 ? 'good' : report.acceptanceRate >= 0.7 ? 'warning' : 'critical'}
            />
          )}
        </ChartPanel>
      </div>
    </div>
  )
}

/**
 * The last 7 days on a store's dashboard (vendor or restaurant), from its own sales report: orders and sales by day
 * (bar charts), how orders ended (pie), the best sellers (bars), and stock health (vendors) or menu availability
 * (restaurants) as a pie. The full reports page has other periods.
 * @param {{ kind: StoreKind }} props
 */
export function StoreDashboardCharts({ kind }) {
  const range = lastDays(7)
  const report = useSalesReport(kind, range, true)

  return (
    <section aria-labelledby="store-charts-title" className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex items-center gap-3">
          <span
            aria-hidden="true"
            className="flex size-10 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-emerald-100"
          >
            <Icon name="chart" className="size-5" />
          </span>
          <div>
            <h2 id="store-charts-title" className="text-lg font-bold text-slate-900">
              Last 7 days at a glance
            </h2>
            <p className="text-sm text-slate-600">
              {formatShortDay(range.from)} – {formatShortDay(range.to)} · India time
            </p>
          </div>
        </div>
        <Link to={NOUNS[kind].reportsPath} className="text-sm font-semibold text-emerald-700 hover:text-emerald-800">
          Full sales reports →
        </Link>
      </div>
      {report.isPending ? (
        <div role="status" aria-label="Loading your charts" className="grid gap-4 lg:grid-cols-2">
          <Skeleton className="h-64 rounded-2xl" />
          <Skeleton className="h-64 rounded-2xl" />
        </div>
      ) : report.isError ? (
        <ErrorState
          title="Could not load your charts"
          message={report.error.message}
          correlationId={report.error instanceof ApiError ? report.error.correlationId : null}
          onRetry={() => void report.refetch()}
        />
      ) : (
        <Charts kind={kind} report={report.data} />
      )}
    </section>
  )
}
