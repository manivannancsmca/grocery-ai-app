import { useId, useState } from 'react'
import { directionsUrl } from '../../shared/components/map/directions'
import { LazyDeliveryMap } from '../../shared/components/map/LazyDeliveryMap'
import { isGeoPoint } from '../../shared/components/map/types'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Badge, Button, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatPrice, formatTime } from '../../shared/utils/format'
import { ORDER_TYPE_LABELS } from '../orders/labels'
import { deliveryActionError } from './errors'
import { FailDeliveryDialog } from './FailDeliveryDialog'
import { useDeliveryAction } from './hooks'
import { deliveryStatusLabel, deliveryStatusTone, dropAddressLines, itemsText, telHref } from './labels'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/components/map/types').GeoPoint} GeoPoint */
/** @typedef {import('../../shared/components/map/types').MapPoint} MapPoint */
/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').DeliveryView} DeliveryView */

/**
 * @typedef {object} ActiveDeliveryCardProps
 * @property {DeliveryView} delivery
 * @property {GeoPoint | null} position The partner's own latest position ("You" on the map).
 */

const STEPS = [
  { number: 1, title: 'Go to the store' },
  { number: 2, title: 'Deliver' },
]

/**
 * @param {string} name
 * @returns {string}
 */
function firstName(name) {
  return name.trim().split(/\s+/)[0] ?? name
}

/**
 * Store (or restaurant), customer and "You" on the map of the current step.
 * @param {DeliveryView} delivery
 * @param {GeoPoint | null} position
 * @returns {MapPoint[]}
 */
function routePoints(delivery, position) {
  const points = []
  if (delivery.pickup && isGeoPoint(delivery.pickup)) {
    const restaurant = delivery.orderType === 'RESTAURANT'
    points.push({
      id: 'store',
      kind: restaurant ? 'restaurant' : 'store',
      label: `${delivery.pickup.name} (${restaurant ? 'restaurant' : 'store'})`,
      latitude: delivery.pickup.latitude,
      longitude: delivery.pickup.longitude,
    })
  }
  if (isGeoPoint(delivery.drop)) {
    points.push({
      id: 'home',
      kind: 'home',
      label: `${delivery.drop.recipientName} (customer)`,
      latitude: delivery.drop.latitude,
      longitude: delivery.drop.longitude,
    })
  }
  if (position) points.push({ id: 'me', kind: 'partner', label: 'You', ...position })
  return points
}

/** The route line of each step: you → pickup → customer before the pickup, you → customer after it. */
const ROUTE_TO_STORE = ['me', 'store', 'home']
const ROUTE_TO_CUSTOMER = ['me', 'home']

/**
 * "Navigate" opens turn-by-turn directions (Google Maps) to the next stop, in a new tab or the Maps app.
 * @param {{ to: GeoPoint, name: string }} props
 */
function NavigateLink({ to, name }) {
  return (
    <a
      href={directionsUrl(to)}
      target="_blank"
      rel="noopener noreferrer"
      className="flex h-11 min-w-0 items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-sky-600 to-indigo-600 px-3 text-sm font-semibold text-white shadow-sm shadow-sky-700/25 transition hover:brightness-110 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-600 sm:text-base"
    >
      <Icon name="mapPin" className="size-5" />
      <span className="truncate">Navigate to {name}</span>
      <span className="sr-only">(opens directions in a new tab)</span>
    </a>
  )
}

/**
 * The two steps as a progress bar: the pickup (restaurant or store picture), then the customer (home).
 * @param {{ current: 1 | 2, restaurant: boolean }} props
 */
function StepList({ current, restaurant }) {
  return (
    <ol aria-label="Delivery steps" className="grid grid-cols-2 gap-2">
      {STEPS.map((step) => {
        const done = step.number < current
        const active = step.number === current
        const picture = step.number === 1 ? (restaurant ? 'cloche' : 'store') : 'home'
        return (
          <li
            key={step.number}
            aria-current={active ? 'step' : undefined}
            className={cn(
              'flex min-w-0 items-center gap-2.5 rounded-xl px-2.5 py-2 ring-1 transition-colors ring-inset',
              active
                ? 'bg-gradient-to-r from-emerald-500 to-teal-500 text-white shadow-md ring-transparent shadow-emerald-700/20'
                : done
                  ? 'bg-emerald-50 text-emerald-800 ring-emerald-100'
                  : 'bg-slate-50 text-slate-500 ring-slate-200',
            )}
          >
            <span
              aria-hidden="true"
              className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-white shadow-sm"
            >
              {done ? (
                <Icon name="check" className="size-4 text-emerald-600" />
              ) : (
                <Icon name={picture} className="size-4.5" />
              )}
            </span>
            <span className="min-w-0">
              <span
                aria-hidden="true"
                className="block text-[10px] leading-tight font-semibold tracking-wide uppercase opacity-80"
              >
                Step {step.number}
                {done && ' · done'}
              </span>
              <span className="block text-sm leading-tight font-semibold break-words">
                <span className="sr-only">Step {step.number}: </span>
                {step.title}
                {done && <span className="sr-only"> (done)</span>}
              </span>
            </span>
          </li>
        )
      })}
    </ol>
  )
}

