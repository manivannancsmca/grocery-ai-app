import { useId } from 'react'
import { useSearchParams } from 'react-router'
import { ACCENTS, waitingSince } from '../../../features/stakeholders/admin/approvalLook'
import { usePendingSummary, useStatusCount } from '../../../features/stakeholders/admin/hooks'
import { PendingQueue } from '../../../features/stakeholders/admin/PendingQueue'
import { useNow } from '../../../shared/hooks/useNow'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { partnerKind, restaurantKind, vendorKind } from '../stakeholderKinds'

/** @typedef {import('../../../features/stakeholders/admin/approvalLook').ApprovalAccent} ApprovalAccent */
/** @typedef {import('../../../features/stakeholders/admin/kind').StakeholderKind} StakeholderKind */
/** @typedef {import('../../../features/stakeholders/types').StakeholderLifecycle} StakeholderLifecycle */
/** @typedef {import('../../../shared/icons').IconName} IconName */

/** @typedef {'vendors' | 'restaurants' | 'delivery-partners'} ApprovalTab */

/**
 * @param {string | null} value
 * @returns {value is ApprovalTab}
 */
function isApprovalTab(value) {
  return value === 'vendors' || value === 'restaurants' || value === 'delivery-partners'
}

/**
 * The three onboarding queues in tile order, each with its picture and colour family.
 * @type {readonly ApprovalTab[]}
 */
const QUEUE_ORDER = ['vendors', 'restaurants', 'delivery-partners']
/** @type {Record<ApprovalTab, { icon: IconName, accent: ApprovalAccent, hint: string }>} */
const QUEUES = {
  vendors: { icon: 'store', accent: 'amber', hint: 'Grocery stores' },
  restaurants: { icon: 'cloche', accent: 'rose', hint: 'Food & menus' },
  'delivery-partners': { icon: 'scooter', accent: 'sky', hint: 'Riders & vehicles' },
}

/**
 * The tile and the banner only read the lifecycle fields every type has (count, oldest submission, plural), so the
 * three kinds can be handled as one: the cast only widens the item type of their display callbacks.
 * @param {ApprovalTab} tab
 * @returns {StakeholderKind<StakeholderLifecycle>}
 */
function kindOf(tab) {
  const kind = tab === 'vendors' ? vendorKind : tab === 'restaurants' ? restaurantKind : partnerKind
  return kind
}

/**
 * "Today", "2 days ago", "None waiting".
 * @param {string | null} oldest
 * @param {number} now
 * @returns {string}
 */
function oldestLabel(oldest, now) {
  const waited = waitingSince(oldest, now).days
  if (waited === null) return 'None waiting'
  return waited === 0 ? 'Today' : `${waited} ${waited === 1 ? 'day' : 'days'} ago`
}

/** The queue totals across the three types, for the banner: how many wait, and since when the oldest one. */
function useQueueTotals() {
  const vendors = usePendingSummary(vendorKind)
  const restaurants = usePendingSummary(restaurantKind)
  const partners = usePendingSummary(partnerKind)
  const all = [vendors.data, restaurants.data, partners.data]
  const loaded = all.every((summary) => summary !== undefined)
  const total = all.reduce((sum, summary) => sum + (summary?.count ?? 0), 0)
  const oldest = all
    .map((summary) => summary?.oldestSubmittedAt)
    .filter((at) => typeof at === 'string')
    .sort()[0]
  return { loaded, total, oldest: oldest ?? null }
}

