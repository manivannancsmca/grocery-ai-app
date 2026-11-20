import type { TrackingView } from './api'

/**
 * The arrival estimate the order page shows: only while a partner has the delivery (ASSIGNED: partner → store →
 * customer, PICKED_UP: partner → customer), never once it is over or before anyone accepted it.
 */
export function liveEtaMinutes(tracking: TrackingView | undefined): number | null {
  if (!tracking?.partner || (tracking.status !== 'ASSIGNED' && tracking.status !== 'PICKED_UP')) return null
  const minutes = tracking.etaMinutes
  return minutes !== null && Number.isFinite(minutes) ? Math.max(0, minutes) : null
}

/** "a minute" / "7 min": the value after "Arriving in about". */
export function etaValueText(minutes: number): string {
  return minutes <= 1 ? 'a minute' : `${minutes} min`
}
