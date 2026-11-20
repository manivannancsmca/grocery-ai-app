import { Fragment } from 'react'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatPhone, formatRelativeTime, humanizeToken, initials } from '../../shared/utils/format'

/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').StoreDelivery} StoreDelivery */

/** A position older than this is shown with its age: the partner's phone may have stopped reporting. */
const STALE_LOCATION_MS = 2 * 60_000
/** Closer than this, the partner is at the store. */
const AT_STORE_KM = 0.1

/** @type {Record<string, IconName>} */
const VEHICLE_ICONS = { BICYCLE: 'bicycle', SCOOTER: 'scooter', MOTORCYCLE: 'motorcycle', CAR: 'car' }

/**
 * "0.4 km away" · "about 2 min", "At the store", or nothing known yet; with the position's age when it is old.
 * @param {StoreDelivery} delivery
 * @param {number} now
 * @returns {string[]}
 */
function whereParts(delivery, now) {
  if (delivery.distanceToStoreKm === null || delivery.partnerLocation === null) return ['Location not shared yet']
  const parts =
    delivery.distanceToStoreKm <= AT_STORE_KM
      ? ['At the store']
      : [
          `${delivery.distanceToStoreKm} km away`,
          ...(delivery.etaToStoreMinutes !== null ? [`about ${delivery.etaToStoreMinutes} min`] : []),
        ]
  const age = now - Date.parse(delivery.partnerLocation.at)
  return age > STALE_LOCATION_MS
    ? [...parts, `location from ${formatRelativeTime(delivery.partnerLocation.at, now).toLowerCase()}`]
    : parts
}

/**
 * Parts joined by " · ", each kept whole: a narrow column breaks between them, never inside one.
 * @param {{ parts: readonly string[] }} props
 */
function Parts({ parts }) {
  return parts.map((part, index) => (
    <Fragment key={part}>
      {index > 0 && ' · '}
      <span className="whitespace-nowrap">{part}</span>
    </Fragment>
  ))
}

/**
 * "🏍 Motorcycle [TN22EK4410]": the vehicle and its number plate, which the store checks at the counter.
 * @param {{ type: string | null, number: string | null }} props
 */
function Vehicle({ type, number }) {
  if (!type && !number) return null
  return (
    <p className="mt-0.5 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs text-slate-600">
      <Icon name={VEHICLE_ICONS[type ?? ''] ?? 'truck'} className="size-3.5" />
      {type && <span className="whitespace-nowrap">{humanizeToken(type)}</span>}
      {type && number && <span className="sr-only"> · </span>}
      {number && (
        <span
          title="Number plate"
          className="rounded-sm bg-white px-1 py-px font-mono text-[11px] leading-4 font-semibold tracking-wide whitespace-nowrap text-slate-800 ring-1 ring-slate-300 ring-inset"
        >
          {number}
        </span>
      )}
    </p>
  )
}

/**
 * @typedef {object} PickupPartnerProps
 * @property {StoreDelivery | undefined} delivery This order's delivery; undefined when there is none yet or the delivery view is not available.
 * @property {number} now
 * @property {'compact' | 'full'} [variant] `full` on a ready order — who to hand it to, the main thing on the card, also saying when nobody is known yet; `compact` while it is still being made (a small row, nothing when there is no delivery).
 */

/**
 * Who collects an order: the partner's name, vehicle, how far from the store they are and a call button, or that a
 * partner is still being found.
 * @param {PickupPartnerProps} props
 */
