import { useEffect, useEffectEvent, useId, useState } from 'react'
import { LazyDeliveryMap } from '../../shared/components/map/LazyDeliveryMap'
import type { GeoPoint, MapPoint } from '../../shared/components/map/types'
import { Badge, Button, Switch } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { DRIVE_STEP_MS, DRIVE_STEPS, driveStep, formatCoordinates } from './simulator'

interface LocationSimulatorProps {
  /** Positions come from here instead of the device. */
  enabled: boolean
  onEnabledChange: (enabled: boolean) => void
  /** The panel is unfolded (folded by default: it is a testing tool). */
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The current (simulated) position. */
  position: GeoPoint | null
  /** A new simulated position: fed to the same location reporter as the device's. */
  onPosition: (position: GeoPoint) => void
  store: (GeoPoint & { name: string }) | null
  customer: (GeoPoint & { name: string }) | null
  /** Where "Drive" goes: the store before pickup, the customer after it. */
  destination: 'store' | 'customer' | null
  className?: string
}

interface Route {
  from: GeoPoint
  to: GeoPoint
  label: string
}

/**
 * Development / testing only (`import.meta.env.DEV` or a `VITE_LOCATION_SIMULATOR=true` build): play a delivery partner from a desktop
 * far from the test data. Folded into one line until opened. Set the position by clicking the map or "At the store" /
 * "At the customer"; "Drive" moves it along a straight line, one step every 2 s. While online, positions are reported
 * like real ones.
 */
export function LocationSimulator({
  enabled,
  onEnabledChange,
  open,
  onOpenChange,
  position,
  onPosition,
  store,
  customer,
  destination,
  className,
}: LocationSimulatorProps) {
  const [route, setRoute] = useState<Route | null>(null)
  const [step, setStep] = useState(0)
  const panelId = useId()
  const target = destination === 'store' ? store : destination === 'customer' ? customer : null

  const advance = useEffectEvent(() => {
    if (!route) return
    const next = step + 1
    onPosition(driveStep(route.from, route.to, next))
    setStep(next)
    if (next >= DRIVE_STEPS) setRoute(null)
  })

  useEffect(() => {
    if (!route) return
    const timer = setInterval(() => advance(), DRIVE_STEP_MS)
    return () => clearInterval(timer)
  }, [route])

  function place(point: GeoPoint) {
    setRoute(null)
    onPosition({ latitude: point.latitude, longitude: point.longitude })
  }

  function drive() {
    if (!position || !target) return
    setStep(0)
    setRoute({ from: position, to: { latitude: target.latitude, longitude: target.longitude }, label: destination === 'store' ? 'the store' : 'the customer' })
  }

  function toggle(on: boolean) {
    setRoute(null)
    onEnabledChange(on)
  }

  const points: MapPoint[] = [
    ...(store ? [{ id: 'store', kind: 'store' as const, label: `${store.name} (store)`, latitude: store.latitude, longitude: store.longitude }] : []),
    ...(customer ? [{ id: 'home', kind: 'home' as const, label: `${customer.name} (customer)`, latitude: customer.latitude, longitude: customer.longitude }] : []),
    ...(position ? [{ id: 'me', kind: 'partner' as const, label: 'Simulated position', ...position }] : []),
  ]

  return (
    <section aria-label="Location simulator" className={cn('@container flex flex-col justify-center rounded-2xl border border-dashed border-violet-300 bg-violet-50/50', className)}>
      <h2>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => onOpenChange(!open)}
          className="flex w-full items-center gap-3 rounded-2xl px-3.5 py-3 text-left transition-colors hover:bg-violet-100/40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-violet-500"
        >
          <span aria-hidden="true" className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-violet-100">
            <Icon name="crosshair" className="size-5" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-sm font-semibold text-slate-900">
              Location simulator <Badge tone="violet">Testing only</Badge>
            </span>
            <span className="block text-xs text-slate-500">
              {enabled ? 'On — your position comes from here, not from GPS.' : 'Play a delivery partner from any computer.'}
            </span>
          </span>
          <Icon name="chevronRight" className={cn('size-5 text-slate-500 transition-transform', open ? '-rotate-90' : 'rotate-90')} />
        </button>
      </h2>

      {open && (
        <div id={panelId} className="grid gap-4 border-t border-dashed border-violet-200 px-3.5 py-4 @3xl:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
          <div className="min-w-0 space-y-3">
            <p className="text-sm text-slate-600">Positions set here are reported like real GPS positions while you are online.</p>
            <Switch label="Use simulated location" checked={enabled} onChange={toggle} description="Turns the device's GPS off for this page." />
            {enabled && (
              <>
                <p role="status" className="text-sm text-slate-700">
                  {position ? (
                    <>
                      Simulated position: <span className="font-mono">{formatCoordinates(position)}</span>
                    </>
                  ) : (
                    'No position yet — click the map or choose a place below.'
                  )}
                </p>
                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" size="sm" disabled={!store} onClick={() => store && place(store)}>
                    At the store
                  </Button>
                  <Button variant="secondary" size="sm" disabled={!customer} onClick={() => customer && place(customer)}>
                    At the customer
                  </Button>
                  {route ? (
                    <Button size="sm" variant="danger" onClick={() => setRoute(null)}>
                      Stop driving
                    </Button>
                  ) : (
                    <Button size="sm" disabled={!position || !target} onClick={drive}>
                      {target ? `Drive to ${destination === 'store' ? 'the store' : 'the customer'}` : 'Drive'}
                    </Button>
                  )}
                </div>
                {route && (
                  <p className="text-sm text-violet-800" aria-live="polite">
                    Driving to {route.label}… step {step} of {DRIVE_STEPS}
                  </p>
                )}
                {!target && <p className="text-xs text-slate-500">“Drive” needs an active delivery: it drives to its store, then to its customer.</p>}
              </>
            )}
          </div>
          {enabled && (
            <div className="min-w-0">
              <LazyDeliveryMap points={points} label="Simulator map — click to set your position" onPick={place} className="h-56" />
            </div>
          )}
        </div>
      )}
    </section>
  )
}
