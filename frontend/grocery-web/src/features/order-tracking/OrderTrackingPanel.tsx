import { ApiError, hasErrorCode } from '../../shared/api/problemDetail'
import { LazyDeliveryMap } from '../../shared/components/map/LazyDeliveryMap'
import { distanceKm, isGeoPoint, type MapPoint } from '../../shared/components/map/types'
import { Alert, Button, Skeleton, Spinner } from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { Icon, type IconName } from '../../shared/icons'
import { useRealtimeStatus } from '../../shared/realtime/useRealtime'
import { cn } from '../../shared/utils/cn'
import { formatDistanceKm, formatPhone, formatTime, initials } from '../../shared/utils/format'
import { VEHICLE_TYPES, type VehicleType } from '../delivery/api'
import { VEHICLE_LABELS } from '../delivery/schema'
import type { OrderView } from '../orders/api'
import { TRACKING_NOT_FOUND, type TrackingView } from './api'
import { useOrderTracking } from './hooks'

/** A partner position older than this is called out: their phone has probably lost its signal or connection. */
const STALE_LOCATION_MS = 2 * 60_000

/** The map's height: fixed per width (Leaflet does not follow a box that grows after it was drawn). */
const MAP_CLASS = 'h-60 @md:h-72'

const VEHICLE_ICONS: Record<string, IconName> = { BICYCLE: 'bicycle', SCOOTER: 'scooter', MOTORCYCLE: 'motorcycle', CAR: 'car' }

function vehicleLabel(vehicle: string | null): string | null {
  if (!vehicle) return null
  return (VEHICLE_TYPES as readonly string[]).includes(vehicle) ? VEHICLE_LABELS[vehicle as VehicleType] : vehicle
}

/** Where the order is delivered: the delivery's drop point, else the address copied onto the order. */
function dropPoint(tracking: TrackingView, order: OrderView) {
  if (tracking.drop && isGeoPoint(tracking.drop)) return tracking.drop
  return isGeoPoint(order.deliveryAddress) ? order.deliveryAddress : null
}

/** The restaurant (food orders) or store, the customer's home and — once known — the partner. */
function trackingPoints(tracking: TrackingView, order: OrderView): MapPoint[] {
  const points: MapPoint[] = []
  if (tracking.pickup && isGeoPoint(tracking.pickup)) {
    const restaurant = order.store.type === 'RESTAURANT'
    points.push({
      id: 'store',
      kind: restaurant ? 'restaurant' : 'store',
      label: `${tracking.pickup.name} (${restaurant ? 'restaurant' : 'store'})`,
      latitude: tracking.pickup.latitude,
      longitude: tracking.pickup.longitude,
    })
  }
  const drop = dropPoint(tracking, order)
  if (drop) points.push({ id: 'home', kind: 'home', label: 'Your address', latitude: drop.latitude, longitude: drop.longitude })
  if (tracking.partnerLocation && tracking.partner) {
    points.push({
      id: 'partner',
      kind: 'partner',
      label: `${tracking.partner.name} (delivery partner)`,
      latitude: tracking.partnerLocation.latitude,
      longitude: tracking.partnerLocation.longitude,
    })
  }
  return points
}

/** The route line: partner → pickup → you before the pickup, partner → you after it (no partner yet: pickup → you). */
function trackingRoute(tracking: TrackingView): string[] {
  return tracking.status === 'PICKED_UP' ? ['partner', 'home'] : ['partner', 'store', 'home']
}

function Searching() {
  return (
    <div role="status" className="flex items-start gap-3 rounded-2xl bg-sky-50 p-3.5 text-sm text-sky-900 ring-1 ring-sky-100 ring-inset">
      <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-white text-sky-600 shadow-sm">
        <Spinner size="sm" />
      </span>
      <div className="min-w-0">
        <p className="font-semibold">Looking for a delivery partner…</p>
        <p className="mt-0.5 text-sky-800">We are asking partners near the store. You will see them on the map once one accepts.</p>
      </div>
    </div>
  )
}

