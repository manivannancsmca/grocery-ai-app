import { useQueries } from '@tanstack/react-query'
import { ApiError } from '../../shared/api/problemDetail'
import { Button, Spinner, StatCard } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { fetchServiceHealth, HEALTH_SERVICES, SERVICE_LABELS } from './api'

/** @typedef {import('react').CSSProperties} CSSProperties */
/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').HealthService} HealthService */

const SYSTEM_STATUS_POLL_MS = 15_000

/** @typedef {'UP' | 'DOWN' | 'UNREACHABLE' | 'CHECKING'} ServiceState */

/** @type {Record<ServiceState, BadgeTone>} */
const STATE_TONE = { UP: 'green', DOWN: 'red', UNREACHABLE: 'amber', CHECKING: 'gray' }
/** @type {Record<ServiceState, string>} */
const STATE_LABEL = { UP: 'Up', DOWN: 'Down', UNREACHABLE: 'Unreachable', CHECKING: 'Checking' }

/**
 * A picture per service, so the grid is quick to scan.
 * @type {Record<HealthService, IconName>}
 */
const SERVICE_ICONS = {
  gateway: 'globe',
  identity: 'lock',
  customer: 'user',
  vendor: 'store',
  restaurant: 'cloche',
  delivery: 'scooter',
  'catalog-command': 'pencil',
  'catalog-query': 'search',
  inventory: 'box',
  pricing: 'tag',
  cart: 'cart',
  order: 'receipt',
  payment: 'card',
  notification: 'bell',
  review: 'star',
  reporting: 'chart',
}

/**
 * Full class names per health colour (so Tailwind sees them): the pill, its dot, the tile's top bar and ring.
 * `ping` = the dot pulses (live states: up, down, restarting).
 * @type {Record<BadgeTone, { pill: string, dot: string, ping: boolean, bar: string, ring: string }>}
 */
const HEALTH_TONES = {
  green: {
    pill: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
    dot: 'bg-emerald-500',
    ping: true,
    bar: 'from-emerald-400 to-teal-500',
    ring: 'ring-slate-200/80',
  },
  red: {
    pill: 'bg-red-50 text-red-700 ring-red-200',
    dot: 'bg-red-500',
    ping: true,
    bar: 'from-rose-500 to-red-500',
    ring: 'ring-red-200',
  },
  amber: {
    pill: 'bg-amber-50 text-amber-800 ring-amber-200',
    dot: 'bg-amber-500',
    ping: false,
    bar: 'from-amber-400 to-orange-500',
    ring: 'ring-amber-200',
  },
  blue: {
    pill: 'bg-sky-50 text-sky-700 ring-sky-200',
    dot: 'bg-sky-500',
    ping: true,
    bar: 'from-sky-400 to-blue-500',
    ring: 'ring-sky-200',
  },
  violet: {
    pill: 'bg-violet-50 text-violet-700 ring-violet-200',
    dot: 'bg-violet-500',
    ping: false,
    bar: 'from-violet-400 to-fuchsia-500',
    ring: 'ring-violet-200',
  },
  gray: {
    pill: 'bg-slate-100 text-slate-600 ring-slate-200',
    dot: 'bg-slate-400',
    ping: false,
    bar: 'from-slate-300 to-slate-400',
    ring: 'ring-slate-200/80',
  },
}

/*
 * The small health pieces below are shared with the Debezium connectors and the dead letters (Operations), and with
 * the AI audit's model status, so every health state reads the same across the admin area.
 */

/**
 * The picture of a backend service (decorative).
 * @param {{ service: HealthService, className?: string }} props
 */
export function ServiceIcon({ service, className }) {
  return <Icon name={SERVICE_ICONS[service]} className={className} />
}

/**
 * A small dot in a health colour (decorative).
 * @param {{ tone: BadgeTone }} props
 */
export function HealthDot({ tone }) {
  return <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', HEALTH_TONES[tone].dot)} />
}

/**
 * @typedef {object} HealthTileProps
 * @property {BadgeTone} tone
 * @property {string} label Accessible name of the list item, e.g. "Identity service".
 * @property {string} [className]
 * @property {CSSProperties} [style]
 * @property {ReactNode} children
 */

