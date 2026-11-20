import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { ApiError, hasErrorCode } from '../../shared/api/problemDetail'
import { isGeoPoint } from '../../shared/components/map/types'
import { Alert, Button, ErrorState, Skeleton, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { useLiveNotifications } from '../../shared/realtime/useRealtime'
import { cn } from '../../shared/utils/cn'
import { ActiveDeliveryCard } from './ActiveDeliveryCard'
import { DELIVERY_ERRORS } from './api'
import { useActiveDelivery, usePartnerStatus } from './hooks'
import { LocationSimulator } from './LocationSimulator'
import { OffersSection } from './OffersSection'
import { PartnerHero } from './PartnerHero'
import { LocationNotice, PartnerStatusCard } from './PartnerStatusCard'
import { simulatorEnabled } from './simulator'
import { useAvailabilityToggle } from './useAvailabilityToggle'
import { useLocationReporter } from './useLocationReporter'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../delivery/api').PartnerView} PartnerView */
/** @typedef {import('./useAvailabilityToggle').AvailabilityToggle} AvailabilityToggle */

/** Partner notifications that take the active delivery away; they stay on screen until dismissed. */
const LOST_DELIVERY_TYPES = new Set(['DELIVERY_CANCELLED', 'DELIVERY_REASSIGNED'])

/**
 * Offline and free: one slim card with the big "Go online" button (the same action as the switch in the banner).
 * @param {{ control: Pick<AvailabilityToggle, 'pending' | 'goOnline'> }} props
 */
function OfflinePanel({ control }) {
  return (
    <section
      aria-label="Offline"
      className="flex flex-col items-center gap-4 rounded-2xl border border-slate-200/80 bg-gradient-to-r from-white via-white to-emerald-50/60 p-4 text-center shadow-sm sm:flex-row sm:p-5 sm:text-left"
    >
      <span
        aria-hidden="true"
        className="flex size-14 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-slate-100 to-emerald-50 ring-1 ring-slate-200"
      >
        <Icon name="pauseCircle" className="size-8" />
      </span>
      <div className="min-w-0 flex-1">
        <h2 className="text-base font-semibold text-slate-900">Ready for your next ride?</h2>
        <p className="mt-0.5 text-sm text-slate-600">
          Go online to start receiving delivery offers near you. Your location is shared only while you are online.
        </p>
      </div>
      <Button
        className="h-12 w-full px-6 text-base sm:w-auto"
        onClick={control.goOnline}
        disabled={control.pending}
        aria-busy={control.pending || undefined}
      >
        {control.pending ? (
          <Spinner size="sm" className="[&_svg]:text-white" />
        ) : (
          <Icon name="bolt" className="size-5" />
        )}
        Go online
      </Button>
    </section>
  )
}

/** The status panel's place in the banner while the status loads. */
function AvailabilityLoading() {
  return (
    <div className="flex h-full min-h-24 items-center justify-center rounded-2xl bg-white/95 p-4 shadow-lg shadow-teal-950/20">
      <Spinner size="lg" label="Loading your status" />
    </div>
  )
}

/**
 * The banner on top of whatever the screen shows below it.
 * @param {{ partner: PartnerView, availability?: ReactNode, children?: ReactNode }} props
 */
function Layout({ partner, availability, children }) {
  return (
    <div className="space-y-5">
      <PartnerHero partner={partner} availability={availability} />
      {children}
    </div>
  )
}

/**
 * The delivery partner's working screen (Phase 9), "now" first: the banner with the Online switch (live location
 * sharing) and today's numbers; a notice when the location cannot be shared; then the work, which follows the state —
 * a big "Go online" while offline, the offers (or a slim "looking for deliveries" card) while online and free, the
 * active delivery with its map otherwise; then the assistant (`assistant`) and — in development and test builds only —
 * the location simulator, folded away.
 * @param {{ partner: PartnerView, assistant?: ReactNode }} props
 */
export function PartnerApp({ partner, assistant }) {
  const [searchParams] = useSearchParams()
  const showSimulator = simulatorEnabled()
  // `?simulate=1` starts with the simulator on (and unfolded), where the simulator exists at all (development and test builds).
  const [simulating, setSimulating] = useState(() => showSimulator && searchParams.get('simulate') === '1')
  const [simulatorOpen, setSimulatorOpen] = useState(simulating)
  const status = usePartnerStatus()
  const active = useActiveDelivery()
  const online = status.data?.online ?? false
  const sharing = useLocationReporter({ online, source: simulating ? 'simulator' : 'device' })
  const control = useAvailabilityToggle(online, sharing)
  const [lostNotice, setLostNotice] = useState(null)

  useLiveNotifications((notification) => {
    if (LOST_DELIVERY_TYPES.has(notification.type) && !notification.read) setLostNotice(notification)
  })

  // One tree in every state, so the banner stays put (and does not animate in again) when the status arrives.
  if (status.isPending) return <Layout partner={partner} availability={<AvailabilityLoading />} />

  if (status.isError) {
    return (
      <Layout partner={partner}>
        {hasErrorCode(status.error, DELIVERY_ERRORS.partnerNotActive) ? (
          <Alert tone="info" title="Your account is waiting for approval">
            You can go online and receive delivery offers once an admin approves your partner profile.
          </Alert>
        ) : (
          <ErrorState
            title="Could not load your status"
            message={status.error.message}
            correlationId={status.error instanceof ApiError ? status.error.correlationId : null}
            onRetry={() => void status.refetch()}
          />
        )}
      </Layout>
    )
  }

  const delivery = active.data ?? null
  const pickup = delivery?.pickup && isGeoPoint(delivery.pickup) ? delivery.pickup : null
  const drop = delivery && isGeoPoint(delivery.drop) ? delivery.drop : null
  const lastKnown =
    status.data.location && isGeoPoint(status.data.location)
      ? { latitude: status.data.location.latitude, longitude: status.data.location.longitude }
      : null
  const destination = delivery?.status === 'ASSIGNED' ? 'store' : delivery?.status === 'PICKED_UP' ? 'customer' : null

  let work
  if (active.isPending) {
    work = (
      <div role="status" aria-label="Loading your delivery">
        <Skeleton className="h-28 rounded-2xl" />
      </div>
    )
  } else if (active.isError) {
    work = (
      <ErrorState
        title="Could not load your current delivery"
        message={active.error.message}
        correlationId={active.error instanceof ApiError ? active.error.correlationId : null}
        onRetry={() => void active.refetch()}
      />
    )
  } else if (delivery) {
    // "You" on the map: this phone's latest GPS fix, or the last position the server has until the first fix.
    work = <ActiveDeliveryCard delivery={delivery} position={sharing.position ?? lastKnown} />
  } else {
    work = online ? <OffersSection enabled={online} /> : <OfflinePanel control={control} />
  }

  return (
    <Layout
      partner={partner}
      availability={
        <PartnerStatusCard
          online={online}
          hasActiveDelivery={delivery !== null}
          control={control}
          sharing={sharing}
          simulating={simulating}
        />
      }
    >
      {online && <LocationNotice sharing={sharing} simulatorAvailable={showSimulator} />}
      {lostNotice && (
        <Alert
          tone="warning"
          role="alert"
          title={lostNotice.title}
          actions={
            <Button variant="secondary" size="sm" onClick={() => setLostNotice(null)}>
              OK
            </Button>
          }
        >
          {lostNotice.body || 'This delivery is no longer yours.'}
        </Alert>
      )}
      {work}
      {(assistant || showSimulator) && (
        // Side by side while both are one line; an unfolded simulator (controls and map) takes the full width.
        <div
          className={cn(
            'grid gap-4',
            Boolean(assistant) && showSimulator && !simulatorOpen && 'xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]',
          )}
        >
          {assistant}
          {showSimulator && (
            <LocationSimulator
              enabled={simulating}
              onEnabledChange={setSimulating}
              open={simulatorOpen}
              onOpenChange={setSimulatorOpen}
              position={sharing.position}
              onPosition={(point) => sharing.report(point)}
              store={pickup ? { name: pickup.name, latitude: pickup.latitude, longitude: pickup.longitude } : null}
              customer={drop ? { name: drop.recipientName, latitude: drop.latitude, longitude: drop.longitude } : null}
              destination={destination}
            />
          )}
        </div>
      )}
    </Layout>
  )
}
