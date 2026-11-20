/** Reports → Promotions: coupon use, the discount given and who funded it. */

import { BarList, SERIES_COLORS } from '../../shared/components/charts'
import { Badge, Card, DataTable, EmptyState } from '../../shared/components/ui'
import { formatCount } from '../../shared/utils/format'
import { usePromotionsReport } from './hooks'
import { money } from './reportFormat'
import { QueryBody, ReportExportButton } from './ReportLayout'

/** @typedef {import('./api').PromotionRow} PromotionRow */
/** @typedef {import('./api').ReportRange} ReportRange */

/**
 * @param {PromotionRow['fundedBy']} fundedBy
 * @returns {string}
 */
function fundedByLabel(fundedBy) {
  if (fundedBy === 'PLATFORM') return 'Platform'
  if (fundedBy === 'STORE') return 'Store'
  return 'Not recorded'
}

/** @param {{ range: ReportRange, enabled: boolean }} props */
export function PromotionsSection({ range, enabled }) {
  const promotions = usePromotionsReport(range, enabled)
  const columns = [
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
    {
      key: 'orders',
      header: 'Orders',
      className: 'text-right',
      sortValue: (row) => row.orders,
      cell: (row) => <span className="tabular-nums">{formatCount(row.orders)}</span>,
    },
    {
      key: 'discount',
      header: 'Discount given',
      className: 'text-right',
      sortValue: (row) => row.discountGiven,
      cell: (row) => <span className="font-semibold text-slate-900 tabular-nums">{money(row.discountGiven)}</span>,
    },
    {
      key: 'gmv',
      header: 'GMV',
      className: 'text-right',
      hideOnMobile: true,
      sortValue: (row) => row.gmv,
      cell: (row) => <span className="tabular-nums">{money(row.gmv)}</span>,
    },
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
    <Card
      icon="ticket"
      title="Promotions"
      description="Orders placed with a coupon."
      actions={<ReportExportButton report="promotions" params={{ ...range }} label="Promotions" range={range} />}
    >
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
                  .map((row) => ({
                    key: row.couponCode,
                    label: row.couponCode,
                    detail: row.couponName ?? undefined,
                    value: row.discountGiven,
                  }))}
              />
            )}
            <DataTable
              caption="Promotions"
              columns={columns}
              rows={rows}
              getRowKey={(row) => row.couponCode}
              empty={<EmptyState title="No coupon was used in this period" />}
            />
          </div>
        )}
      </QueryBody>
    </Card>
  )
}
