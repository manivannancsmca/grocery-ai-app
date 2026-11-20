import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Skeleton } from '../../shared/components/ui'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCount, formatPrice } from '../../shared/utils/format'
import type { PartnerView, VehicleType } from '../delivery/api'
import { VEHICLE_LABELS } from '../delivery/schema'
import { usePartnerStats } from './hooks'
import { formatMinutes, presetRange } from './statsPeriod'

/** The partner's own vehicle as the picture of their dashboard. */
const VEHICLE_ICONS: Record<VehicleType, IconName> = {
  BICYCLE: 'bicycle',
  SCOOTER: 'scooter',
  MOTORCYCLE: 'motorcycle',
  CAR: 'car',
}

/** "Good morning" / "Good afternoon" / "Good evening", by the device's clock. */
function greeting(hour: number): string {
  if (hour < 12) return 'Good morning'
  if (hour < 17) return 'Good afternoon'
  return 'Good evening'
}

interface Figure {
  /** Short, shown under the number; `full` is what assistive technology reads ("Delivered today"). */
  label: string
  full: string
  /** `undefined` while it loads or when it cannot be read. */
  value: string | undefined
  icon: IconName
}

/** One number of today on the banner: a shimmer while it loads, a dash when it cannot be read. */
function FigureTile({ figure, loading }: { figure: Figure; loading: boolean }) {
  return (
    <div className="relative flex min-w-0 flex-col-reverse justify-center rounded-xl bg-white/12 py-2.5 pr-3 pl-12 ring-1 ring-white/20 backdrop-blur-sm ring-inset">
      {/* The label comes first for screen readers; the number shows on top. */}
      <dt className="truncate text-xs leading-tight font-medium text-white/85">
        <span aria-hidden="true" className="absolute top-1/2 left-2.5 flex size-8 -translate-y-1/2 items-center justify-center rounded-lg bg-white shadow-sm">
          <Icon name={figure.icon} className="size-4.5" />
        </span>
        <span aria-hidden="true">{figure.label}</span>
        <span className="sr-only">{figure.full}</span>
      </dt>
      <dd className="truncate text-lg leading-tight font-bold tabular-nums">
        {figure.value ?? (loading ? <Skeleton className="my-0.5 h-5 w-10 bg-white/30" /> : '—')}
      </dd>
    </div>
  )
}

const LINK_CLASS =
  'inline-flex h-9 items-center justify-center gap-1.5 rounded-xl px-2.5 text-sm font-semibold transition focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white sm:px-3'

interface PartnerHeroProps {
  partner: PartnerView
  /** The availability panel (Online switch and location sharing), on the right from `lg`, under the name on phones. */
  availability?: ReactNode
}

/**
 * The top of the delivery partner's dashboard — what matters now: a greeting with their vehicle, links to their
 * history and profile, the Online switch (`availability`) and today's numbers (delivered, estimated earnings, distance
 * and time online, India time, from `GET /delivery/stats` for today). The period figures live in "Your performance".
 */