/**
 * One stop of the trip: its picture, name and the lines under it, with an optional badge on the right.
 * @param {{ icon: IconName, tone: string, name: string, children?: ReactNode, badge?: ReactNode }} props
 */
function StopCard({ icon, tone, name, children, badge }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2 rounded-2xl bg-slate-50/80 p-3 ring-1 ring-slate-200/70 ring-inset">
      <div className="flex min-w-0 items-start gap-3">
        <span
          aria-hidden="true"
          className={cn('flex size-10 shrink-0 items-center justify-center rounded-xl ring-1', tone)}
        >
          <Icon name={icon} className="size-5.5" />
        </span>
        <div className="min-w-0">
          <p className="text-base leading-snug font-semibold text-slate-900">{name}</p>
          {children}
        </div>
      </div>
      {badge}
    </div>
  )
}

/**
 * The partner's active delivery (Phase 9), as a two-part card: on the left the steps, the next stop and the next
 * action as one big button; on the right (under it on narrow screens) the map of the route. Step 1 "Go to the store"
 * ("Order ready" from `readyForPickup`, "Picked up" only once it is ready); step 2 "Deliver" (customer, call link,
 * address, note, "Delivered" / "Couldn't deliver"). A cancelled delivery says so plainly.
 * @param {ActiveDeliveryCardProps} props
 */
