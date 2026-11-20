/** Reports → Ratings: the highest and lowest rated products, dishes, stores, restaurants or delivery partners. */

import { Card, DataTable, EmptyState, SelectField } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCount } from '../../shared/utils/format'
import { useRatingsReport } from './hooks'
import { QueryBody, ReportExportButton } from './ReportLayout'

/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').RatedTarget} RatedTarget */
/** @typedef {import('./api').ReportRange} ReportRange */

/** @type {Record<string, string>} */
const RATING_TARGETS = {
  PRODUCT: 'Products',
  MENU_ITEM: 'Dishes',
  VENDOR: 'Grocery stores',
  RESTAURANT: 'Restaurants',
  DELIVERY_PARTNER: 'Delivery partners',
}

/**
 * Green from 4★, amber from 3★, red below.
 * @param {number} rating
 * @returns {string}
 */
function ratingLook(rating) {
  if (rating >= 4) return 'bg-emerald-50 text-emerald-700 ring-emerald-200'
  if (rating >= 3) return 'bg-amber-50 text-amber-800 ring-amber-200'
  return 'bg-red-50 text-red-700 ring-red-200'
}

/** @param {{ title: string, icon: IconName, rows: RatedTarget[] }} props */
function RatedList({ title, icon, rows }) {
  const columns = [
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
        <span
          className={cn(
            'inline-flex rounded-full px-2 py-0.5 text-xs font-semibold whitespace-nowrap tabular-nums ring-1 ring-inset',
            ratingLook(row.averageRating),
          )}
        >
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
      <DataTable
        caption={title}
        columns={columns}
        rows={rows}
        getRowKey={(row) => `${row.targetType}:${row.targetId}`}
        empty={<EmptyState title="No ratings yet" />}
      />
    </div>
  )
}

/** @param {{ range: ReportRange, enabled: boolean, targetType: string | undefined, onTargetTypeChange: (type: string) => void }} props */
export function RatingsSection({ range, enabled, targetType, onTargetTypeChange }) {
  const params = { ...range, targetType, limit: 5 }
  const ratings = useRatingsReport(params, enabled)
  return (
    <Card
      icon="star"
      title="Ratings"
      description="Lowest and highest rated, with how many ratings they have."
      actions={
        <div className="flex flex-wrap items-end gap-2">
          <SelectField
            label="Rated"
            value={targetType ?? ''}
            onChange={(event) => onTargetTypeChange(event.target.value)}
            containerClassName="w-44"
          >
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
