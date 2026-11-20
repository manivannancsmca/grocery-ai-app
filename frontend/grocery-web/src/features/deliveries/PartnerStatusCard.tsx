import { useId, useState } from 'react'
import { Spinner } from '../../shared/components/ui'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatTime } from '../../shared/utils/format'
import type { AvailabilityToggle } from './useAvailabilityToggle'
import type { LocationSharing } from './useLocationReporter'

function sentAt(ms: number): string {
  return formatTime(new Date(ms).toISOString())
}

/** Location sharing cannot go on: the notice under the banner says why and what to do. */
function sharingBlocked(sharing: LocationSharing): boolean {
  return sharing.stopped !== null || sharing.problem === 'denied' || sharing.problem === 'unsupported'
}

/** How location sharing is going while online, in one short line (the problems get their own notice). */
function SharingLine({ sharing, simulating }: { sharing: LocationSharing; simulating: boolean }) {
  let line
  if (sharingBlocked(sharing)) {
    line = (
      <p className="flex items-center gap-2 font-medium text-red-700">
        <Icon name="alertCircle" className="size-4" /> Your location is not shared — see the message below.
      </p>
    )
  } else if (sharing.problem === 'unavailable') {
    line = (
      <p className="flex items-center gap-2 text-amber-800">
        <Spinner size="sm" /> Waiting for a GPS signal… Make sure location services are on.
      </p>
    )
  } else if (sharing.lastSentAt !== null) {
    line = (
      <p className="flex items-center gap-2 text-emerald-800">
        <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-emerald-500" />
        {simulating ? 'Sharing your simulated location' : 'Sharing your location'} · last sent {sentAt(sharing.lastSentAt)}
      </p>
    )
  } else if (sharing.position === null) {
    line = (
      <p className="flex items-center gap-2 text-slate-600">
        <Spinner size="sm" /> {simulating ? 'Set a position in the location simulator to share it.' : 'Finding your location…'}
      </p>
    )
  } else {
    line = (
      <p className="flex items-center gap-2 text-slate-600">
        <Spinner size="sm" /> Sending your location…
      </p>
    )
  }
  return (
    <div role="status" className="space-y-0.5 text-sm">
      {line}
      {sharing.sendError && <p className="text-xs text-amber-800">The last location update failed ({sharing.sendError}). Retrying.</p>}
    </div>
  )
}

interface PartnerStatusCardProps {
  online: boolean
  hasActiveDelivery: boolean
  /** The shared online / offline mutation (also behind the "Go online" button). */
  control: Pick<AvailabilityToggle, 'pending' | 'toggle'>
  sharing: LocationSharing
  /** Positions come from the location simulator instead of the device. */
  simulating: boolean
}

/**
 * The partner's availability, on a white panel in the banner: whether they are online (and on a delivery), a large
 * Online / Offline switch, and — while online — how location sharing is going, in one line.
 */
export function PartnerStatusCard({ online, hasActiveDelivery, control, sharing, simulating }: PartnerStatusCardProps) {
  const { pending, toggle } = control
  const title = !online ? 'You are offline' : hasActiveDelivery ? 'On a delivery' : 'You are online'
  const subtitle = !online
    ? 'Go online to receive delivery offers near you.'
    : hasActiveDelivery
      ? 'Finish this delivery; new offers come after it.'
      : 'Waiting for delivery offers near you.'

  return (
    <section
      aria-label="Availability"
      className={cn(
        'flex h-full flex-col justify-center gap-3 rounded-2xl bg-white p-3.5 text-slate-900 shadow-lg shadow-teal-950/20 ring-1 transition-colors duration-300 sm:p-4',
        online ? 'ring-emerald-200' : 'ring-white/70',
      )}
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span
            aria-hidden="true"
            className={cn(
              'relative hidden size-11 shrink-0 items-center justify-center rounded-xl ring-1 sm:flex',
              online ? 'bg-gradient-to-br from-emerald-50 to-teal-50 ring-emerald-100' : 'bg-slate-50 ring-slate-200',
            )}
          >
            <Icon name={online ? 'pulse' : 'pauseCircle'} className="size-6" />
            {online && (
              <span className="absolute -top-1 -right-1 flex size-3">
                <span className="absolute inset-0 animate-ping rounded-full bg-emerald-400 opacity-75 motion-reduce:hidden" />
                <span className="relative size-3 rounded-full bg-emerald-500 ring-2 ring-white" />
              </span>
            )}
          </span>
          <div className="min-w-0">
            <p className="text-base leading-tight font-bold">{title}</p>
            <p className="mt-0.5 text-sm leading-snug text-slate-600">{subtitle}</p>
          </div>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={online}
          aria-label="Online"
          aria-busy={pending || undefined}
          disabled={pending}
          onClick={toggle}
          className={cn(
            'relative inline-flex h-12 shrink-0 items-center justify-center gap-2.5 rounded-full pr-4 pl-2 text-base font-bold transition-all duration-200 active:scale-[0.98]',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 disabled:cursor-wait disabled:opacity-70',
            online
              ? 'bg-emerald-600 text-white shadow-md shadow-emerald-700/30 hover:bg-emerald-700'
              : 'bg-slate-100 text-slate-700 ring-1 ring-slate-300 ring-inset hover:bg-white hover:ring-emerald-400',
          )}
        >
          <span
            aria-hidden="true"
            className={cn('relative inline-flex h-8 w-14 shrink-0 items-center rounded-full transition-colors', online ? 'bg-emerald-400/70' : 'bg-slate-300')}
          >
            <span className={cn('inline-block size-7 rounded-full bg-white shadow transition-transform', online ? 'translate-x-6.5' : 'translate-x-0.5')} />
          </span>
          <span className="min-w-14 text-left">{pending ? <Spinner size="sm" /> : online ? 'Online' : 'Offline'}</span>
        </button>
      </div>
      {online && (
        <div className="border-t border-slate-100 pt-2.5">
          <SharingLine sharing={sharing} simulating={simulating} />
        </div>
      )}
    </section>
  )
}