/** Whether the map is really live: the push connection's state (realtime layer), not a decoration. */
function ConnectionBadge() {
  const status = useRealtimeStatus()
  const look =
    status === 'live'
      ? { text: 'Live', className: 'bg-emerald-50 text-emerald-700 ring-emerald-200', dot: 'bg-emerald-500', ping: true }
      : status === 'offline'
        ? { text: 'Updates every 30 s', className: 'bg-slate-100 text-slate-600 ring-slate-200', dot: 'bg-slate-400', ping: false }
        : { text: status === 'connecting' ? 'Connecting…' : 'Reconnecting…', className: 'bg-amber-50 text-amber-800 ring-amber-200', dot: 'bg-amber-500', ping: true }
  return (
    <p className={cn('inline-flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold whitespace-nowrap ring-1 ring-inset', look.className)}>
      <span aria-hidden="true" className="relative flex size-2">
        {look.ping && <span className={cn('absolute inset-0 animate-ping rounded-full opacity-75 motion-reduce:hidden', look.dot)} />}
        <span className={cn('relative size-2 rounded-full', look.dot)} />
      </span>
      {look.text}
    </p>
  )
}

/** "Location updated at 10:42", or a warning when the partner's position has not changed for a while. */
function LocationFreshness({ tracking, partnerName }: { tracking: TrackingView; partnerName: string }) {
  const at = tracking.partnerLocation?.at
  const active = tracking.status === 'ASSIGNED' || tracking.status === 'PICKED_UP'
  const now = useNow(30_000, Boolean(at) && active)
  if (!at) {
    return (
      <p className="flex items-center gap-1.5 text-xs text-slate-500">
        <Icon name="mapPin" className="size-3.5" />
        {`Waiting for ${partnerName}'s location…`}
      </p>
    )
  }
  const ageMinutes = Math.floor((now - Date.parse(at)) / 60_000)
  if (active && now - Date.parse(at) > STALE_LOCATION_MS) {
    return (
      <p className="flex items-start gap-1.5 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-900 ring-1 ring-amber-100">
        <Icon name="clock" className="mt-px size-3.5 shrink-0" />
        {`Last location ${ageMinutes} min ago (${formatTime(at)}). ${partnerName}'s phone may have lost its connection; the map moves again as soon as it is back.`}
      </p>
    )
  }
  return (
    <p className="flex items-center gap-1.5 text-xs text-slate-500">
      <Icon name="mapPin" className="size-3.5" />
      {`${partnerName}'s location updated at ${formatTime(at)}.`}
    </p>
  )
}

interface PartnerCardProps {
  name: string
  vehicleType: string | null
  /** The number plate, to spot the partner (only while they hold the delivery). */
  vehicleNumber: string | null
  /** To call the partner (only while they hold the delivery): no call button without it. */
  phone: string | null
  /** What the partner is doing ("Ravi is on the way to you"). */
  doing: string
  /** "1.2 km away" once they carry the order. */
  away: string | null
}

/**
 * Who is bringing the order: initials with the vehicle and its number plate, what they are doing, how far they still
 * are, and a button to call them.
 */
function PartnerCard({ name, vehicleType, vehicleNumber, phone, doing, away }: PartnerCardProps) {
  const vehicle = vehicleLabel(vehicleType)
  const shownPhone = phone ? formatPhone(phone) : null
  return (
    <section
      aria-label="Delivery partner"
      className="rounded-2xl bg-gradient-to-r from-emerald-50/80 via-white to-white p-3 ring-1 ring-emerald-100 ring-inset"
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <span
          aria-hidden="true"
          className="relative flex size-12 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-emerald-500 to-emerald-700 text-base font-bold text-white shadow-sm"
        >
          {initials(name)}
          <span className="absolute -right-1 -bottom-1 flex size-6 items-center justify-center rounded-full bg-white shadow ring-1 ring-emerald-100">
            <Icon name={VEHICLE_ICONS[vehicleType ?? ''] ?? 'scooter'} className="size-3.5" />
          </span>
        </span>
        <div className="min-w-0 flex-1 basis-40">
          <p className="text-[15px] leading-snug font-semibold text-slate-900">{doing}</p>
          <p className="mt-0.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs text-slate-600">
            <span>{['Your delivery partner', vehicle].filter(Boolean).join(' · ')}</span>
            {vehicleNumber && (
              <>
                <span className="sr-only"> · number plate </span>
                <span
                  title="Number plate"
                  className="rounded-sm bg-white px-1 py-px font-mono text-[11px] leading-4 font-semibold tracking-wide whitespace-nowrap text-slate-800 ring-1 ring-slate-300 ring-inset"
                >
                  {vehicleNumber}
                </span>
              </>
            )}
          </p>
        </div>
        {away && (
          <p className="ml-15 shrink-0 rounded-full bg-white px-2.5 py-1 text-xs font-semibold whitespace-nowrap text-sky-800 ring-1 ring-sky-200 ring-inset @sm:ml-0">
            {away} away
          </p>
        )}
      </div>
      {phone && (
        // Under the name (lined up with it from small widths), full width on a phone: easy to hit with a thumb.
        <a
          href={`tel:${phone}`}
          aria-label={`Call ${name} on ${shownPhone}`}
          className="mt-3 flex h-10 w-full items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-semibold whitespace-nowrap text-white shadow-sm shadow-emerald-600/25 transition-colors hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 @sm:ml-15 @sm:w-fit"
        >
          <Icon name="phone" className="size-4" />
          Call <span className="tabular-nums">{shownPhone}</span>
        </a>
      )}
    </section>
  )
}

