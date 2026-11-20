import { useState } from 'react'
import { adminReportRangeError, DEFAULT_ADMIN_REPORT_DAYS, MAX_ADMIN_REPORT_DAYS, type StoreMetric } from '../../../features/admin-reports/api'
import {
  DailySalesSection,
  DeliverySection,
  OverviewSection,
  PromotionsSection,
  PurchasingSection,
  RatingsSection,
  TopProductsSection,
  TopStoresSection,
} from '../../../features/admin-reports/ReportSections'
import { lastDays } from '../../../features/store-reports/range'
import { FilterBar, PageHeader, TextField } from '../../../shared/components/ui'
import { useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { formatLongDay, indiaDay } from '../../../shared/utils/indiaDate'

/**
 * `/admin/reports` (Phase 12 §7) — platform reporting between two India-time days (`?from=&to=`, default the last 30
 * days, at most 366): the overview's key figures, daily sales (bars with a table alternative), top stores and
 * products, delivery performance, promotions, ratings and purchasing. Every section downloads as CSV.
 */
export function AdminReportsPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [today] = useState(() => indiaDay())
  const fallback = lastDays(DEFAULT_ADMIN_REPORT_DAYS, today)
  const from = searchParams.get('from') ?? fallback.from
  const to = searchParams.get('to') ?? fallback.to
  const rangeError = adminReportRangeError(from, to)
  const valid = rangeError === null
  const range = { from, to }

  const typeParam = searchParams.get('type')
  const type = typeParam === 'GROCERY' || typeParam === 'RESTAURANT' ? typeParam : undefined
  const metric: StoreMetric = searchParams.get('metric') === 'orders' ? 'orders' : 'gmv'
  const ratingType = searchParams.get('rated') || undefined

  const presets = [
    { label: 'Last 7 days', range: lastDays(7, today) },
    { label: 'Last 30 days', range: lastDays(30, today) },
    { label: 'Last 90 days', range: lastDays(90, today) },
    { label: 'Last 12 months', range: lastDays(365, today) },
  ]

  return (
    <div className="space-y-6">
      <PageHeader icon="chart" title="Reports" description="Sales, deliveries, promotions, ratings and purchasing, built from the platform’s events (India time)." />

      {/* The period card (same look as the filter cards of the list pages; its name stays "Report period"). */}
      <FilterBar title="Report period" note="India time">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <div className="grid grid-cols-2 gap-3 sm:flex sm:flex-wrap sm:items-end">
            <TextField
              label="From"
              type="date"
              value={from}
              max={today}
              containerClassName="min-w-0 sm:w-44"
              onChange={(event) => updateParams({ from: event.target.value }, { replace: true })}
            />
            <TextField
              label="To"
              type="date"
              value={to}
              max={today}
              containerClassName="min-w-0 sm:w-44"
              onChange={(event) => updateParams({ to: event.target.value }, { replace: true })}
            />
          </div>
          {/* Quick ranges as toggle chips (aria-pressed marks the one that matches the dates). */}
          <div role="group" aria-label="Quick ranges" className="flex flex-wrap gap-2">
            {presets.map((preset) => {
              const selected = preset.range.from === from && preset.range.to === to
              return (
                <button
                  key={preset.label}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => updateParams({ from: preset.range.from, to: preset.range.to })}
                  className={cn(
                    'inline-flex h-9 items-center gap-1.5 rounded-full px-3.5 text-sm font-semibold transition-all duration-200 motion-reduce:transition-none',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
                    selected
                      ? 'bg-gradient-to-r from-emerald-500 to-teal-500 text-white shadow-md shadow-emerald-600/25'
                      : 'bg-white text-slate-600 ring-1 ring-slate-200 ring-inset hover:-translate-y-px hover:text-emerald-800 hover:ring-emerald-300 motion-reduce:hover:translate-y-0',
                  )}
                >
                  {selected && <Icon name="check" className="size-4" />}
                  {preset.label}
                </button>
              )
            })}
          </div>
        </div>
        {rangeError ? (
          <p role="alert" className="mt-3 flex items-center gap-2 text-sm font-medium text-red-700">
            <Icon name="alertCircle" className="size-4" />
            {rangeError}
          </p>
        ) : (
          <p className="mt-3 flex items-start gap-2 text-xs text-slate-500">
            <Icon name="calendar" className="size-4" />
            <span>
              <span className="font-semibold text-slate-700">
                {formatLongDay(from)} – {formatLongDay(to)}
              </span>
              . Both days are included; up to {MAX_ADMIN_REPORT_DAYS} days at a time.
            </span>
          </p>
        )}
      </FilterBar>

      {valid && (
        <>
          <OverviewSection range={range} enabled={valid} />
          <DailySalesSection
            range={range}
            enabled={valid}
            type={type}
            storeId={searchParams.get('storeId') ?? ''}
            onTypeChange={(value) => updateParams({ type: value })}
            onStoreChange={(value) => updateParams({ storeId: value }, { replace: true })}
          />
          <div className="grid grid-cols-1 gap-6 2xl:grid-cols-2 [&>*]:min-w-0">
            <TopStoresSection range={range} enabled={valid} metric={metric} onMetricChange={(value) => updateParams({ metric: value === 'gmv' ? null : value })} />
            <TopProductsSection range={range} enabled={valid} />
          </div>
          <DeliverySection range={range} enabled={valid} />
          <PromotionsSection range={range} enabled={valid} />
          <RatingsSection range={range} enabled={valid} targetType={ratingType} onTargetTypeChange={(value) => updateParams({ rated: value })} />
          <PurchasingSection
            range={range}
            enabled={valid}
            vendorId={searchParams.get('vendorId') ?? ''}
            onVendorChange={(value) => updateParams({ vendorId: value }, { replace: true })}
          />
        </>
      )}
    </div>
  )
}
