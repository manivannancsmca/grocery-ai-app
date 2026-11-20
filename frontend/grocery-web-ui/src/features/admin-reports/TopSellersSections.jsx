/** Reports → Top stores and Top products: ranked tables (gold / silver / bronze for the first three). */

import { BarList, SERIES_COLORS } from '../../shared/components/charts'
import { Avatar, Badge, Card, DataTable, EmptyState, SelectField } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { formatCount } from '../../shared/utils/format'
import { useTopProducts, useTopStores } from './hooks'
import { TOP_LIMIT, money, ranked } from './reportFormat'
import { QueryBody, ReportExportButton } from './ReportLayout'

/** @typedef {import('./api').ReportRange} ReportRange */
/** @typedef {import('./api').StoreMetric} StoreMetric */

/** Gold, silver and bronze for the top three; a grey disc after that. */
const RANK_LOOKS = [
  'from-amber-300 to-amber-500 text-white',
  'from-slate-300 to-slate-400 text-white',
  'from-orange-300 to-orange-500 text-white',
]

const rankColumn = {
  key: 'rank',
  header: '#',
  className: 'w-12',
  sortValue: (row) => row.rank,
  cell: (row) => (
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

/**
 * Grocery store or restaurant, as a small coloured badge.
 * @param {{ type: string | null | undefined }} props
 */
function StoreTypeBadge({ type }) {
  return type === 'RESTAURANT' ? <Badge tone="amber">Restaurant</Badge> : <Badge tone="green">Grocery</Badge>
}

/** @param {{ range: ReportRange, enabled: boolean, metric: StoreMetric, onMetricChange: (metric: string) => void }} props */
export function TopStoresSection({ range, enabled, metric, onMetricChange }) {
  const params = { ...range, metric, limit: TOP_LIMIT }
  const stores = useTopStores(params, enabled)
  const columns = [
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
    {
      key: 'orders',
      header: 'Orders',
      className: 'text-right',
      sortValue: (row) => row.orders,
      cell: (row) => <span className="tabular-nums">{formatCount(row.orders)}</span>,
    },
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
          <SelectField
            label="Rank by"
            value={metric}
            onChange={(event) => onMetricChange(event.target.value)}
            containerClassName="w-36"
          >
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
            <DataTable
              caption="Top stores"
              columns={columns}
              rows={ranked(rows)}
              getRowKey={(row) => row.storeId}
              empty={<EmptyState title="No orders in this period" />}
            />
          </div>
        )}
      </QueryBody>
    </Card>
  )
}

/** @param {{ range: ReportRange, enabled: boolean }} props */
export function TopProductsSection({ range, enabled }) {
  const params = { ...range, limit: TOP_LIMIT }
  const products = useTopProducts(params, enabled)
  const columns = [
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
    {
      key: 'revenue',
      header: 'Revenue',
      className: 'text-right',
      sortValue: (row) => row.revenue,
      cell: (row) => <span className="tabular-nums">{money(row.revenue)}</span>,
    },
  ]
  return (
    <Card
      icon="bag"
      title="Top products and dishes"
      description="By units sold."
      actions={<ReportExportButton report="topProducts" params={params} label="Top products" range={range} />}
    >
      <QueryBody query={products} label="Top products">
        {(rows) => (
          <div className="space-y-6">
            {rows.length > 0 && (
              <BarList
                title="Units sold"
                color={SERIES_COLORS[1]}
                format={formatCount}
                items={rows.map((row) => ({
                  key: row.itemId,
                  label: row.name,
                  detail: row.storeName ?? undefined,
                  value: row.units,
                }))}
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
