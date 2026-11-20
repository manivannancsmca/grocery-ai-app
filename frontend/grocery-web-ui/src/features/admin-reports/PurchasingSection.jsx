/** Reports → Purchasing: goods that vendors received from their suppliers - cost by vendor, units and cost per day. */

import { BarList, SERIES_COLORS } from '../../shared/components/charts'
import { Card, DataTable, EmptyState, TextField } from '../../shared/components/ui'
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue'
import { formatCount } from '../../shared/utils/format'
import { formatLongDay } from '../../shared/utils/indiaDate'
import { usePurchasingReport } from './hooks'
import { money } from './reportFormat'
import { QueryBody, ReportExportButton, SectionFilters } from './ReportLayout'

/** @typedef {import('./api').PurchasingRow} PurchasingRow */
/** @typedef {import('./api').ReportRange} ReportRange */

/**
 * Purchase cost per vendor over the whole range, largest first.
 * @param {readonly PurchasingRow[]} rows
 */
function costByVendor(rows) {
  const totals = new Map()
  for (const row of rows) {
    const entry = totals.get(row.vendorId) ?? { key: row.vendorId, label: row.vendorName ?? row.vendorId, value: 0 }
    entry.value += row.cost
    totals.set(row.vendorId, entry)
  }
  return [...totals.values()].sort((a, b) => b.value - a.value)
}

/** @param {{ range: ReportRange, enabled: boolean, vendorId: string, onVendorChange: (vendorId: string) => void }} props */
export function PurchasingSection({ range, enabled, vendorId, onVendorChange }) {
  const vendor = useDebouncedValue(vendorId.trim(), 300)
  const params = { ...range, vendorId: vendor || undefined }
  const purchasing = usePurchasingReport(params, enabled)
  const columns = [
    {
      key: 'date',
      header: 'Day',
      sortValue: (row) => row.date,
      cell: (row) => <span className="whitespace-nowrap">{formatLongDay(row.date)}</span>,
    },
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
        <TextField
          label="Vendor ID"
          type="search"
          value={vendorId}
          autoComplete="off"
          placeholder="One vendor only"
          onChange={(event) => onVendorChange(event.target.value)}
        />
      </SectionFilters>
      <QueryBody query={purchasing} label="Purchasing">
        {(rows) => (
          <div className="space-y-6">
            {rows.length > 0 && (
              <BarList title="Cost by vendor" color={SERIES_COLORS[0]} format={money} items={costByVendor(rows)} />
            )}
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
