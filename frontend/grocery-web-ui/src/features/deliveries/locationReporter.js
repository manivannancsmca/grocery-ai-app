import { hasErrorCode } from '../../shared/api/problemDetail'
import { DELIVERY_ERRORS } from './api'

/** @typedef {import('./api').LocationReport} LocationReport */

/** At most one location report per this interval (the server stores at most one per 2 s anyway). */
export const LOCATION_REPORT_INTERVAL_MS = 5_000

/**
 * A partner standing still gets few or no new fixes from the browser, but delivery-service treats a location
 * older than 60 s (by default; a platform setting) as stale (no more offers). The last position is re-sent after
 * this much silence.
 */
export const LOCATION_KEEPALIVE_MS = 25_000

/**
 * Why the server told the reporter to stop.
 * @typedef {'offline' | 'not-active'} ReporterStopReason
 */

/**
 * @typedef {object} LocationReporterOptions
 * @property {(report: LocationReport) => Promise<void>} send
 * @property {(report: LocationReport, at: number) => void} [onSent] A report reached the server (`at` = when it was sent).
 * @property {(error: unknown) => void} [onError] A report failed for another reason; the reporter keeps trying.
 * @property {(reason: ReporterStopReason) => void} [onStopped] 409 PARTNER_OFFLINE or 403 PARTNER_NOT_ACTIVE: the reporter has stopped itself.
 * @property {number} [intervalMs]
 * @property {number} [keepAliveMs]
 * @property {() => number} [now]
 */

/**
 * @typedef {object} LocationReporter
 * @property {(report: LocationReport) => void} push A new position (device fix or simulator step). Sent now, or as soon as the interval allows.
 * @property {() => void} start Starts sending (the latest known position goes out at once).
 * @property {() => void} stop Stops sending and drops any scheduled send; the latest position is kept for the next start.
 * @property {boolean} running
 */

/**
 * Throttled location reporting for the partner app: the first position goes out at once, later ones at most
 * every `intervalMs` (the newest wins — a trailing send), and the last one is repeated after `keepAliveMs` of
 * silence. A 409 PARTNER_OFFLINE (or 403 PARTNER_NOT_ACTIVE) stops it until `start()` is called again.
 * @param {LocationReporterOptions} options
 * @returns {LocationReporter}
 */
export function createLocationReporter(options) {
  const intervalMs = options.intervalMs ?? LOCATION_REPORT_INTERVAL_MS
  const keepAliveMs = options.keepAliveMs ?? LOCATION_KEEPALIVE_MS
  const now = options.now ?? Date.now

  let running = false
  let latest = null
  /** A position arrived that has not been sent yet. */
  let dirty = false
  let inFlight = false
  let lastSentAt = Number.NEGATIVE_INFINITY
  let timer = null
  /** Bumped by stop(): answers of requests sent before it are ignored. */
  let generation = 0

  function clearTimer() {
    if (timer !== null) clearTimeout(timer)
    timer = null
  }

  function schedule(delayMs) {
    clearTimer()
    timer = setTimeout(() => {
      timer = null
      flush(true)
    }, delayMs)
  }

  function stop() {
    running = false
    generation++
    inFlight = false
    clearTimer()
  }

  /** Sends the latest position if the interval allows, otherwise schedules it. `force` = keep-alive / retry. */
  function flush(force = false) {
    if (!running || latest === null || inFlight) return
    if (!dirty && !force) return
    const elapsed = now() - lastSentAt
    if (elapsed < intervalMs) {
      schedule(intervalMs - elapsed)
      return
    }
    clearTimer()
    const report = latest
    const sentAt = now()
    const round = generation
    dirty = false
    inFlight = true
    lastSentAt = sentAt
    let failed = false
    options
      .send(report)
      .then(
        () => {
          if (round === generation) options.onSent?.(report, sentAt)
        },
        (error) => {
          if (round !== generation) return
          if (hasErrorCode(error, DELIVERY_ERRORS.partnerOffline, DELIVERY_ERRORS.partnerNotActive)) {
            stop()
            options.onStopped?.(hasErrorCode(error, DELIVERY_ERRORS.partnerOffline) ? 'offline' : 'not-active')
            return
          }
          failed = true
          options.onError?.(error)
        },
      )
      .finally(() => {
        if (round !== generation) return
        inFlight = false
        if (dirty) flush()
        else schedule(failed ? intervalMs : keepAliveMs)
      })
  }

  return {
    push(report) {
      latest = report
      dirty = true
      flush()
    },
    start() {
      if (running) return
      running = true
      // Whatever we know goes out now: the server needs a fresh position to offer deliveries.
      if (latest !== null) dirty = true
      flush()
    },
    stop,
    get running() {
      return running
    },
  }
}