/**
 * Live tracking on the order page while the order is `trackable` (Phase 9 contract §4): the partner's first name,
 * vehicle and number plate, a button to call them (while they hold the delivery: the contract sends their phone only
 * then), how far they are, and a map with the restaurant or store, the customer's home, the partner and the
 * route between them (the arrival estimate is in the order's status, next to this panel). The partner moves with
 * every `DELIVERY_LOCATION` signal (patched into the cache by the realtime layer — no refetch per ping, no page
 * refresh). The badge shows whether the push connection is live; while it is not, the tracking is re-read every 30 s
 * (useOrderTracking).
 */
export function OrderTrackingPanel({ order }: { order: OrderView }) {
  const tracking = useOrderTracking(order.id, true)

  let content
  if (tracking.isPending) {
    content = (
      <div role="status" aria-label="Loading live tracking">
        <Skeleton className="h-72 w-full rounded-xl" />
      </div>
    )
  } else if (tracking.isError && !tracking.data) {
    // A failed first read. (A failed background re-read keeps the last view; see the note below the map.)
    const notFound = hasErrorCode(tracking.error, TRACKING_NOT_FOUND) || (tracking.error instanceof ApiError && tracking.error.status === 404)
    content = notFound ? (
      <Searching />
    ) : (
      <Alert
        tone="warning"
        title="Live tracking is not available right now"
        actions={
          <Button variant="secondary" size="sm" onClick={() => void tracking.refetch()}>
            Try again
          </Button>
        }
      >
        {tracking.error.message}
      </Alert>
    )
  } else {
    const view = tracking.data
    const partner = view.partner
    const points = trackingPoints(view, order)
    const refreshFailed = tracking.isRefetchError && (
      <p className="text-xs text-amber-800">Could not refresh the tracking just now; showing the last update. It retries by itself.</p>
    )
    if (view.status === 'CANCELLED') {
      content = <p className="text-sm text-slate-600">This delivery was cancelled.</p>
    } else if (!partner || view.status === 'PENDING' || view.status === 'SEARCHING') {
      // No partner yet: where the order comes from and where it goes, so the customer already sees the trip.
      content = (
        <>
          <Searching />
          {points.length > 0 && <LazyDeliveryMap points={points} route={trackingRoute(view)} label="Delivery map" className={MAP_CLASS} />}
          {refreshFailed}
        </>
      )
    } else {
      const pickedUp = view.status === 'PICKED_UP'
      const finished = view.status === 'DELIVERED' || view.status === 'FAILED'
      const drop = dropPoint(view, order)
      // How far the partner still is from the customer (straight line), once they carry the order.
      const away = pickedUp && !finished && view.partnerLocation && drop ? formatDistanceKm(distanceKm(view.partnerLocation, drop)) : null
      const doing =
        view.status === 'DELIVERED'
          ? `${partner.name} delivered your order`
          : pickedUp
            ? `${partner.name} is on the way to you`
            : `${partner.name} is heading to ${view.pickup?.name ?? 'the store'}`
      content = (
        <>
          <PartnerCard
            name={partner.name}
            vehicleType={partner.vehicleType}
            vehicleNumber={partner.vehicleNumber ?? null}
            phone={partner.phone ?? null}
            doing={doing}
            away={away}
          />
          <LazyDeliveryMap points={points} route={trackingRoute(view)} label="Delivery map" className={MAP_CLASS} />
          {!finished && <LocationFreshness tracking={view} partnerName={partner.name} />}
          {refreshFailed}
        </>
      )
    }
  }

  return (
    <section
      aria-label="Live tracking"
      className="@container animate-fade-up flex min-w-0 flex-col gap-3.5 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm shadow-slate-900/[0.04] sm:p-5"
    >
      <div className="flex items-center gap-3">
        <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-50 ring-1 ring-emerald-100 ring-inset">
          <Icon name="route" className="size-[1.15rem]" />
        </span>
        <h2 className="min-w-0 flex-1 text-base font-semibold text-slate-900">Live tracking</h2>
        <ConnectionBadge />
      </div>
      {content}
    </section>
  )
}