export function PartnerHero({ partner, availability }: PartnerHeroProps) {
  const today = usePartnerStats(presetRange('today'))
  const totals = today.data?.totals
  const firstName = partner.fullName?.trim().split(/\s+/)[0] ?? 'there'
  const vehicle = partner.vehicleType ? VEHICLE_LABELS[partner.vehicleType] : null

  const figures: Figure[] = [
    { label: 'Delivered', full: 'Delivered today', value: totals && formatCount(totals.delivered), icon: 'checkCircle' },
    { label: 'Earned (est.)', full: 'Earned today (estimate)', value: totals && formatPrice(totals.earnings, today.data?.payRate.currency), icon: 'savings' },
    { label: 'Distance', full: 'Distance today', value: totals && `${totals.distanceKm.toFixed(1)} km`, icon: 'route' },
    { label: 'Online', full: 'Online today', value: totals && formatMinutes(totals.onlineMinutes), icon: 'clock' },
  ]

  return (
    <section
      aria-label="Your day"
      className="@container animate-fade-up relative isolate overflow-hidden rounded-3xl bg-gradient-to-br from-emerald-600 via-teal-600 to-sky-700 p-4 text-white shadow-lg shadow-teal-900/20 motion-reduce:animate-none sm:p-5"
    >
      {/* Soft lights. */}
      <span aria-hidden="true" className="absolute -top-20 -right-16 -z-10 size-64 rounded-full bg-white/15 blur-3xl" />
      <span aria-hidden="true" className="absolute -bottom-24 left-10 -z-10 size-56 rounded-full bg-amber-300/20 blur-3xl" />

      {/*
        By the banner's own width: narrow — name, switch, numbers one under the other; medium — name beside the switch,
        the numbers under both; wide — the numbers under the name and the switch beside both.
      */}
      <div
        className={cn(
          'grid gap-4',
          Boolean(availability) && '@3xl:grid-cols-[minmax(0,1fr)_minmax(20rem,24rem)] @3xl:gap-x-5 @5xl:grid-cols-[minmax(0,1fr)_minmax(20rem,26rem)] @5xl:grid-rows-[auto_1fr]',
        )}
      >
        <div className="flex min-w-0 flex-wrap items-start justify-between gap-3 @3xl:col-start-1 @3xl:row-start-1 @3xl:self-center @5xl:self-start">
          <div className="flex min-w-0 flex-1 basis-52 items-center gap-3 sm:gap-4">
            <span aria-hidden="true" className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-white shadow-md sm:size-14">
              <Icon name={partner.vehicleType ? VEHICLE_ICONS[partner.vehicleType] : 'scooter'} className="size-8 sm:size-9" />
            </span>
            <div className="min-w-0">
              <p className="text-sm leading-tight font-medium text-white/85">{greeting(new Date().getHours())},</p>
              <h1 className="truncate text-2xl leading-tight font-bold tracking-tight">{firstName}</h1>
              <p className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-white/90">
                {vehicle && <span>{vehicle}</span>}
                {partner.vehicleNumber && (
                  <span className="rounded-md bg-white/15 px-1.5 py-0.5 font-mono text-xs font-semibold tracking-wider ring-1 ring-white/25 ring-inset">
                    {partner.vehicleNumber}
                  </span>
                )}
                {partner.city && (
                  <span className="inline-flex items-center gap-1">
                    <Icon name="mapPin" className="size-3.5" />
                    {partner.city}
                  </span>
                )}
              </p>
            </div>
          </div>
          {/* Phones: pictures only (the names are still read out). */}
          <nav aria-label="Your account" className="flex shrink-0 gap-2">
            <Link to="/delivery/history" className={cn(LINK_CLASS, 'bg-white/15 ring-1 ring-white/25 ring-inset hover:bg-white/25')}>
              <Icon name="list" className="size-4" />
              <span className="sr-only sm:not-sr-only">Delivery history</span>
            </Link>
            <Link to="/delivery/profile" className={cn(LINK_CLASS, 'bg-white text-emerald-800 shadow-sm hover:bg-emerald-50')}>
              <Icon name="user" className="size-4" />
              <span className="sr-only sm:not-sr-only">Profile</span>
            </Link>
          </nav>
        </div>

        {availability && <div className="min-w-0 @3xl:col-start-2 @3xl:row-start-1 @5xl:row-span-2">{availability}</div>}

        <div className="min-w-0 self-end @3xl:col-span-2 @3xl:row-start-2 @5xl:col-span-1 @5xl:col-start-1">
          <p aria-hidden="true" className="mb-1.5 text-[11px] font-semibold tracking-wider text-white/75 uppercase">
            Today
          </p>
          <dl aria-label="Today's numbers" className="grid grid-cols-2 gap-2 @xl:grid-cols-4">
            {figures.map((figure) => (
              <FigureTile key={figure.label} figure={figure} loading={today.isPending} />
            ))}
          </dl>
        </div>
      </div>
    </section>
  )
}
