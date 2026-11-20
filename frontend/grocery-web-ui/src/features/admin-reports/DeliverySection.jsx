/** Reports → Delivery: deliveries and failures per day, and the average minutes of each step (assign, pick up, deliver). */

import { SERIES_COLORS, TrendChart } from '../../shared/components/charts'
import { Card, DataTable, EmptyState } from '../../shared/components/ui'
import { formatCount } from '../../shared/utils/format'
import { formatLongDay, formatShortDay } from '../../shared/utils/indiaDate'
import { useDeliveryPerformance } from './hooks'
import { highest, minutes } from './reportFormat'
import { ChartPanel, QueryBody, ReportExportButton } from './ReportLayout'

/** @typedef {import('./api').ReportRange} ReportRange */

/** @param {{ range: ReportRange, enabled: boolean }} props */
export function DeliverySection({ range, enabled }) {
  const performance = useDeliveryPerformance(range, enabled)
  const columns = [
    {
      key: 'date',
      header: 'Day',
      sortValue: (row) => row.date,
      cell: (row) => <span className="whitespace-nowrap">{formatLongDay(row.date)}</span>,
    },
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
      actions={
        <ReportExportButton report="delivery" params={{ ...range }} label="Delivery performance" range={range} />
      }
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
                    subtitle={highest(
                      rows.map((row) => row.deliveries),
                      formatCount,
                    )}
                    categories={rows.map((row) => row.date)}
                    formatCategory={formatShortDay}
                    series={[
                      {
                        key: 'deliveries',
                        name: 'Deliveries',
                        values: rows.map((row) => row.deliveries),
                        color: SERIES_COLORS[2],
                      },
                    ]}
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
                      {
                        key: 'pickup',
                        name: 'To pick up',
                        values: rows.map((row) => row.avgAssignedToPickedUpMinutes),
                      },
                      {
                        key: 'deliver',
                        name: 'To deliver',
                        values: rows.map((row) => row.avgPickedUpToDeliveredMinutes),
                      },
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
