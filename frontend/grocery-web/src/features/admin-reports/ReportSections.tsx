import type { UseQueryResult } from '@tanstack/react-query'
import { type ReactNode, useId, useState } from 'react'
import { ApiError } from '../../shared/api/problemDetail'
import { BarList, SERIES_COLORS, TrendChart } from '../../shared/components/charts'
import {
  Avatar,
  Badge,
  Button,
  Card,
  type Column,
  DataTable,
  EmptyState,
  ErrorState,
  SelectField,
  Skeleton,
  Spinner,
  StatCard,
  TABLE_HEAD_CLASS,
  TABLE_HEAD_ROW_CLASS,
  TextField,
} from '../../shared/components/ui'
import { ExportButton, type ExportParams } from '../../shared/export'
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCount, formatPercent, formatPrice } from '../../shared/utils/format'
import { formatLongDay, formatShortDay } from '../../shared/utils/indiaDate'
import {
  type DailySalesRow,
  type DeliveryPerformanceRow,
  type PromotionRow,
  type PurchasingRow,
  type RatedTarget,
  REPORT_PATHS,
  type ReportName,
  type ReportOverview,
  type ReportRange,
  type StoreMetric,
  type TopProductRow,
  type TopStoreRow,
} from './api'
import {
  useDailySales,
  useDeliveryPerformance,
  usePromotionsReport,
  usePurchasingReport,
  useRatingsReport,
  useReportOverview,
  useTopProducts,
  useTopStores,
} from './hooks'

const TOP_LIMIT = 10

const money = (value: number) => formatPrice(value)
const compactMoney = new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 })
const minutes = (value: number | null) => (value === null || value === undefined ? '—' : `${value.toLocaleString('en-IN', { maximumFractionDigits: 1 })} min`)

interface ReportExportProps {
  report: ReportName
  /** The section's query: the period and its own filters (sent with `format`). */
  params: ExportParams
  /** The section's name: the menu's accessible name ("Export daily sales") and the toasts. */
  label: string
  range: ReportRange
}

/**
 * "Export" on a report section: the same numbers as CSV, Excel or PDF (`?format=`), for the chosen period and the
 * section's own filters. The download runs in the shared export tray (progress, cancel, a toast at the end). Excel and
 * PDF have headings in words with the period and the filters on top; CSV keeps the report's field names.
 */
export function ReportExportButton({ report, params, label, range }: ReportExportProps) {
  return (
    <ExportButton
      path={REPORT_PATHS[report]}
      params={params}
      label={label.toLowerCase()}
      scopeNote={`This section for ${formatLongDay(range.from)} to ${formatLongDay(range.to)}, with its filters.`}
    />
  )
}

interface QueryBodyProps<T> {
  query: UseQueryResult<T>
  label: string
  /** Shown while loading instead of the spinner (e.g. skeleton tiles); the loading message stays for screen readers. */
  skeleton?: ReactNode
  children: (data: T) => ReactNode
}

/** Loading / error / content of one report query. */
function QueryBody<T>({ query, label, skeleton, children }: QueryBodyProps<T>) {
  if (query.isPending) {
    if (skeleton) {
      return (
        <>
          <span role="status" className="sr-only">
            Loading {label.toLowerCase()}
          </span>
          {skeleton}
        </>
      )
    }
    return (
      <div className="flex justify-center rounded-xl bg-slate-50/70 py-10 ring-1 ring-slate-100 ring-inset">
        <Spinner label={`Loading ${label.toLowerCase()}`} />
      </div>
    )
  }
  if (query.isError) {
    return (
      <ErrorState
        title={`Could not load ${label.toLowerCase()}`}
        message={query.error.message}
        correlationId={query.error instanceof ApiError ? query.error.correlationId : null}
        onRetry={() => void query.refetch()}
      />
    )
  }
  return <div className={cn('transition-opacity', query.isPlaceholderData && 'opacity-60')}>{children(query.data)}</div>
}

