import { formatLongDay, indiaDay, shiftDay } from '../../../shared/utils/indiaDate'
import { deliveryStatusLabel } from '../labels'

/** @typedef {import('../../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../../../shared/icons').IconName} IconName */
/** @typedef {import('../api').DeliverySummary} DeliverySummary */
/** @typedef {import('../api').StatsRange} StatsRange */

/**
 * The trips of one day in the history list (India time), in the order the server sent them (newest first).
 * @typedef {object} TripDay
 * @property {string} day `YYYY-MM-DD`, or `''` when the trip has no time at all.
 * @property {string} title "Today", "Yesterday" or the date ("Mon, 16 Nov 2026").
 * @property {string | null} subtitle The date under "Today" / "Yesterday"; null otherwise.
 * @property {DeliverySummary[]} trips
 * @property {number} km Store → customer distance of the listed trips.
 */

/**
 * When the trip ended, or when it was picked up if the end is missing.
 * @param {Pick<DeliverySummary, 'finishedAt' | 'pickedUpAt'>} trip
 * @returns {string | null}
 */
export function tripTime(trip) {
  return trip.finishedAt ?? trip.pickedUpAt
}

/**
 * The India-time day of an ISO instant, or `''` for a missing / broken value.
 * @param {string | null} at
 * @returns {string}
 */
function dayOf(at) {
  if (!at || Number.isNaN(new Date(at).getTime())) return ''
  return indiaDay(at)
}

/**
 * "Today" / "Yesterday" with the date underneath, otherwise the date itself.
 * @param {string} day
 * @param {string} today
 * @returns {{ title: string, subtitle: string | null }}
 */
export function dayTitle(day, today) {
  if (day === '') return { title: 'Date unknown', subtitle: null }
  if (day === today) return { title: 'Today', subtitle: formatLongDay(day) }
  if (day === shiftDay(today, -1)) return { title: 'Yesterday', subtitle: formatLongDay(day) }
  return { title: formatLongDay(day), subtitle: null }
}

/**
 * One page of history grouped by day. A day keeps the place of its first trip, so newest first stays newest first.
 * @param {readonly DeliverySummary[]} trips
 * @param {string} [today=indiaDay()]
 * @returns {TripDay[]}
 */
export function groupTripsByDay(trips, today = indiaDay()) {
  const days = new Map()
  for (const trip of trips) {
    const day = dayOf(tripTime(trip))
    let group = days.get(day)
    if (!group) {
      group = { day, ...dayTitle(day, today), trips: [], km: 0 }
      days.set(day, group)
    }
    group.trips.push(trip)
    group.km += trip.tripKm ?? 0
  }
  return [...days.values()]
}

/**
 * Minutes from pickup to the end of the trip, or null when a time is missing (or the times are the wrong way round).
 * @param {Pick<DeliverySummary, 'pickedUpAt' | 'finishedAt'>} trip
 * @returns {number | null}
 */
export function rideMinutes(trip) {
  if (!trip.pickedUpAt || !trip.finishedAt) return null
  const minutes = (new Date(trip.finishedAt).getTime() - new Date(trip.pickedUpAt).getTime()) / 60_000
  return Number.isFinite(minutes) && minutes >= 0 ? minutes : null
}

/**
 * How a finished trip ended, in the partner's words, with its badge colour and drop-off picture.
 * @typedef {object} TripOutcome
 * @property {string} label
 * @property {BadgeTone} tone
 * @property {IconName} icon
 * @property {boolean} delivered
 */

/**
 * @param {string} status
 * @returns {TripOutcome}
 */
export function tripOutcome(status) {
  if (status === 'DELIVERED') return { label: 'Delivered', tone: 'green', icon: 'homeCheck', delivered: true }
  if (status === 'FAILED') return { label: 'Not delivered', tone: 'red', icon: 'homeCross', delivered: false }
  return { label: deliveryStatusLabel(status), tone: 'gray', icon: 'home', delivered: false }
}

/**
 * `1 trip` / `3 trips`.
 * @param {number} count
 * @returns {string}
 */
export function tripsText(count) {
  return `${count} trip${count === 1 ? '' : 's'}`
}

/**
 * The same 7 India-time days as the summary tiles ("the week is the last 7 days", today included).
 * @param {string} [today=indiaDay()]
 * @returns {StatsRange}
 */
export function lastSevenDays(today = indiaDay()) {
  return { from: shiftDay(today, -6), to: today }
}

/**
 * Colour of the success ring: green from 90 %, amber from 75 %, red below.
 * @param {number | null} ratio
 * @returns {'good' | 'warning' | 'critical' | 'none'}
 */
export function successTone(ratio) {
  if (ratio === null) return 'none'
  if (ratio >= 0.9) return 'good'
  return ratio >= 0.75 ? 'warning' : 'critical'
}
