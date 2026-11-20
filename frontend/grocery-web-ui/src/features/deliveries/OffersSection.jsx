import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { ApiError } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Button, ErrorState, Skeleton, Spinner } from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCountdown, formatMoney } from '../../shared/utils/format'
import { ORDER_TYPE_LABELS } from '../orders/labels'
import { deliveryKeys } from './api'
import { offerError } from './errors'
import { useAcceptOffer, useOffers, usePartnerStats, useRejectOffer } from './hooks'
import { itemsText, kmText, offerEarnings } from './labels'
import { presetRange } from './statsPeriod'

/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').OfferView} OfferView */
/** @typedef {import('./api').PartnerStatsView} PartnerStatsView */

/** Under this much time left, the countdown turns red. */
export const OFFER_URGENT_MS = 10_000

/** How long a partner has to answer an offer (the service's offer time-to-live): the bar along the top. */
const OFFER_WINDOW_MS = 30_000

/** @param {{ label: string, value: string, icon: IconName, highlight?: boolean }} props */
function OfferFact({ label, value, icon, highlight = false }) {
  return (
    <div className="relative min-w-0 pl-9">
      <dt className="truncate text-[11px] leading-tight font-medium text-slate-500">
        <span
          aria-hidden="true"
          className="absolute top-1/2 left-0 flex size-7 -translate-y-1/2 items-center justify-center rounded-lg bg-white shadow-sm ring-1 ring-slate-200/70"
        >
          <Icon name={icon} className="size-4" />
        </span>
        {label}
      </dt>
      <dd className={cn('truncate text-sm leading-tight font-bold', highlight ? 'text-emerald-700' : 'text-slate-900')}>
        {value}
      </dd>
    </div>
  )
}

/**
 * One offer: the store and the drop area as a little route, the distances, items and estimated earnings, a countdown
 * to `expiresAt` (also a shrinking bar along the top) and big Accept / Reject buttons. Wide cards (a single offer on
 * a desktop) put the buttons beside the facts.
 * @param {{ offer: OfferView, now: number, payRate?: PartnerStatsView['payRate'] }} props
 */