interface NoticeProps {
  tone: 'danger' | 'warning'
  role: 'alert' | 'status'
  title: string
  children: string
  /** Steps behind a "How to fix" toggle. */
  steps?: readonly string[]
  /** A last line under the steps. */
  extra?: string | null
}

const NOTICE_TONES = {
  danger: { box: 'border-red-200 bg-red-50/90 text-red-800', title: 'text-red-900', icon: 'alertCircle', button: 'text-red-800 ring-red-200 hover:bg-red-100' },
  warning: { box: 'border-amber-200 bg-amber-50/90 text-amber-800', title: 'text-amber-900', icon: 'warning', button: 'text-amber-900 ring-amber-200 hover:bg-amber-100' },
} as const satisfies Record<string, { box: string; title: string; icon: IconName; button: string }>

/** A slim banner: what is wrong in one line and, when there is something to do, the steps behind "How to fix". */
function Notice({ tone, role, title, children, steps, extra }: NoticeProps) {
  const [open, setOpen] = useState(false)
  const stepsId = useId()
  const colours = NOTICE_TONES[tone]
  return (
    <div role={role} className={cn('animate-fade-in rounded-2xl border px-4 py-3 text-sm', colours.box)}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Icon name={colours.icon} className="size-5" />
        <p className="min-w-0 flex-1 basis-56">
          <span className={cn('font-semibold', colours.title)}>{title}</span>
          <span className="mx-1.5 hidden sm:inline" aria-hidden="true">
            ·
          </span>
          <span className="block sm:inline">{children}</span>
        </p>
        {steps && (
          <button
            type="button"
            aria-expanded={open}
            aria-controls={stepsId}
            onClick={() => setOpen((value) => !value)}
            className={cn(
              'inline-flex h-8 shrink-0 items-center gap-1 rounded-lg bg-white/70 px-2.5 text-sm font-semibold ring-1 transition-colors ring-inset focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current',
              colours.button,
            )}
          >
            How to fix
            <Icon name="chevronRight" className={cn('size-4 transition-transform', open ? '-rotate-90' : 'rotate-90')} />
          </button>
        )}
      </div>
      {steps && open && (
        <div id={stepsId} className="mt-2.5 border-t border-current/10 pt-2.5 sm:pl-8">
          <ol className="list-decimal space-y-1 pl-5">
            {steps.map((step) => (
              <li key={step}>{step}</li>
            ))}
          </ol>
          {extra && <p className="mt-2 font-medium">{extra}</p>}
        </div>
      )}
    </div>
  )
}

const BLOCKED_STEPS = [
  'Allow location for this site: tap the lock icon next to the address, then Location → Allow.',
  'Make sure location services are on for your phone or computer.',
  'Go offline and online again.',
] as const

/**
 * Why the partner's location is not being shared while online, as a compact banner: blocked permission (with the steps
 * to fix it behind a toggle), a browser without location, or the server stopping the reports. Nothing otherwise.
 */
export function LocationNotice({ sharing, simulatorAvailable }: { sharing: LocationSharing; simulatorAvailable: boolean }) {
  if (sharing.stopped === 'offline') {
    return (
      <Notice tone="warning" role="status" title="Location sharing stopped">
        The server has you offline, so your location is no longer sent. Go online again to keep receiving offers.
      </Notice>
    )
  }
  if (sharing.stopped === 'not-active') {
    return (
      <Notice tone="warning" role="status" title="Your account is waiting for approval">
        Your location is not shared until an admin approves your partner profile.
      </Notice>
    )
  }
  if (sharing.problem === 'denied') {
    return (
      <Notice
        tone="danger"
        role="alert"
        title="Location access is blocked"
        steps={BLOCKED_STEPS}
        extra={simulatorAvailable ? 'Testing on a computer? Use the location simulator below.' : null}
      >
        We need your location to offer you nearby deliveries and to show customers where their order is.
      </Notice>
    )
  }
  if (sharing.problem === 'unsupported') {
    return (
      <Notice tone="danger" role="alert" title="This browser cannot share your location">
        Use the app on a phone with location services, or a browser that supports location sharing.
      </Notice>
    )
  }
  return null
}
