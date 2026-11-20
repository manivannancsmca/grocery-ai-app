import { Link } from 'react-router'
import { DELIVERY_ERRORS } from '../../../features/deliveries/api'
import { HistoryFilterBar } from '../../../features/deliveries/history/HistoryFilterBar'
import {
  FILTER_PARAMS,
  filtersFromParams,
  filtersText,
  historyQuery,
  isFiltered,
} from '../../../features/deliveries/history/historyFilters'
import { HistoryOverview } from '../../../features/deliveries/history/HistoryOverview'
import { TripTimeline, TripTimelineSkeleton } from '../../../features/deliveries/history/TripTimeline'
import { useDeliveryHistory } from '../../../features/deliveries/hooks'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import { Alert, Button, EmptyState, ErrorState, PageHeader, Pagination } from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { DeliveryScooterIllustration } from '../../../shared/illustrations'
import { formatCount } from '../../../shared/utils/format'

const PRIMARY_LINK =
  'inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-900/20 transition-colors hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/**
 * `/delivery/history` — the partner's finished trips: this week's numbers on top, then every trip, newest first,
 * grouped by day (India time) with the route store → customer, distance, ride time, times and outcome; 20 per page.
 * The trips (and the export) can be filtered by outcome and period, kept in the URL; the numbers on top are not.
 */
export function DeliveryHistoryPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const page = pageFromParam(searchParams.get('page'))
  const filters = filtersFromParams(searchParams)
  const filtered = isFiltered(filters)
  const query = historyQuery(filters)
  const history = useDeliveryHistory({ page, size: DEFAULT_PAGE_SIZE, ...query })
  const notActive = hasErrorCode(history.error, DELIVERY_ERRORS.partnerNotActive)
  const total = history.data?.totalElements

  /** A new filter starts again at the first page; "all" leaves the URL key out. */
  function changeFilters(change) {
    const next = { ...filters, ...change }
    updateParams({
      [FILTER_PARAMS.outcome]: next.outcome === 'all' ? null : next.outcome,
      [FILTER_PARAMS.period]: next.period === 'all' ? null : next.period,
      page: null,
    })
  }

  let content
  if (history.isPending) {
    content = <TripTimelineSkeleton />
  } else if (history.isError) {
    content = notActive ? (
      <Alert tone="info" title="Your account is waiting for approval">
        Your deliveries appear here once you are approved and start delivering.
      </Alert>
    ) : (
      <ErrorState
        title="Could not load your deliveries"
        message={history.error.message}
        correlationId={history.error instanceof ApiError ? history.error.correlationId : null}
        onRetry={() => void history.refetch()}
      />
    )
  } else if (history.data.content.length === 0 && filtered) {
    content = (
      <EmptyState
        illustration={<DeliveryScooterIllustration />}
        title="No trips match these filters"
        description={`No ${filtersText(filters)}. Try another outcome or period.`}
        action={
          <Button variant="secondary" onClick={() => changeFilters({ outcome: 'all', period: 'all' })}>
            Show all trips
          </Button>
        }
      />
    )
  } else if (history.data.content.length === 0) {
    content = (
      <EmptyState
        size="lg"
        illustration={<DeliveryScooterIllustration />}
        title="No trips yet"
        description="Every delivery you finish shows up here, grouped by day: the store, the drop-off area, the distance and how long the ride took."
        action={
          <Link to="/delivery" className={PRIMARY_LINK}>
            <Icon name="scooter" className="size-4" />
            Go online and take an order
          </Link>
        }
      />
    )
  } else {
    content = (
      <>
        <TripTimeline trips={history.data.content} dimmed={history.isPlaceholderData} />
        <Pagination
          page={history.data.page}
          size={history.data.size}
          totalElements={history.data.totalElements}
          totalPages={history.data.totalPages}
          disabled={history.isFetching}
          onPageChange={(next) => updateParams({ page: pageToParam(next) })}
        />
      </>
    )
  }

  let countText = null
  if (total !== undefined && total > 0) {
    const verb = filtered ? (total === 1 ? 'matches' : 'match') : 'in all'
    countText = (
      <p className="text-sm text-slate-500">
        <span className="font-semibold text-slate-900 tabular-nums">{formatCount(total)}</span>{' '}
        {total === 1 ? 'trip' : 'trips'} {verb} · newest first
      </p>
    )
  }

  return (
    <div className="space-y-5">
      <PageHeader
        icon="route"
        title="Delivery history"
        description="Your finished trips, newest first. Days are India time; the week is the last 7 days."
        actions={
          notActive ? undefined : (
            <ExportButton path="/delivery/history/export" params={query} label="your deliveries" total={total} />
          )
        }
      />

      {!notActive && <HistoryOverview />}

      <section aria-labelledby="trips-title" className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-1">
          <h2 id="trips-title" className="flex items-center gap-2 text-lg font-bold text-slate-900">
            <Icon name="scooter" className="size-5" />
            Your trips
          </h2>
          {countText}
        </div>
        {!notActive && <HistoryFilterBar filters={filters} onChange={changeFilters} />}
        {content}
      </section>
    </div>
  )
}