/**
 * A list-item tile with a colour bar on top in its health colour (service and connector grids).
 * @param {HealthTileProps} props
 */
export function HealthTile({ tone, label, className, style, children }) {
  const look = HEALTH_TONES[tone]
  return (
    <li
      aria-label={label}
      style={style}
      className={cn(
        'relative animate-fade-up overflow-hidden rounded-2xl bg-white p-4 pt-5 shadow-sm ring-1 transition-shadow duration-300 hover:shadow-md motion-reduce:animate-none',
        look.ring,
        className,
      )}
    >
      <span aria-hidden="true" className={cn('absolute inset-x-0 top-0 h-1 bg-gradient-to-r', look.bar)} />
      {children}
    </li>
  )
}

/**
 * A health state as a coloured pill with a dot; live states pulse gently (not with reduced motion).
 * @param {{ tone: BadgeTone, children: ReactNode }} props
 */
export function HealthPill({ tone, children }) {
  const look = HEALTH_TONES[tone]
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold whitespace-nowrap ring-1 ring-inset',
        look.pill,
      )}
    >
      <span aria-hidden="true" className="relative flex size-2">
        {look.ping && (
          <span
            className={cn(
              'absolute inline-flex size-full animate-ping rounded-full opacity-60 motion-reduce:animate-none',
              look.dot,
            )}
          />
        )}
        <span className={cn('relative inline-flex size-2 rounded-full', look.dot)} />
      </span>
      {children}
    </span>
  )
}

const BANNER_LOOK = {
  ok: {
    box: 'from-emerald-50 via-white to-teal-50 ring-emerald-200',
    bar: 'from-emerald-400 to-teal-500',
    text: 'text-emerald-900',
    icon: 'checkCircle',
  },
  problem: {
    box: 'from-red-50 via-white to-rose-50 ring-red-200',
    bar: 'from-red-400 to-rose-500',
    text: 'text-red-900',
    icon: 'warning',
  },
  checking: {
    box: 'from-slate-50 via-white to-sky-50 ring-slate-200',
    bar: 'from-slate-300 to-sky-400',
    text: 'text-slate-700',
    icon: 'pulse',
  },
}

/**
 * @typedef {object} HealthBannerProps
 * @property {keyof typeof BANNER_LOOK} state
 * @property {string} message The headline ("All services are operational.").
 * @property {number} [checkedAt] Time of the newest check (ms); 0 = none yet.
 * @property {ReactNode} [action] The refresh button, shown next to the banner.
 */

/**
 * The overall verdict above a health grid (a live `status` region), with the refresh button next to it.
 * @param {HealthBannerProps} props
 */
export function HealthBanner({ state, message, checkedAt = 0, action }) {
  const look = BANNER_LOOK[state]
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
      <div
        role="status"
        className={cn(
          'relative flex min-w-0 flex-1 items-center gap-3 overflow-hidden rounded-2xl bg-gradient-to-r p-4 pl-5 ring-1 ring-inset',
          look.box,
        )}
      >
        <span aria-hidden="true" className={cn('absolute inset-y-0 left-0 w-1 bg-gradient-to-b', look.bar)} />
        <span
          aria-hidden="true"
          className="flex size-11 shrink-0 items-center justify-center rounded-2xl bg-white shadow-sm ring-1 ring-slate-100"
        >
          {state === 'checking' ? <Spinner size="sm" /> : <Icon name={look.icon} className="size-6" />}
        </span>
        <div className="min-w-0">
          <p className={cn('text-sm font-semibold', look.text)}>{message}</p>
          {checkedAt > 0 && (
            <p className="mt-0.5 text-xs text-slate-500">
              Last checked {new Date(checkedAt).toLocaleTimeString('en-IN')}.
            </p>
          )}
        </div>
      </div>
      {action}
    </div>
  )
}