export function OfferCard({ offer, now, payRate }) {
  const toast = useToast()
  const queryClient = useQueryClient()
  const accept = useAcceptOffer()
  const reject = useRejectOffer()
  const expiresAt = Date.parse(offer.expiresAt)
  const remaining = Number.isFinite(expiresAt) ? expiresAt - now : Number.POSITIVE_INFINITY
  const expired = remaining <= 0
  const busy = accept.isPending || reject.isPending

  // An expired offer is closed by the server's poller; re-read the list so it disappears.
  useEffect(() => {
    if (expired) void queryClient.invalidateQueries({ queryKey: deliveryKeys.offers() })
  }, [expired, queryClient])

  async function onAccept() {
    try {
      const delivery = await accept.mutateAsync(offer.offerId)
      toast.success(`Delivery accepted — head to ${delivery.pickup?.name ?? offer.store.name}`, {
        description: `Order ${delivery.orderNumber}. The customer's details are now on your screen.`,
      })
    } catch (error) {
      const failure = offerError(offer.store.name, error)
      toast.show(failure.tone, failure.message, { description: failure.description })
    }
  }

  async function onReject() {
    try {
      await reject.mutateAsync(offer.offerId)
      toast.info(`Offer from ${offer.store.name} rejected`)
    } catch (error) {
      const failure = offerError(offer.store.name, error)
      toast.show(failure.tone, failure.message, { description: failure.description })
    }
  }

  const urgent = remaining < OFFER_URGENT_MS
  const restaurant = offer.orderType === 'RESTAURANT'
  const left = Math.max(0, Math.min(1, remaining / OFFER_WINDOW_MS))
  const earnings = offerEarnings(offer.tripKm, payRate)

  return (
    <article
      aria-label={`Delivery offer from ${offer.store.name}`}
      aria-busy={busy || undefined}
      className={cn(
        '@container animate-pop-in relative h-full overflow-hidden rounded-2xl border-2 bg-white p-4 pt-5 shadow-md motion-reduce:animate-none',
        expired ? 'border-slate-200 opacity-70 shadow-none' : 'border-emerald-300 shadow-emerald-900/10',
      )}
    >
      {/* Time left to answer, as a shrinking bar along the top. */}
      {!expired && (
        <span aria-hidden="true" className="absolute inset-x-0 top-0 h-1.5 bg-slate-100">
          <span
            className={cn(
              'block h-full transition-[width] duration-1000 ease-linear',
              urgent ? 'bg-red-500' : 'bg-gradient-to-r from-emerald-500 to-teal-500',
            )}
            style={{ width: `${left * 100}%` }}
          />
        </span>
      )}
      <div className="grid gap-3.5 @3xl:grid-cols-[minmax(0,1fr)_17rem] @3xl:items-center @3xl:gap-6">
        <div className="min-w-0 space-y-3">
          <header className="flex items-start justify-between gap-3">
            <div className="flex min-w-0 items-center gap-3">
              <span
                aria-hidden="true"
                className={cn(
                  'flex size-11 shrink-0 items-center justify-center rounded-xl ring-1',
                  restaurant ? 'bg-rose-50 ring-rose-100' : 'bg-amber-50 ring-amber-100',
                )}
              >
                <Icon name={restaurant ? 'cloche' : 'store'} className="size-6" />
              </span>
              <div className="min-w-0">
                <p className="text-[11px] font-bold tracking-wide text-emerald-700 uppercase">
                  New delivery · {ORDER_TYPE_LABELS[offer.orderType] ?? offer.orderType}
                </p>
                <h3 className="line-clamp-2 text-base leading-snug font-bold break-words text-slate-900 sm:text-lg @xl:line-clamp-1">
                  {offer.store.name}
                </h3>
                <p className="truncate text-xs text-slate-500">
                  {[offer.store.city, offer.orderNumber].filter(Boolean).join(' · ')}
                </p>
              </div>
            </div>
            {expired ? (
              <p className="shrink-0 rounded-full bg-slate-100 px-2.5 py-1 text-sm font-semibold text-slate-500">
                Expired
              </p>
            ) : (
              <p
                className={cn(
                  'shrink-0 rounded-xl px-2.5 py-1 text-right text-[11px] font-medium ring-1 ring-inset',
                  urgent ? 'bg-red-50 text-red-700 ring-red-200' : 'bg-amber-50 text-amber-800 ring-amber-200',
                )}
              >
                Respond within
                <span
                  role="timer"
                  aria-label="Time left to respond"
                  className="block font-mono text-xl leading-tight font-bold tabular-nums"
                >
                  {formatCountdown(remaining)}
                </span>
              </p>
            )}
          </header>

          {/* Pickup → drop area. */}
          <div className="flex items-center gap-2 text-sm">
            <span className="flex min-w-0 items-center gap-1.5 font-semibold text-slate-800">
              <span aria-hidden="true" className="size-2.5 shrink-0 rounded-full bg-amber-400 ring-2 ring-amber-100" />
              <span className="truncate">{offer.store.name}</span>
            </span>
            <span aria-hidden="true" className="flex min-w-6 flex-1 items-center text-slate-300">
              <span className="h-0 flex-1 border-t-2 border-dotted border-current" />
              <Icon name="chevronRight" className="-ml-1 size-4" />
            </span>
            <dl className="min-w-0 font-semibold text-slate-800">
              <dt className="sr-only">Drop area</dt>
              <dd className="flex min-w-0 items-center gap-1.5">
                <span
                  aria-hidden="true"
                  className="size-2.5 shrink-0 rounded-full bg-emerald-500 ring-2 ring-emerald-100"
                />
                <span className="truncate">{offer.dropArea ?? '—'}</span>
              </dd>
            </dl>
          </div>

          <dl className="grid grid-cols-2 gap-x-3 gap-y-2.5 rounded-xl bg-slate-50 p-2.5 ring-1 ring-slate-100 ring-inset @xl:grid-cols-4">
            <OfferFact label="To the store" value={kmText(offer.distanceKm)} icon="route" />
            <OfferFact label="Trip to the customer" value={kmText(offer.tripKm)} icon="scooter" />
            <OfferFact label="Items" value={itemsText(offer.itemCount)} icon="bag" />
            <OfferFact
              label="You earn (est.)"
              value={earnings === null ? '—' : formatMoney(earnings, payRate?.currency)}
              icon="savings"
              highlight={earnings !== null}
            />
          </dl>
        </div>

        <div className="grid grid-cols-2 gap-2.5">
          <Button
            variant="secondary"
            className="h-12 text-base"
            onClick={() => void onReject()}
            disabled={busy || expired}
          >
            {reject.isPending && <Spinner size="sm" />}
            Reject
          </Button>
          <Button className="h-12 text-base" onClick={() => void onAccept()} disabled={busy || expired}>
            {accept.isPending && <Spinner size="sm" className="[&_svg]:text-white" />}
            Accept
          </Button>
        </div>
      </div>
    </article>
  )
}

