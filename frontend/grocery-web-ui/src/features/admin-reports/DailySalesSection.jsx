/** Reports → Daily sales: day-by-day charts and table, filtered by store type and store. */

import { useId, useState } from 'react'
import { SERIES_COLORS, TrendChart } from '../../shared/components/charts'
import {
  Button,
  Card,
  EmptyState,
  SelectField,
  TABLE_HEAD_CLASS,
  TABLE_HEAD_ROW_CLASS,
  TextField,
} from '../../shared/components/ui'
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCount } from '../../shared/utils/format'
import { formatLongDay, formatShortDay } from '../../shared/utils/indiaDate'
import { useDailySales } from './hooks'
import { compactMoney, highest, money } from './reportFormat'
import { ChartPanel, QueryBody, ReportExportButton, SectionFilters } from './ReportLayout'

/** @typedef {import('./api').DailySalesRow} DailySalesRow */
/** @typedef {import('./api').ReportRange} ReportRange */

/** @param {{ rows: DailySalesRow[] }} props */
function DailyTable({ rows }) {
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

/**
 * @typedef {object} DailySalesSectionProps
 * @property {ReportRange} range
 * @property {boolean} enabled
 * @property {'GROCERY' | 'RESTAURANT' | undefined} type
 * @property {string} storeId
 * @property {(type: string) => void} onTypeChange
 * @property {(storeId: string) => void} onStoreChange
 */

/** @param {DailySalesSectionProps} props */
export function DailySalesSection({ range, enabled, type, storeId, onTypeChange, onStoreChange }) {
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
          <Button
            size="sm"
            variant="secondary"
            aria-expanded={showTable}
            aria-controls={tableId}
            onClick={() => setShowTable((shown) => !shown)}
          >
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
        <TextField
          label="Store ID"
          type="search"
          value={storeId}
          autoComplete="off"
          placeholder="One store only"
          onChange={(event) => onStoreChange(event.target.value)}
        />
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
                    subtitle={highest(
                      rows.map((row) => row.gmv),
                      money,
                    )}
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
                    subtitle={highest(
                      rows.map((row) => row.orders),
                      formatCount,
                    )}
                    categories={rows.map((row) => row.date)}
                    formatCategory={formatShortDay}
                    series={[
                      { key: 'orders', name: 'Orders', values: rows.map((row) => row.orders), color: SERIES_COLORS[0] },
                    ]}
                    format={formatCount}
                  />
                </ChartPanel>
              </div>
              <div
                id={tableId}
                className={
                  showTable ? 'mt-6 max-h-96 overflow-auto rounded-xl border border-slate-200/80 shadow-sm' : 'sr-only'
                }
              >
                <DailyTable rows={rows} />
              </div>
            </>
          )
        }
      </QueryBody>
    </Card>
  )
}