/** Polls `GET /api/health/{service}` for every Phase 2–7 service. */
export function SystemStatusGrid() {
  const results = useQueries({
    queries: HEALTH_SERVICES.map((service) => ({
      queryKey: ['system', 'health', service],
      queryFn: () => fetchServiceHealth(service),
      refetchInterval: SYSTEM_STATUS_POLL_MS,
      staleTime: 0,
      retry: false,
    })),
  })

  const states = results.map((result) =>
    result.isPending ? 'CHECKING' : result.isError ? 'UNREACHABLE' : result.data.status === 'UP' ? 'UP' : 'DOWN',
  )
  const problems = states.filter((state) => state === 'DOWN' || state === 'UNREACHABLE').length
  const checking = states.includes('CHECKING')
  const lastChecked = Math.max(...results.map((result) => Math.max(result.dataUpdatedAt, result.errorUpdatedAt)))
  const fetching = results.some((result) => result.isFetching)
  const count = (state) => states.filter((value) => value === state).length

  return (
    <div className="space-y-4">
      <HealthBanner
        state={checking ? 'checking' : problems === 0 ? 'ok' : 'problem'}
        message={
          checking
            ? 'Checking services…'
            : problems === 0
              ? 'All services are operational.'
              : `${problems} of ${HEALTH_SERVICES.length} services need attention.`
        }
        checkedAt={checking ? 0 : lastChecked}
        action={
          <Button
            variant="secondary"
            onClick={() => results.forEach((result) => void result.refetch())}
            disabled={fetching}
            className="shrink-0"
          >
            {fetching ? <Spinner size="sm" /> : <Icon name="refresh" className="size-4" />}
            Refresh now
          </Button>
        }
      />

      {/* Counts of the tiles below (no extra request). */}
      <dl aria-label="Service health summary" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard icon="grid" accent="sky" label="Services" value={HEALTH_SERVICES.length} />
        <StatCard icon="checkCircle" accent="emerald" label="Up" value={count('UP')} />
        <StatCard
          icon="xCircle"
          accent="rose"
          label="Down"
          value={count('DOWN')}
          valueClassName={count('DOWN') > 0 ? 'text-red-700' : undefined}
        />
        <StatCard
          icon="warning"
          accent="amber"
          label="Unreachable"
          value={count('UNREACHABLE')}
          valueClassName={count('UNREACHABLE') > 0 ? 'text-amber-700' : undefined}
        />
      </dl>

      <ul
        aria-label="Service health checks"
        className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3 [&>*]:min-w-0"
      >
        {HEALTH_SERVICES.map((service, index) => {
          const state = states[index] ?? 'CHECKING'
          const error = results[index]?.error
          return (
            <HealthTile
              key={service}
              tone={STATE_TONE[state]}
              label={`${SERVICE_LABELS[service].name} service`}
              style={{ animationDelay: `${Math.min(index, 12) * 30}ms` }}
              className="group"
            >
              <div className="flex items-start gap-3">
                <span
                  aria-hidden="true"
                  className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-slate-50 to-white shadow-sm ring-1 ring-slate-100 transition-transform duration-300 group-hover:scale-110 motion-reduce:transition-none"
                >
                  <ServiceIcon service={service} className="size-6" />
                </span>
                <div className="min-w-0 flex-1">
                  <h3 className="text-sm font-semibold break-words text-slate-900">{SERVICE_LABELS[service].name}</h3>
                  <p className="text-xs text-slate-500">{SERVICE_LABELS[service].description}</p>
                </div>
                <HealthPill tone={STATE_TONE[state]}>{STATE_LABEL[state]}</HealthPill>
              </div>
              {state === 'UNREACHABLE' && error && (
                <p className="mt-3 rounded-lg bg-red-50 px-2.5 py-2 text-xs text-red-700 ring-1 ring-red-100 ring-inset">
                  {error.message}
                  {error instanceof ApiError && error.correlationId && (
                    <span className="mt-1 block font-mono break-all text-red-500/80">Ref: {error.correlationId}</span>
                  )}
                </p>
              )}
              <p className="mt-3 truncate font-mono text-[11px] text-slate-400">GET /api/health/{service}</p>
            </HealthTile>
          )
        })}
      </ul>
    </div>
  )
}