function Hero() {
  const { loaded, total, oldest } = useQueueTotals()
  const now = useNow(60_000)
  const overdue = (waitingSince(oldest, now).days ?? 0) >= 3
  return (
    <section
      aria-labelledby="approvals-title"
      className="relative overflow-hidden rounded-3xl bg-gradient-to-br from-emerald-600 via-teal-600 to-sky-700 px-6 py-7 text-white shadow-xl shadow-emerald-900/20 sm:px-8 sm:py-8"
    >
      <div aria-hidden="true" className="pointer-events-none absolute inset-0">
        <div className="absolute -top-24 -right-16 size-72 rounded-full bg-white/10 blur-2xl" />
        <div className="absolute -bottom-28 left-1/4 size-64 rounded-full bg-amber-300/25 blur-3xl" />
        <div className="absolute top-6 right-1/3 size-40 rounded-full bg-fuchsia-400/20 blur-3xl" />
        <div className="absolute inset-0 [background-image:radial-gradient(rgb(255_255_255/0.14)_1px,transparent_1px)] [background-size:20px_20px] [mask-image:linear-gradient(to_left,black,transparent_75%)]" />
      </div>

      <div className="relative flex flex-col gap-6 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex min-w-0 items-start gap-4">
          <span
            aria-hidden="true"
            className="hidden size-16 shrink-0 animate-float items-center justify-center rounded-2xl bg-white shadow-lg shadow-emerald-950/20 motion-reduce:animate-none sm:flex"
          >
            <Icon name="shield" className="size-9" />
          </span>
          <div className="min-w-0">
            <p className="inline-flex items-center gap-1.5 rounded-full bg-white/15 px-3 py-1 text-xs font-semibold tracking-wide uppercase ring-1 ring-white/25 ring-inset">
              <Icon name="sparkle" className="size-3.5" />
              Partner onboarding
            </p>
            <h1 id="approvals-title" className="mt-3 text-3xl font-bold tracking-tight sm:text-4xl">
              Approvals
            </h1>
            <p className="mt-2 max-w-xl text-sm text-emerald-50/90 sm:text-base">
              Review new vendors, restaurants and delivery partners, then approve them or reject them with a reason.
            </p>
          </div>
        </div>

        <dl className="grid shrink-0 grid-cols-2 gap-3 lg:w-[24rem]">
          <div className="rounded-2xl bg-white/15 p-4 ring-1 ring-white/25 backdrop-blur-sm ring-inset">
            <dt className="flex items-center gap-1.5 text-xs font-medium text-emerald-50/90">
              <Icon name="clipboard" className="size-4" />
              Waiting for review
            </dt>
            <dd className="mt-1.5 text-4xl leading-none font-bold tabular-nums">{loaded ? total : '—'}</dd>
          </div>
          <div className="rounded-2xl bg-white/15 p-4 ring-1 ring-white/25 backdrop-blur-sm ring-inset">
            <dt className="flex items-center gap-1.5 text-xs font-medium text-emerald-50/90">
              <Icon name="hourglass" className="size-4" />
              Oldest submission
            </dt>
            <dd className="mt-1.5 flex items-center gap-2 text-2xl leading-none font-bold">
              {loaded ? oldestLabel(oldest, now) : '—'}
              {loaded && overdue && (
                <span className="rounded-full bg-rose-500/90 px-2 py-0.5 text-[11px] font-semibold tracking-wide uppercase">
                  Overdue
                </span>
              )}
            </dd>
          </div>
        </dl>
      </div>
    </section>
  )
}

/**
 * @typedef {object} QueueTabProps
 * @property {ApprovalTab} tab
 * @property {IconName} icon
 * @property {ApprovalAccent} accent
 * @property {string} hint
 * @property {boolean} selected
 * @property {string} tabId
 * @property {string} panelId
 * @property {() => void} onSelect
 */

/**
 * A colourful tile per stakeholder type: its picture, how many wait (large) and how many are approved.
 * @param {QueueTabProps} props
 */
function QueueTab({ tab, icon, accent, hint, selected, tabId, panelId, onSelect }) {
  const kind = kindOf(tab)
  const pending = usePendingSummary(kind)
  const approved = useStatusCount(kind, 'APPROVED')
  const look = ACCENTS[accent]
  const waiting = pending.data?.count
  return (
    <button
      id={tabId}
      type="button"
      role="tab"
      aria-selected={selected}
      aria-controls={panelId}
      tabIndex={selected ? 0 : -1}
      onClick={onSelect}
      className={cn(
        'group relative overflow-hidden rounded-2xl p-4 text-left transition-all duration-300 focus-visible:outline-2 focus-visible:outline-offset-2 motion-reduce:transition-none',
        look.focus,
        selected
          ? cn(
              '-translate-y-0.5 bg-gradient-to-br shadow-lg ring-2 motion-reduce:translate-y-0',
              look.soft,
              look.ring,
              look.shadow,
            )
          : 'bg-white shadow-sm ring-1 ring-slate-200/80 hover:-translate-y-0.5 hover:shadow-md hover:ring-slate-300 motion-reduce:hover:translate-y-0',
      )}
    >
      <div
        aria-hidden="true"
        className={cn(
          'pointer-events-none absolute -right-8 -bottom-10 size-28 rounded-full blur-2xl transition-opacity',
          look.glow,
          selected ? 'opacity-100' : 'opacity-0 group-hover:opacity-70',
        )}
      />
      <div className="relative flex items-center gap-3">
        <span
          aria-hidden="true"
          className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-white shadow-md ring-1 ring-slate-100 transition-transform duration-300 group-hover:scale-110 group-hover:-rotate-6 motion-reduce:transition-none"
        >
          <Icon name={icon} className="size-7" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-semibold text-slate-900">{kind.plural}</span>
          <span className="block truncate text-xs text-slate-500">
            {hint}
            {approved.data !== undefined && <> · {approved.data} approved</>}
          </span>
        </span>
        <span className="shrink-0 text-right">
          <span
            className={cn('block text-3xl leading-none font-bold tabular-nums', waiting ? look.text : 'text-slate-300')}
          >
            {waiting ?? '–'}
          </span>
          <span className="mt-1 block text-[11px] font-semibold tracking-wide text-slate-500 uppercase">waiting</span>
        </span>
      </div>
      <span
        aria-hidden="true"
        className={cn(
          'absolute inset-x-5 bottom-0 h-1 rounded-t-full bg-gradient-to-r transition-opacity',
          look.gradient,
          selected ? 'opacity-100' : 'opacity-0',
        )}
      />
    </button>
  )
}

