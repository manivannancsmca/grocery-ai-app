/** The charts of the delivery partner's performance card. */

import { BarList, ChartPanel, DonutChart, Meter, TrendChart } from '../../shared/components/charts'
import { formatCount, formatMoney, formatPercent } from '../../shared/utils/format'
import { formatShortDay } from '../../shared/utils/indiaDate'
import { formatMinutes } from './statsPeriod'
import { rangeText } from './performanceFormat'

/** @typedef {import('./api').PartnerStatsView} PartnerStatsView */

/** Chart colours: delivered in the brand green, not delivered in rose, cancelled in slate. */
const COLORS = { delivered: '#059669', failed: '#e11d48', cancelled: '#94a3b8', earnings: '#0d9488', online: '#0284c7' }

/** A donut keeps its legend beside it, so its panel takes the full row until the section is wide enough for two. */
const DONUT_PANEL = '@2xl:col-span-2 @4xl:col-span-1'

/**
 * The charts behind the numbers, in pairs of the same kind so a row's two panels are about as tall: the two trends
 * (deliveries and earnings by day), the two donuts (how trips ended, how offers were answered), then times and online
 * time. One column on narrow screens, two from a 42rem-wide section.
 * @param {{ stats: PartnerStatsView }} props
 */
export function Charts({ stats }) {
  const { totals, days, payRate } = stats
  const currency = payRate.currency
  const categories = days.map((day) => day.date)
  const severalDays = days.length > 1
  const finished = totals.delivered + totals.failed + totals.cancelled
  const answered = totals.offersAccepted + totals.offersRejected + totals.offersExpired
  // Online time per day in hours, or in minutes while every day is under two hours (so short days stay visible).
  const inHours = days.some((day) => day.onlineMinutes >= 120)

  return (
    <div className="grid gap-3 @2xl:grid-cols-2 @2xl:gap-4">
      <ChartPanel title="Deliveries by day" icon="chart">
        <TrendChart
          title={rangeText(stats.from, stats.to)}
          subtitle={`${formatCount(totals.delivered)} delivered · ${formatCount(totals.failed)} not delivered · ${formatCount(totals.cancelled)} cancelled`}
          categories={categories}
          formatCategory={formatShortDay}
          kind="column"
          series={[
            { key: 'delivered', name: 'Delivered', values: days.map((day) => day.delivered), color: COLORS.delivered },
            { key: 'failed', name: 'Not delivered', values: days.map((day) => day.failed), color: COLORS.failed },
            { key: 'cancelled', name: 'Cancelled', values: days.map((day) => day.cancelled), color: COLORS.cancelled },
          ]}
          format={(value) => formatCount(value)}
          height={180}
        />
      </ChartPanel>
      <ChartPanel title="Estimated earnings" icon="savings">
        <TrendChart
          title={formatMoney(totals.earnings, currency)}
          subtitle={`${formatMoney(payRate.basePerDelivery, currency)} per delivery + ${formatMoney(payRate.perKm, currency)} per km`}
          categories={categories}
          formatCategory={formatShortDay}
          kind={severalDays ? 'area' : 'column'}
          series={[
            { key: 'earnings', name: 'Earnings', values: days.map((day) => day.earnings), color: COLORS.earnings },
          ]}
          format={(value) => formatMoney(value, currency)}
          formatTick={(value) => `₹${Math.round(value)}`}
          height={180}
        />
      </ChartPanel>
      <ChartPanel title="How your trips ended" icon="checkCircle" className={DONUT_PANEL}>
        <div className="space-y-4">
          <DonutChart
            title="Assigned trips that ended in this period"
            totalLabel="trips"
            parts={[
              { key: 'delivered', label: 'Delivered', value: totals.delivered, color: COLORS.delivered },
              { key: 'failed', label: 'Not delivered', value: totals.failed, color: COLORS.failed },
              { key: 'cancelled', label: 'Cancelled', value: totals.cancelled, color: COLORS.cancelled },
            ]}
            format={(value) => formatCount(value)}
            subtitle={finished === 0 ? 'None yet' : undefined}
          />
          {totals.successRate !== null && (
            <Meter
              label="Success rate (delivered vs not delivered)"
              ratio={totals.successRate}
              valueText={formatPercent(totals.successRate)}
              tone={totals.successRate >= 0.9 ? 'good' : totals.successRate >= 0.7 ? 'warning' : 'critical'}
            />
          )}
        </div>
      </ChartPanel>
      <ChartPanel title="Offer responses" icon="bell" className={DONUT_PANEL}>
        <div className="space-y-4">
          <DonutChart
            title={`${formatCount(totals.offersReceived)} offers received`}
            totalLabel="answered"
            parts={[
              { key: 'accepted', label: 'Accepted', value: totals.offersAccepted, color: COLORS.delivered },
              { key: 'rejected', label: 'Rejected', value: totals.offersRejected, color: COLORS.failed },
              { key: 'expired', label: 'Let expire', value: totals.offersExpired, color: '#f59e0b' },
            ]}
            format={(value) => formatCount(value)}
            subtitle={answered === 0 ? 'None answered yet' : undefined}
          />
          {totals.acceptanceRate !== null && (
            <Meter
              label="Acceptance rate"
              ratio={totals.acceptanceRate}
              valueText={formatPercent(totals.acceptanceRate)}
              tone={totals.acceptanceRate >= 0.7 ? 'good' : totals.acceptanceRate >= 0.4 ? 'warning' : 'critical'}
            />
          )}
        </div>
      </ChartPanel>
      <ChartPanel title="Where the time goes" icon="clock">
        {totals.avgTotalMinutes === null ? (
          <p className="text-sm text-slate-600">Average times appear after your first delivery in this period.</p>
        ) : (
          <BarList
            title="Average per delivered order"
            items={[
              {
                key: 'pickup',
                label: 'Getting to the pickup',
                detail: 'assigned → picked up',
                value: totals.avgPickupMinutes ?? 0,
              },
              {
                key: 'ride',
                label: 'Riding to the customer',
                detail: 'picked up → delivered',
                value: totals.avgRideMinutes ?? 0,
              },
              { key: 'total', label: 'Whole delivery', detail: 'assigned → delivered', value: totals.avgTotalMinutes },
            ]}
            format={(value) => formatMinutes(value)}
          />
        )}
      </ChartPanel>
      <ChartPanel title="Online time" icon="pulse">
        <TrendChart
          title={formatMinutes(totals.onlineMinutes)}
          subtitle="While the app was sharing your location"
          categories={categories}
          formatCategory={formatShortDay}
          kind="column"
          series={[
            {
              key: 'online',
              name: 'Online',
              values: days.map((day) => (inHours ? Math.round((day.onlineMinutes / 60) * 10) / 10 : day.onlineMinutes)),
              color: COLORS.online,
            },
          ]}
          format={(value) => formatMinutes(inHours ? value * 60 : value)}
          formatTick={(value) => (inHours ? `${value} h` : `${value} min`)}
          height={150}
        />
      </ChartPanel>
    </div>
  )
}