export function PickupPartner({ delivery, now, variant = 'compact' }) {
  const full = variant === 'full'
  if (!delivery) {
    return full ? (
      <p className="flex items-center gap-2 rounded-lg bg-slate-50 px-3 py-2.5 text-sm text-slate-600 ring-1 ring-slate-200/70 ring-inset">
        <Icon name="scooter" className="size-4" />
        Waiting for a delivery partner.
      </p>
    ) : null
  }
  const partner = delivery.partner
  if (!partner) {
    return (
      <p
        role="status"
        className={cn(
          'flex items-center gap-2 rounded-lg text-slate-600',
          full ? 'bg-slate-50 px-3 py-2.5 text-sm ring-1 ring-slate-200/70 ring-inset' : 'px-0.5 text-xs',
        )}
      >
        <span aria-hidden="true" className="relative flex size-2 shrink-0">
          <span className="absolute inline-flex size-full animate-ping rounded-full bg-amber-400 opacity-60 motion-reduce:hidden" />
          <span className="relative inline-flex size-2 rounded-full bg-amber-400" />
        </span>
        {delivery.assignmentFailed
          ? 'No delivery partner is free nearby yet — still looking.'
          : 'Finding a delivery partner…'}
      </p>
    )
  }

  const where = whereParts(delivery, now)
  const atStore =
    delivery.distanceToStoreKm !== null &&
    delivery.distanceToStoreKm <= AT_STORE_KM &&
    delivery.partnerLocation !== null
  const phone = partner.phone ? formatPhone(partner.phone) : null
  const callLabel = phone ? `Call ${partner.name} on ${phone}` : ''
  const avatar = (
    <span
      aria-hidden="true"
      className={cn(
        'flex shrink-0 items-center justify-center rounded-full font-bold text-white',
        full ? 'size-9 bg-emerald-600 text-xs' : 'size-7 bg-slate-500 text-[10px]',
      )}
    >
      {initials(partner.name)}
    </span>
  )

  if (!full) {
    return (
      <section
        aria-label="Delivery partner"
        className="rounded-lg bg-slate-50/80 px-2.5 py-2 ring-1 ring-slate-200/70 ring-inset"
      >
        <div className="flex items-center gap-2">
          {avatar}
          <p className="min-w-0 flex-1 text-[13px] leading-snug font-semibold break-words text-slate-900">
            {partner.name}
          </p>
          {partner.phone && (
            <a
              href={`tel:${partner.phone}`}
              aria-label={callLabel}
              title={`Call ${phone}`}
              className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-white text-emerald-700 shadow-xs ring-1 ring-slate-200 ring-inset hover:bg-emerald-50 hover:ring-emerald-300 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
            >
              <Icon name="phone" className="size-4" />
            </a>
          )}
        </div>
        <Vehicle type={partner.vehicleType} number={partner.vehicleNumber} />
        <p
          className={cn(
            'mt-1 flex items-start gap-1 text-xs',
            atStore ? 'font-semibold text-emerald-700' : 'text-slate-600',
          )}
        >
          <Icon name={atStore ? 'store' : 'route'} className="mt-px size-3.5" />
          <span className="min-w-0">
            <Parts parts={where} />
          </span>
        </p>
      </section>
    )
  }

  return (
    <section
      aria-label="Delivery partner"
      className="space-y-2.5 rounded-lg bg-emerald-50/70 p-3 ring-1 ring-emerald-200 ring-inset"
    >
      <p className="text-[11px] font-semibold tracking-wide text-emerald-800 uppercase">Hand it to</p>
      <div className="flex items-start gap-2.5">
        {avatar}
        <div className="min-w-0 flex-1">
          <p className="text-sm leading-snug font-semibold break-words text-slate-900">{partner.name}</p>
          <Vehicle type={partner.vehicleType} number={partner.vehicleNumber} />
        </div>
      </div>
      <p
        className={cn(
          'flex items-start gap-1.5 rounded-md px-2 py-1.5 text-xs font-medium',
          atStore ? 'bg-emerald-600 text-white' : 'bg-white/80 text-slate-800 ring-1 ring-emerald-100 ring-inset',
        )}
      >
        <Icon name={atStore ? 'store' : 'route'} className="mt-px size-3.5" />
        <span className="min-w-0">
          <Parts parts={atStore ? [...where, 'hand it over'] : where} />
        </span>
      </p>
      {partner.phone && (
        <a
          href={`tel:${partner.phone}`}
          aria-label={callLabel}
          className="flex h-9 w-full items-center justify-center gap-1.5 rounded-lg bg-white px-3 text-sm font-semibold whitespace-nowrap text-emerald-800 shadow-xs ring-1 ring-emerald-200 ring-inset hover:bg-emerald-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
        >
          <Icon name="phone" className="size-4" />
          {/* Narrow board columns: the number alone (the button's name still says "Call …"). */}
          <span className="tabular-nums">
            <span className="hidden @min-[16rem]:inline">Call </span>
            {phone}
          </span>
        </a>
      )}
    </section>
  )
}