/** Online with nothing to do yet: a slim card with a soft radar pulse around the vehicle. */
function Waiting() {
  return (
    <div className="flex items-center gap-4 rounded-2xl border border-emerald-100 bg-gradient-to-r from-white via-white to-emerald-50/70 px-4 py-4 shadow-sm sm:px-5">
      <span aria-hidden="true" className="relative flex size-14 shrink-0 items-center justify-center">
        <span className="absolute inset-0 animate-ping rounded-full bg-emerald-200/60 [animation-duration:2.4s] motion-reduce:hidden" />
        <span className="absolute inset-1.5 rounded-full bg-emerald-100" />
        <span className="relative flex size-10 items-center justify-center rounded-full bg-white shadow-md ring-1 ring-emerald-200">
          <Icon name="scooter" className="size-6" />
        </span>
      </span>
      <div className="min-w-0">
        <p className="text-base font-semibold text-slate-900">Looking for deliveries near you</p>
        <p className="text-sm text-slate-600">
          No offers right now. Stay online — new deliveries near you appear here instantly.
        </p>
      </div>
    </div>
  )
}

/**
 * The partner's pending offers (`GET /api/delivery/offers`), refreshed live by DELIVERY_OFFER and
 * DELIVERY_OFFERS_CHANGED. The first partner to accept wins; the others are told "Another partner took it". The
 * estimated earnings use the pay rate of today's stats (the banner reads the same query).
 * @param {{ enabled: boolean }} props
 */
export function OffersSection({ enabled }) {
  const offers = useOffers(enabled)
  const today = usePartnerStats(presetRange('today'))
  const list = offers.data ?? []
  const now = useNow(1_000, list.length > 0)
  const count = list.length

  return (
    <section aria-label="Delivery offers" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <h2 className="flex items-center gap-2.5 text-lg font-bold text-slate-900">
          <span
            aria-hidden="true"
            className="flex size-8 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-emerald-100"
          >
            <Icon name="bell" className="size-4.5" />
          </span>
          Delivery offers
          {count > 0 && (
            <span className="rounded-full bg-emerald-600 px-2.5 py-0.5 text-xs font-bold text-white tabular-nums">
              {count}
            </span>
          )}
        </h2>
        {/* Announced when offers arrive or go (the countdowns themselves are not read out every second). */}
        <p role="status" className="text-xs text-slate-500 sm:text-sm">
          {offers.isSuccess &&
            (count === 0 ? 'None waiting' : `${count} waiting · the first partner to accept gets it`)}
        </p>
      </div>
      {offers.isPending ? (
        <div role="status" aria-label="Loading offers">
          <Skeleton className="h-24 rounded-2xl" />
        </div>
      ) : offers.isError ? (
        <ErrorState
          title="Could not load your offers"
          message={offers.error.message}
          correlationId={offers.error instanceof ApiError ? offers.error.correlationId : null}
          onRetry={() => void offers.refetch()}
        />
      ) : count === 0 ? (
        <Waiting />
      ) : (
        <ul className={cn('grid gap-3', count > 1 && 'xl:grid-cols-2')}>
          {list.map((offer) => (
            <li key={offer.offerId}>
              <OfferCard offer={offer} now={now} payRate={today.data?.payRate} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