/** "Highest day …", the subtitle of a day-by-day chart. */
function highest(values: readonly number[], format: (value: number) => string): string {
  return `Highest day ${format(Math.max(0, ...values))}`
}

/** A chart on a soft panel, so each picture reads as its own block inside a section card. */
function ChartPanel({ children }: { children: ReactNode }) {
  return <div className="min-w-0 rounded-xl bg-gradient-to-b from-slate-50/80 to-white p-3 ring-1 ring-slate-100 ring-inset sm:p-4">{children}</div>
}

/** A section's own filters, on a tinted strip at the top of its card. */
function SectionFilters({ children }: { children: ReactNode }) {
  return (
    <div className="mb-5 grid grid-cols-1 gap-3 rounded-xl bg-gradient-to-br from-slate-50 to-emerald-50/40 p-3 ring-1 ring-slate-200/70 ring-inset sm:grid-cols-2 lg:grid-cols-4">
      {children}
    </div>
  )
}

const KPI_GRID = 'grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4'

function KpiSkeleton() {
  return (
    <div aria-hidden="true" className={KPI_GRID}>
      {Array.from({ length: 8 }, (_, index) => (
        <div key={index} className="flex items-center gap-3 rounded-2xl border border-slate-200/70 bg-white px-4 py-3.5">
          <Skeleton className="size-10 rounded-xl" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-5 w-24" />
            <Skeleton className="h-3 w-16" />
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * Who funded the coupon discounts. Orders placed before the funding was recorded count in the total only, so their
 * share is named rather than silently missing from the split.
 */
function couponFunding({ platform, store, total }: ReportOverview['couponDiscounts']): string {
  if (platform === null && store === null) return 'Funding not recorded'
  const parts = [`Platform ${money(platform ?? 0)}`, `stores ${money(store ?? 0)}`]
  // in paise, so rounding never leaves a stray remainder
  const notRecorded = Math.round(total * 100) - Math.round((platform ?? 0) * 100) - Math.round((store ?? 0) * 100)
  if (notRecorded > 0) parts.push(`not recorded ${money(notRecorded / 100)}`)
  return parts.join(' · ')
}

function Kpis({ overview }: { overview: ReportOverview }) {
  return (
    <dl aria-label="Key figures" className={cn('animate-fade-up motion-reduce:animate-none', KPI_GRID)}>
      <StatCard icon="savings" accent="emerald" label="Gross sales (GMV)" value={money(overview.grossSales)} hint="Confirmed orders" />
      <StatCard icon="trendUp" accent="sky" label="Net sales" value={money(overview.netSales)} hint="GMV minus refunds" />
      <StatCard icon="receipt" accent="violet" label="Orders" value={formatCount(overview.orders)} />
      <StatCard icon="chart" accent="amber" label="Average order value" value={money(overview.averageOrderValue)} />
      <StatCard icon="xCircle" accent="rose" label="Cancellation rate" value={formatPercent(overview.cancellationRate)} hint="Cancelled or rejected" />
      <StatCard icon="return" accent="teal" label="Refunds" value={money(overview.refunds)} />
      <StatCard
        icon="ticket"
        accent="violet"
        label="Coupon discounts"
        value={money(overview.couponDiscounts.total)}
        hint={couponFunding(overview.couponDiscounts)}
      />
      <StatCard icon="users" accent="emerald" label="New customers" value={formatCount(overview.newCustomers)} hint="First order in this period" />
    </dl>
  )
}

export function OverviewSection({ range, enabled }: { range: ReportRange; enabled: boolean }) {
  const overview = useReportOverview(range, enabled)
  return (
    <section aria-labelledby="report-overview" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span aria-hidden="true" className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-50 to-sky-50 ring-1 ring-emerald-100">
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

function DailyTable({ rows }: { rows: DailySalesRow[] }) {
  return (
    <table className="min-w-full divide-y divide-slate-200 text-sm">
      <caption className="sr-only">Daily sales</caption>
      <thead className={cn('sticky top-0', TABLE_HEAD_CLASS)}>
        <tr className={TABLE_HEAD_ROW_CLASS}>
          <th scope="col" className="px-3 py-2 text-left">
            Day
          </th>
          <th scope="col" className="px-3 py-2 text-right">
            Orders
          </th>
          <th scope="col" className="px-3 py-2 text-right">
            GMV
          </th>
          <th scope="col" className="px-3 py-2 text-right">
            Discounts
          </th>
          <th scope="col" className="px-3 py-2 text-right">
            Refunds
          </th>
          <th scope="col" className="px-3 py-2 text-right">
            Net
          </th>
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100">
        {rows.map((row) => (
          <tr key={row.date} className="even:bg-slate-50/50 hover:bg-emerald-50/50">
            <th scope="row" className="px-3 py-2 text-left font-normal whitespace-nowrap text-slate-700">
              {formatLongDay(row.date)}
            </th>
            <td className="px-3 py-2 text-right tabular-nums">{formatCount(row.orders)}</td>
            <td className="px-3 py-2 text-right tabular-nums">{money(row.gmv)}</td>
            <td className="px-3 py-2 text-right tabular-nums">{money(row.discounts)}</td>
            <td className="px-3 py-2 text-right tabular-nums">{money(row.refunds)}</td>
            <td className="px-3 py-2 text-right tabular-nums">{money(row.net)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

interface DailySalesSectionProps {
  range: ReportRange
  enabled: boolean
  type: 'GROCERY' | 'RESTAURANT' | undefined
  storeId: string
  onTypeChange: (type: string) => void
  onStoreChange: (storeId: string) => void
}

export function DailySalesSection({ range, enabled, type, storeId, onTypeChange, onStoreChange }: DailySalesSectionProps) {
  const tableId = useId()
  const [showTable, setShowTable] = useState(false)
  const store = useDebouncedValue(storeId.trim(), 300)
  const params = { ...range, type, storeId: store || undefined }
  const daily = useDailySales(params, enabled)

  return (
    <Card
      icon="chart"
      title="Daily sales"
      description="By the day orders were placed, India time."
      actions={
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="secondary" aria-expanded={showTable} aria-controls={tableId} onClick={() => setShowTable((shown) => !shown)}>
            <Icon name={showTable ? 'chart' : 'list'} className="size-4" />
            {showTable ? 'Hide table' : 'Show as table'}
          </Button>
          <ReportExportButton report="daily" params={params} label="Daily sales" range={range} />
        </div>
      }
    >
      <SectionFilters>
        <SelectField label="Store type" value={type ?? ''} onChange={(event) => onTypeChange(event.target.value)}>
          <option value="">All stores</option>
          <option value="GROCERY">Grocery</option>
          <option value="RESTAURANT">Restaurants</option>
        </SelectField>
        <TextField label="Store ID" type="search" value={storeId} autoComplete="off" placeholder="One store only" onChange={(event) => onStoreChange(event.target.value)} />
      </SectionFilters>
      <QueryBody query={daily} label="Daily sales">
        {(rows) =>
          rows.length === 0 ? (
            <EmptyState title="No sales in this period" />
          ) : (
            <>
              <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                <ChartPanel>
                  <TrendChart
                    kind="area"
                    title="Gross sales (GMV)"
                    subtitle={highest(rows.map((row) => row.gmv), money)}
                    categories={rows.map((row) => row.date)}
                    formatCategory={formatShortDay}
                    series={[{ key: 'gmv', name: 'GMV', values: rows.map((row) => row.gmv) }]}
                    format={money}
                    formatTick={(value) => `₹${compactMoney.format(value)}`}
                  />
                </ChartPanel>
                <ChartPanel>
                  <TrendChart
                    kind="column"
                    title="Orders"
                    subtitle={highest(rows.map((row) => row.orders), formatCount)}
                    categories={rows.map((row) => row.date)}
                    formatCategory={formatShortDay}
                    series={[{ key: 'orders', name: 'Orders', values: rows.map((row) => row.orders), color: SERIES_COLORS[0] }]}
                    format={formatCount}
                  />
                </ChartPanel>
              </div>
              <div id={tableId} className={showTable ? 'mt-6 max-h-96 overflow-auto rounded-xl border border-slate-200/80 shadow-sm' : 'sr-only'}>
                <DailyTable rows={rows} />
              </div>
            </>
          )
        }
      </QueryBody>
    </Card>
  )
}

function ranked<T>(rows: T[]): (T & { rank: number })[] {
  return rows.map((row, index) => ({ ...row, rank: index + 1 }))
}

/** Gold, silver and bronze for the top three; a grey disc after that. */
const RANK_LOOKS = ['from-amber-300 to-amber-500 text-white', 'from-slate-300 to-slate-400 text-white', 'from-orange-300 to-orange-500 text-white']

const rankColumn = {
  key: 'rank',
  header: '#',
  className: 'w-12',
  sortValue: (row: { rank: number }) => row.rank,
  cell: (row: { rank: number }) => (
    <span
      className={cn(
        'inline-flex size-7 items-center justify-center rounded-full bg-gradient-to-br text-xs font-bold tabular-nums',
        RANK_LOOKS[row.rank - 1] ?? 'from-slate-100 to-slate-100 text-slate-500',
      )}
    >
      {row.rank}
    </span>
  ),
}

/** Grocery store or restaurant, as a small coloured badge. */
function StoreTypeBadge({ type }: { type: string | null | undefined }) {
  return type === 'RESTAURANT' ? <Badge tone="amber">Restaurant</Badge> : <Badge tone="green">Grocery</Badge>
}

export function TopStoresSection({ range, enabled, metric, onMetricChange }: { range: ReportRange; enabled: boolean; metric: StoreMetric; onMetricChange: (metric: string) => void }) {
  const params = { ...range, metric, limit: TOP_LIMIT }
  const stores = useTopStores(params, enabled)
  const columns: Column<TopStoreRow & { rank: number }>[] = [
    rankColumn,
    {
      key: 'store',
      header: 'Store',
      sortValue: (row) => row.storeName ?? row.storeId,
      cell: (row) => (
        <div className="flex min-w-0 items-center gap-3">
          <Avatar name={row.storeName ?? row.storeId} size="sm" />
          <div className="min-w-0">
            <p className="font-medium text-slate-900">{row.storeName ?? row.storeId}</p>
            <StoreTypeBadge type={row.storeType} />
          </div>
        </div>
      ),
    },
    { key: 'orders', header: 'Orders', className: 'text-right', sortValue: (row) => row.orders, cell: (row) => <span className="tabular-nums">{formatCount(row.orders)}</span> },
    {
      key: 'gmv',
      header: 'GMV',
      className: 'text-right',
      sortValue: (row) => row.gmv,
      cell: (row) => <span className="font-semibold text-slate-900 tabular-nums">{money(row.gmv)}</span>,
    },
  ]
  return (
    <Card
      icon="store"
      title="Top stores"
      actions={
        <div className="flex flex-wrap items-end gap-2">
          <SelectField label="Rank by" value={metric} onChange={(event) => onMetricChange(event.target.value)} containerClassName="w-36">
            <option value="gmv">GMV</option>
            <option value="orders">Orders</option>
          </SelectField>
          <ReportExportButton report="topStores" params={params} label="Top stores" range={range} />
        </div>
      }
    >
      <QueryBody query={stores} label="Top stores">
        {(rows) => (
          <div className="space-y-6">
            {rows.length > 0 && (
              <BarList
                title={metric === 'orders' ? 'Ranked by orders' : 'Ranked by gross sales'}
                format={metric === 'orders' ? formatCount : money}
                items={rows.map((row) => ({
                  key: row.storeId,
                  label: row.storeName ?? row.storeId,
                  detail: row.storeType === 'RESTAURANT' ? 'Restaurant' : 'Grocery',
                  value: metric === 'orders' ? row.orders : row.gmv,
                }))}
              />
            )}
            <DataTable caption="Top stores" columns={columns} rows={ranked(rows)} getRowKey={(row) => row.storeId} empty={<EmptyState title="No orders in this period" />} />
          </div>
        )}
      </QueryBody>
    </Card>
  )
}

export function TopProductsSection({ range, enabled }: { range: ReportRange; enabled: boolean }) {
  const params = { ...range, limit: TOP_LIMIT }
  const products = useTopProducts(params, enabled)
  const columns: Column<TopProductRow & { rank: number }>[] = [
    rankColumn,
    {
      key: 'name',
      header: 'Product or dish',
      sortValue: (row) => row.name,
      cell: (row) => (
        <div>
          <p className="font-medium text-slate-900">{row.name}</p>
          {row.storeName && <p className="text-xs text-slate-500">{row.storeName}</p>}
        </div>
      ),
    },
    {
      key: 'units',
      header: 'Units',
      className: 'text-right',
      sortValue: (row) => row.units,
      cell: (row) => <span className="font-semibold text-slate-900 tabular-nums">{formatCount(row.units)}</span>,
    },
    { key: 'revenue', header: 'Revenue', className: 'text-right', sortValue: (row) => row.revenue, cell: (row) => <span className="tabular-nums">{money(row.revenue)}</span> },
  ]
  return (
    <Card icon="bag" title="Top products and dishes" description="By units sold." actions={<ReportExportButton report="topProducts" params={params} label="Top products" range={range} />}>
      <QueryBody query={products} label="Top products">
        {(rows) => (
          <div className="space-y-6">
            {rows.length > 0 && (
              <BarList
                title="Units sold"
                color={SERIES_COLORS[1]}
                format={formatCount}
                items={rows.map((row) => ({ key: row.itemId, label: row.name, detail: row.storeName ?? undefined, value: row.units }))}
              />
            )}
            <DataTable
              caption="Top products and dishes"
              columns={columns}
              rows={ranked(rows)}
              getRowKey={(row) => row.itemId}
              empty={<EmptyState title="Nothing sold in this period" />}
            />
          </div>
        )}
      </QueryBody>
    </Card>
  )
}

export function DeliverySection({ range, enabled }: { range: ReportRange; enabled: boolean }) {
  const performance = useDeliveryPerformance(range, enabled)
  const columns: Column<DeliveryPerformanceRow>[] = [
    { key: 'date', header: 'Day', sortValue: (row) => row.date, cell: (row) => <span className="whitespace-nowrap">{formatLongDay(row.date)}</span> },
    {
      key: 'deliveries',
      header: 'Deliveries',
      className: 'text-right',
      sortValue: (row) => row.deliveries,
      cell: (row) => <span className="font-semibold text-slate-900 tabular-nums">{formatCount(row.deliveries)}</span>,
    },
    {
      key: 'assign',
      header: 'To assign',
      className: 'text-right',
      hideOnMobile: true,
      sortValue: (row) => row.avgConfirmToAssignedMinutes,
      cell: (row) => minutes(row.avgConfirmToAssignedMinutes),
    },
    {
      key: 'pickup',
      header: 'To pick up',
      className: 'text-right',
      hideOnMobile: true,
      sortValue: (row) => row.avgAssignedToPickedUpMinutes,
      cell: (row) => minutes(row.avgAssignedToPickedUpMinutes),
    },
    {
      key: 'deliver',
      header: 'To deliver',
      className: 'text-right',
      sortValue: (row) => row.avgPickedUpToDeliveredMinutes,
      cell: (row) => minutes(row.avgPickedUpToDeliveredMinutes),
    },
    {
      key: 'failed',
      header: 'Failed',
      className: 'text-right',
      sortValue: (row) => row.failed,
      // Failures stand out as a red pill; zero stays quiet.
      cell: (row) =>
        row.failed > 0 ? (
          <span className="inline-flex rounded-full bg-red-50 px-2 py-0.5 text-xs font-semibold text-red-700 tabular-nums ring-1 ring-red-200 ring-inset">
            {formatCount(row.failed)}
          </span>
        ) : (
          <span className="text-slate-400 tabular-nums">{formatCount(row.failed)}</span>
        ),
    },
  ]
  return (
    <Card
      icon="truck"
      title="Delivery performance"
      description="Average minutes from confirmation to a partner, to pickup, and to the door."
      actions={<ReportExportButton report="delivery" params={{ ...range }} label="Delivery performance" range={range} />}
    >
      <QueryBody query={performance} label="Delivery performance">
        {(rows) =>
          rows.length === 0 ? (
            <EmptyState title="No deliveries in this period" />
          ) : (
            <div className="space-y-6">
              <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                <ChartPanel>
                  <TrendChart
                    kind="column"
                    title="Deliveries"
                    subtitle={highest(rows.map((row) => row.deliveries), formatCount)}
                    categories={rows.map((row) => row.date)}
                    formatCategory={formatShortDay}
                    series={[{ key: 'deliveries', name: 'Deliveries', values: rows.map((row) => row.deliveries), color: SERIES_COLORS[2] }]}
                    format={formatCount}
                  />
                </ChartPanel>
                <ChartPanel>
                  <TrendChart
                    kind="line"
                    title="Average minutes per step"
                    categories={rows.map((row) => row.date)}
                    formatCategory={formatShortDay}
                    series={[
                      { key: 'assign', name: 'To assign', values: rows.map((row) => row.avgConfirmToAssignedMinutes) },
                      { key: 'pickup', name: 'To pick up', values: rows.map((row) => row.avgAssignedToPickedUpMinutes) },
                      { key: 'deliver', name: 'To deliver', values: rows.map((row) => row.avgPickedUpToDeliveredMinutes) },
                    ]}
                    format={minutes}
                  />
                </ChartPanel>
              </div>
              <div className="max-h-96 overflow-auto">
                <DataTable caption="Delivery performance" columns={columns} rows={rows} getRowKey={(row) => row.date} />
              </div>
            </div>
          )
        }
      </QueryBody>
    </Card>
  )
}

function fundedByLabel(fundedBy: PromotionRow['fundedBy']): string {
  if (fundedBy === 'PLATFORM') return 'Platform'
  if (fundedBy === 'STORE') return 'Store'
  return 'Not recorded'
}

export function PromotionsSection({ range, enabled }: { range: ReportRange; enabled: boolean }) {
  const promotions = usePromotionsReport(range, enabled)
  const columns: Column<PromotionRow>[] = [
    {
      key: 'coupon',
      header: 'Coupon',
      sortValue: (row) => row.couponCode,
      cell: (row) => (
        <div className="min-w-0">
          {/* A coupon code looks like a ticket: dashed border, monospace. */}
          <span className="inline-block rounded-md border border-dashed border-violet-300 bg-violet-50 px-2 py-0.5 font-mono text-sm font-semibold text-violet-800">
            {row.couponCode}
          </span>
          {row.couponName && <p className="mt-1 text-xs text-slate-500">{row.couponName}</p>}
        </div>
      ),
    },
    { key: 'orders', header: 'Orders', className: 'text-right', sortValue: (row) => row.orders, cell: (row) => <span className="tabular-nums">{formatCount(row.orders)}</span> },
    {
      key: 'discount',
      header: 'Discount given',
      className: 'text-right',
      sortValue: (row) => row.discountGiven,
      cell: (row) => <span className="font-semibold text-slate-900 tabular-nums">{money(row.discountGiven)}</span>,
    },
    { key: 'gmv', header: 'GMV', className: 'text-right', hideOnMobile: true, sortValue: (row) => row.gmv, cell: (row) => <span className="tabular-nums">{money(row.gmv)}</span> },
    {
      key: 'funded',
      header: 'Funded by',
      sortValue: (row) => fundedByLabel(row.fundedBy),
      cell: (row) =>
        row.fundedBy === 'STORE' ? (
          <Badge tone="blue">Store</Badge>
        ) : row.fundedBy === 'PLATFORM' ? (
          <Badge tone="violet">Platform</Badge>
        ) : (
          // orders placed before the funding was recorded: unknown, not the platform
          <span className="text-xs text-slate-500">{fundedByLabel(row.fundedBy)}</span>
        ),
    },
  ]
  return (
    <Card icon="ticket" title="Promotions" description="Orders placed with a coupon." actions={<ReportExportButton report="promotions" params={{ ...range }} label="Promotions" range={range} />}>
      <QueryBody query={promotions} label="Promotions">
        {(rows) => (
          <div className="space-y-6">
            {rows.length > 0 && (
              <BarList
                title="Discount given"
                color={SERIES_COLORS[4]}
                format={money}
                items={[...rows]
                  .sort((a, b) => b.discountGiven - a.discountGiven)
                  .map((row) => ({ key: row.couponCode, label: row.couponCode, detail: row.couponName ?? undefined, value: row.discountGiven }))}
              />
            )}
            <DataTable caption="Promotions" columns={columns} rows={rows} getRowKey={(row) => row.couponCode} empty={<EmptyState title="No coupon was used in this period" />} />
          </div>
        )}
      </QueryBody>
    </Card>
  )
}

const RATING_TARGETS: Record<string, string> = {
  PRODUCT: 'Products',
  MENU_ITEM: 'Dishes',
  VENDOR: 'Grocery stores',
  RESTAURANT: 'Restaurants',
  DELIVERY_PARTNER: 'Delivery partners',
}

/** Green from 4★, amber from 3★, red below. */
function ratingLook(rating: number): string {
  if (rating >= 4) return 'bg-emerald-50 text-emerald-700 ring-emerald-200'
  if (rating >= 3) return 'bg-amber-50 text-amber-800 ring-amber-200'
  return 'bg-red-50 text-red-700 ring-red-200'
}

function RatedList({ title, icon, rows }: { title: string; icon: IconName; rows: RatedTarget[] }) {
  const columns: Column<RatedTarget>[] = [
    {
      key: 'name',
      header: 'Name',
      sortValue: (row) => row.targetName ?? row.targetId,
      cell: (row) => (
        <div>
          <p className="font-medium text-slate-900">{row.targetName ?? row.targetId}</p>
          <p className="text-xs text-slate-500">{RATING_TARGETS[row.targetType] ?? row.targetType}</p>
        </div>
      ),
    },
    {
      key: 'average',
      header: 'Rating',
      className: 'text-right',
      sortValue: (row) => row.averageRating,
      cell: (row) => (
        <span className={cn('inline-flex rounded-full px-2 py-0.5 text-xs font-semibold whitespace-nowrap tabular-nums ring-1 ring-inset', ratingLook(row.averageRating))}>
          {row.averageRating.toFixed(2)}★
        </span>
      ),
    },
    {
      key: 'count',
      header: 'Ratings',
      className: 'text-right',
      sortValue: (row) => row.ratingCount,
      cell: (row) => <span className="tabular-nums">{formatCount(row.ratingCount)}</span>,
    },
  ]
  return (
    <div className="min-w-0 space-y-2">
      <h3 className="flex items-center gap-2 text-sm font-semibold text-slate-900">
        <Icon name={icon} className="size-4" />
        {title}
      </h3>
      <DataTable caption={title} columns={columns} rows={rows} getRowKey={(row) => `${row.targetType}:${row.targetId}`} empty={<EmptyState title="No ratings yet" />} />
    </div>
  )
}

export function RatingsSection({ range, enabled, targetType, onTargetTypeChange }: { range: ReportRange; enabled: boolean; targetType: string | undefined; onTargetTypeChange: (type: string) => void }) {
  const params = { ...range, targetType, limit: 5 }
  const ratings = useRatingsReport(params, enabled)
  return (
    <Card
      icon="star"
      title="Ratings"
      description="Lowest and highest rated, with how many ratings they have."
      actions={
        <div className="flex flex-wrap items-end gap-2">
          <SelectField label="Rated" value={targetType ?? ''} onChange={(event) => onTargetTypeChange(event.target.value)} containerClassName="w-44">
            <option value="">Everything</option>
            {Object.entries(RATING_TARGETS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </SelectField>
          <ReportExportButton report="ratings" params={params} label="Ratings" range={range} />
        </div>
      }
    >
      <QueryBody query={ratings} label="Ratings">
        {(data) => (
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            <RatedList title="Lowest rated" icon="warning" rows={data.lowest} />
            <RatedList title="Highest rated" icon="star" rows={data.highest} />
          </div>
        )}
      </QueryBody>
    </Card>
  )
}

/** Purchase cost per vendor over the whole range, largest first. */
function costByVendor(rows: readonly PurchasingRow[]) {
  const totals = new Map<string, { key: string; label: string; value: number }>()
  for (const row of rows) {
    const entry = totals.get(row.vendorId) ?? { key: row.vendorId, label: row.vendorName ?? row.vendorId, value: 0 }
    entry.value += row.cost
    totals.set(row.vendorId, entry)
  }
  return [...totals.values()].sort((a, b) => b.value - a.value)
}

export function PurchasingSection({ range, enabled, vendorId, onVendorChange }: { range: ReportRange; enabled: boolean; vendorId: string; onVendorChange: (vendorId: string) => void }) {
  const vendor = useDebouncedValue(vendorId.trim(), 300)
  const params = { ...range, vendorId: vendor || undefined }
  const purchasing = usePurchasingReport(params, enabled)
  const columns: Column<PurchasingRow>[] = [
    { key: 'date', header: 'Day', sortValue: (row) => row.date, cell: (row) => <span className="whitespace-nowrap">{formatLongDay(row.date)}</span> },
    {
      key: 'vendor',
      header: 'Vendor',
      sortValue: (row) => row.vendorName ?? row.vendorId,
      cell: (row) => row.vendorName ?? <span className="font-mono text-xs">{row.vendorId}</span>,
    },
    {
      key: 'units',
      header: 'Units received',
      className: 'text-right',
      sortValue: (row) => row.units,
      cell: (row) => <span className="tabular-nums">{formatCount(row.units)}</span>,
    },
    {
      key: 'cost',
      header: 'Cost',
      className: 'text-right',
      sortValue: (row) => row.cost,
      cell: (row) => <span className="font-semibold text-slate-900 tabular-nums">{money(row.cost)}</span>,
    },
  ]
  return (
    <Card
      icon="clipboard"
      title="Purchasing"
      description="Goods vendors received against purchase orders."
      actions={<ReportExportButton report="purchasing" params={params} label="Purchasing" range={range} />}
    >
      <SectionFilters>
        <TextField label="Vendor ID" type="search" value={vendorId} autoComplete="off" placeholder="One vendor only" onChange={(event) => onVendorChange(event.target.value)} />
      </SectionFilters>
      <QueryBody query={purchasing} label="Purchasing">
        {(rows) => (
          <div className="space-y-6">
            {rows.length > 0 && <BarList title="Cost by vendor" color={SERIES_COLORS[0]} format={money} items={costByVendor(rows)} />}
            <div className="max-h-96 overflow-auto">
              <DataTable
                caption="Purchasing"
                columns={columns}
                rows={rows}
                getRowKey={(row) => `${row.vendorId}:${row.date}`}
                empty={<EmptyState title="No goods received in this period" />}
              />
            </div>
          </div>
        )}
      </QueryBody>
    </Card>
  )
}