/**
 * Onboarding submissions waiting for a decision (`/admin/approvals`, `?type=` selects the queue): a banner with the
 * totals, a colourful tile per stakeholder type (WAI-ARIA tabs: arrow keys, Home and End move between them) and the
 * selected queue as cards, oldest first. "Review" opens the drawer to approve or reject with a reason.
 */
export function ApprovalsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const typeParam = searchParams.get('type')
  const tab = isApprovalTab(typeParam) ? typeParam : 'vendors'
  const baseId = useId()
  const tabId = (id) => `${baseId}-tab-${id}`
  const panelId = `${baseId}-panel`
  const current = QUEUES[tab]

  function select(next, focus = false) {
    setSearchParams({ type: next }, { replace: true })
    if (focus) document.getElementById(tabId(next))?.focus()
  }

  function onKeyDown(event) {
    const index = QUEUE_ORDER.indexOf(tab)
    const count = QUEUE_ORDER.length
    const target =
      event.key === 'ArrowRight' || event.key === 'ArrowDown'
        ? QUEUE_ORDER[(index + 1) % count]
        : event.key === 'ArrowLeft' || event.key === 'ArrowUp'
          ? QUEUE_ORDER[(index - 1 + count) % count]
          : event.key === 'Home'
            ? QUEUE_ORDER[0]
            : event.key === 'End'
              ? QUEUE_ORDER[count - 1]
              : undefined
    if (!target) return
    event.preventDefault()
    select(target, true)
  }

  return (
    <div className="space-y-6">
      <Hero />

      <div
        role="tablist"
        aria-label="Stakeholder type"
        onKeyDown={onKeyDown}
        className="grid grid-cols-1 gap-3 sm:grid-cols-3 [&>*]:min-w-0"
      >
        {QUEUE_ORDER.map((id) => (
          <QueueTab
            key={id}
            tab={id}
            icon={QUEUES[id].icon}
            accent={QUEUES[id].accent}
            hint={QUEUES[id].hint}
            selected={id === tab}
            tabId={tabId(id)}
            panelId={panelId}
            onSelect={() => select(id)}
          />
        ))}
      </div>

      <section id={panelId} role="tabpanel" aria-labelledby={tabId(tab)} className="animate-fade-in space-y-4">
        <div className="flex flex-wrap items-end justify-between gap-2">
          <h2 className="flex items-center gap-2 text-lg font-semibold text-slate-900">
            <Icon name={current.icon} className="size-6" />
            {kindOf(tab).plural} waiting for review
          </h2>
          <p className="flex items-center gap-1.5 text-xs font-medium text-slate-500">
            <Icon name="clock" className="size-4" />
            Oldest first
          </p>
        </div>
        {tab === 'vendors' && <PendingQueue key="vendors" kind={vendorKind} accent="amber" />}
        {tab === 'restaurants' && <PendingQueue key="restaurants" kind={restaurantKind} accent="rose" />}
        {tab === 'delivery-partners' && <PendingQueue key="delivery-partners" kind={partnerKind} accent="sky" />}
      </section>
    </div>
  )
}