export function ActiveDeliveryCard({ delivery, position }) {
  const toast = useToast()
  const action = useDeliveryAction()
  const [failing, setFailing] = useState(null)
  const hintId = useId()
  const busy = action.isPending
  const points = routePoints(delivery, position)

  async function run(step, success) {
    try {
      await action.mutateAsync({ deliveryId: delivery.deliveryId, action: step })
      success()
    } catch (error) {
      const failure = deliveryActionError(delivery.orderNumber, error)
      toast.show(failure.tone, failure.message, { description: failure.description })
    }
  }

  const pickUp = () =>
    void run({ type: 'pickup' }, () =>
      toast.success(`Order ${delivery.orderNumber} picked up`, {
        description: `Now take it to ${firstName(delivery.drop.recipientName)}.`,
      }),
    )
  const deliver = () =>
    void run({ type: 'deliver' }, () =>
      toast.success(`Order ${delivery.orderNumber} delivered`, {
        description: 'Great job! New offers will appear here.',
      }),
    )

  const restaurant = delivery.orderType === 'RESTAURANT'
  const header = (
    <header className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 border-b border-emerald-100 bg-gradient-to-r from-emerald-50 via-white to-sky-50 px-4 py-3 sm:px-5">
      <div className="flex min-w-0 items-center gap-3">
        <span
          aria-hidden="true"
          className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-emerald-100"
        >
          <Icon name="receipt" className="size-5.5" />
        </span>
        <div className="min-w-0">
          <p className="text-[11px] font-bold tracking-wide text-emerald-700 uppercase">Current delivery</p>
          <h2 className="text-base leading-tight font-bold text-slate-900 sm:text-lg">Order {delivery.orderNumber}</h2>
          <p className="text-xs text-slate-500 sm:text-sm">
            {ORDER_TYPE_LABELS[delivery.orderType] ?? delivery.orderType} · {itemsText(delivery.itemCount)} ·{' '}
            {formatPrice(delivery.total, delivery.currency)} paid online
          </p>
        </div>
      </div>
      <Badge tone={deliveryStatusTone(delivery.status)} dot>
        {deliveryStatusLabel(delivery.status)}
      </Badge>
    </header>
  )

  if (delivery.status === 'CANCELLED') {
    return (
      <section
        aria-label="Active delivery"
        className="overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-sm"
      >
        {header}
        <div className="p-4 sm:p-5">
          <Alert tone="warning" role="alert" title={`Order ${delivery.orderNumber} was cancelled`}>
            Do not pick it up. If you already have it, return it to {delivery.pickup?.name ?? 'the store'}.
          </Alert>
        </div>
      </section>
    )
  }

  const pickedUp = delivery.status === 'PICKED_UP'
  const drop = delivery.drop

  const work = !pickedUp ? (
    <>
      <h3 className="text-sm font-bold tracking-wide text-slate-500 uppercase">Go to the store</h3>
      <StopCard
        icon={restaurant ? 'cloche' : 'store'}
        tone={restaurant ? 'bg-rose-50 ring-rose-100' : 'bg-amber-50 ring-amber-100'}
        name={delivery.pickup?.name ?? 'The store'}
        badge={
          delivery.readyForPickup ? (
            <Badge tone="green" dot className="px-2.5 py-1 text-sm">
              Order ready
            </Badge>
          ) : (
            <Badge tone="amber" dot className="px-2.5 py-1 text-sm">
              Being prepared{delivery.readyBy ? ` · ready by ${formatTime(delivery.readyBy)}` : ''}
            </Badge>
          )
        }
      >
        {delivery.pickup?.city && <p className="text-sm text-slate-600">{delivery.pickup.city}</p>}
      </StopCard>
      <p className="flex items-start gap-2 text-sm text-slate-600">
        <Icon name="home" className="mt-0.5 size-4 shrink-0" />
        <span>
          Then deliver to {drop.recipientName}
          {drop.landmark || drop.line2 ? `, ${drop.landmark ?? drop.line2}` : ''}, {drop.city}.
        </span>
      </p>
      <div className="space-y-2.5 pt-1">
        {delivery.pickup && isGeoPoint(delivery.pickup) && (
          <NavigateLink to={delivery.pickup} name={delivery.pickup.name} />
        )}
        <Button
          className="h-14 w-full text-base"
          onClick={pickUp}
          disabled={busy || !delivery.readyForPickup}
          aria-describedby={delivery.readyForPickup ? undefined : hintId}
        >
          {busy ? <Spinner size="sm" className="[&_svg]:text-white" /> : <Icon name="bagCheck" className="size-5" />}
          Picked up
        </Button>
        {!delivery.readyForPickup && (
          <p id={hintId} className="text-center text-sm text-slate-500">
            You can confirm the pickup once the store marks the order ready.
          </p>
        )}
      </div>
    </>
  ) : (
    <>
      <h3 className="text-sm font-bold tracking-wide text-slate-500 uppercase">Deliver</h3>
      <StopCard icon="home" tone="bg-emerald-50 ring-emerald-100" name={drop.recipientName}>
        <address className="mt-0.5 space-y-0.5 text-sm text-slate-700 not-italic">
          {dropAddressLines(drop).map((line) => (
            <p key={line}>{line}</p>
          ))}
        </address>
      </StopCard>
      {delivery.note && (
        <p className="rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-100 ring-inset">
          <span className="font-semibold">Note:</span> {delivery.note}
        </p>
      )}
      <div className="space-y-2.5 pt-1">
        <div className={cn('grid gap-2.5', drop.phone && isGeoPoint(drop) && 'sm:grid-cols-2')}>
          {drop.phone && (
            <a
              href={telHref(drop.phone)}
              aria-label={`Call ${drop.recipientName}, ${drop.phone}`}
              className="flex h-11 min-w-0 items-center justify-center gap-2 rounded-xl bg-sky-50 px-3 text-sm font-semibold text-sky-800 ring-1 ring-sky-200 ring-inset hover:bg-sky-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-600 sm:text-base"
            >
              <Icon name="phone" className="size-5" />
              <span className="shrink-0">Call {firstName(drop.recipientName)}</span>
              <span className="truncate font-medium text-sky-700/80">· {drop.phone}</span>
            </a>
          )}
          {isGeoPoint(drop) && <NavigateLink to={drop} name={firstName(drop.recipientName)} />}
        </div>
        <div className="grid grid-cols-[2fr_3fr] gap-2.5">
          <Button
            variant="secondary"
            className="h-14 px-2 text-sm text-red-700 sm:px-4 sm:text-base"
            onClick={() => setFailing(delivery)}
            disabled={busy}
          >
            Couldn’t deliver
          </Button>
          <Button className="h-14 text-base" onClick={deliver} disabled={busy}>
            {busy ? <Spinner size="sm" className="[&_svg]:text-white" /> : <Icon name="homeCheck" className="size-5" />}
            Delivered
          </Button>
        </div>
      </div>
    </>
  )

  return (
    <section
      aria-label="Active delivery"
      className="@container overflow-hidden rounded-3xl border border-emerald-200 bg-white shadow-md shadow-emerald-900/5"
    >
      {header}
      <div className="grid @3xl:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
        <div className="min-w-0 space-y-3.5 p-4 sm:p-5">
          <StepList current={pickedUp ? 2 : 1} restaurant={restaurant} />
          {work}
        </div>
        {/* Wide cards: the map fills the right half, as tall as the steps beside it. */}
        <div className="min-w-0 border-t border-slate-100 bg-slate-50/50 p-4 sm:p-5 @3xl:border-t-0 @3xl:border-l @3xl:[&>figure]:flex @3xl:[&>figure]:h-full @3xl:[&>figure]:flex-col">
          <LazyDeliveryMap
            points={points}
            route={pickedUp ? ROUTE_TO_CUSTOMER : ROUTE_TO_STORE}
            label={pickedUp ? 'Route to the customer' : 'Route to the store'}
            className="h-56 sm:h-64 @3xl:h-auto @3xl:min-h-72 @3xl:flex-1"
          />
        </div>
      </div>

      <FailDeliveryDialog delivery={failing} onClose={() => setFailing(null)} />
    </section>
  )
}
