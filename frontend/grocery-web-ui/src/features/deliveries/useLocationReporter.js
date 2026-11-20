import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
import { errorMessage } from '../../shared/api/problemDetail'
import { roundCoordinate } from '../../shared/components/map/types'
import { deliveryKeys, reportLocation } from './api'
import { createLocationReporter } from './locationReporter'

/** @typedef {import('../../shared/components/map/types').GeoPoint} GeoPoint */
/** @typedef {import('./api').LocationReport} LocationReport */
/** @typedef {import('./locationReporter').ReporterStopReason} ReporterStopReason */

/**
 * Where positions come from: the device's GPS, or the location simulator (development / testing).
 * @typedef {'device' | 'simulator'} LocationSource
 */

/** @typedef {'denied' | 'unavailable' | 'unsupported'} GeolocationProblem */

/**
 * @typedef {object} LocationSharing
 * @property {GeoPoint | null} position The latest known position (device fix or simulator), shown as "You" on the maps.
 * @property {number | null} lastSentAt When the last report reached delivery-service (ms).
 * @property {GeolocationProblem | null} problem The device could not give a position (device source only).
 * @property {ReporterStopReason | null} stopped The server told us to stop (409 PARTNER_OFFLINE / 403 PARTNER_NOT_ACTIVE) until the partner goes online again.
 * @property {string | null} sendError The last report failed for another reason (it is retried).
 * @property {(report: LocationReport) => void} report A simulated position: goes through the same throttled reporter as the device's.
 * @property {() => void} resume The partner switched online again: clears a server stop and old errors.
 */

/** `GeolocationPositionError.PERMISSION_DENIED`. */
const PERMISSION_DENIED = 1

/** @returns {boolean} */
function geolocationSupported() {
  return typeof navigator !== 'undefined' && 'geolocation' in navigator && Boolean(navigator.geolocation)
}

/**
 * @param {GeolocationCoordinates} coords
 * @returns {LocationReport}
 */
function toReport(coords) {
  const finite = (value) => value !== null && Number.isFinite(value)
  return {
    latitude: roundCoordinate(coords.latitude),
    longitude: roundCoordinate(coords.longitude),
    ...(finite(coords.accuracy) ? { accuracyMeters: Math.round(coords.accuracy) } : {}),
    ...(finite(coords.heading) ? { heading: Math.round(coords.heading) } : {}),
    // m/s → km/h, one decimal.
    ...(finite(coords.speed) ? { speedKmh: Math.round(coords.speed * 36) / 10 } : {}),
  }
}

/**
 * Shares the partner's live location while they are online (Phase 9 contract §3): `watchPosition` on the
 * device (or the simulator's positions) → `PUT /api/delivery/partners/me/location`, at most every 5 s.
 * Stops when the partner goes offline or leaves the page, and when the server answers 409 PARTNER_OFFLINE.
 * @param {{ online: boolean, source: LocationSource }} options
 * @returns {LocationSharing}
 */
export function useLocationReporter({ online, source }) {
  const queryClient = useQueryClient()
  const [position, setPosition] = useState(null)
  const [lastSentAt, setLastSentAt] = useState(null)
  const [problem, setProblem] = useState(null)
  const [stopped, setStopped] = useState(null)
  const [sendError, setSendError] = useState(null)
  const [reporter] = useState(() =>
    createLocationReporter({
      send: reportLocation,
      onSent: (_report, at) => {
        setLastSentAt(at)
        setSendError(null)
      },
      onError: (error) => setSendError(errorMessage(error)),
      onStopped: (reason) => {
        setStopped(reason)
        // The server thinks the partner is offline (or not approved): show what it says.
        void queryClient.invalidateQueries({ queryKey: deliveryKeys.status() })
      },
    }),
  )

  const sharing = online && stopped === null
  const supported = geolocationSupported()
  const watching = sharing && source === 'device' && supported

  useEffect(() => {
    if (!sharing) return
    reporter.start()
    return () => reporter.stop()
  }, [reporter, sharing])

  useEffect(() => {
    if (!watching) return
    const geolocation = navigator.geolocation
    const watchId = geolocation.watchPosition(
      (fix) => {
        setProblem(null)
        const next = toReport(fix.coords)
        setPosition({ latitude: next.latitude, longitude: next.longitude })
        reporter.push(next)
      },
      (error) => setProblem(error.code === PERMISSION_DENIED ? 'denied' : 'unavailable'),
      { enableHighAccuracy: true, maximumAge: 5_000, timeout: 30_000 },
    )
    return () => geolocation.clearWatch(watchId)
  }, [reporter, watching])

  const report = useCallback(
    (next) => {
      setPosition({ latitude: next.latitude, longitude: next.longitude })
      reporter.push(next)
    },
    [reporter],
  )

  const resume = useCallback(() => {
    setStopped(null)
    setProblem(null)
    setSendError(null)
  }, [])

  return {
    position,
    lastSentAt,
    problem: source !== 'device' ? null : supported ? problem : 'unsupported',
    stopped,
    sendError,
    report,
    resume,
  }
}
