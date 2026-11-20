import type { BadgeTone } from '../../../shared/components/ui'
import type { IconName } from '../../../shared/icons'
import { formatLongDay, indiaDay, shiftDay } from '../../../shared/utils/indiaDate'
import type { DeliverySummary, StatsRange } from '../api'
import { deliveryStatusLabel } from '../labels'

/** The trips of one day in the history list (India time), in the order the server sent them (newest first). */
export interface TripDay {
  /** `YYYY-MM-DD`, or `''` when the trip has no time at all. */
  day: string
  /** "Today", "Yesterday" or the date ("Mon, 16 Nov 2026"). */
  title: string
  /** The date under "Today" / "Yesterday"; null otherwise. */
  subtitle: string | null
  trips: DeliverySummary[]
  /** Store → customer distance of the listed trips. */
  km: number
}

/** When the trip ended, or when it was picked up if the end is missing. */
export function tripTime(trip: Pick<DeliverySummary, 'finishedAt' | 'pickedUpAt'>): string | null {
  return trip.finishedAt ?? trip.pickedUpAt
}

/** The India-time day of an ISO instant, or `''` for a missing / broken value. */
function dayOf(at: string | null): string {
  if (!at || Number.isNaN(new Date(at).getTime())) return ''
  return indiaDay(at)
}

/** "Today" / "Yesterday" with the date underneath, otherwise the date itself. */
export function dayTitle(day: string, today: string): { title: string; subtitle: string | null } {
  if (day === '') return { title: 'Date unknown', subtitle: null }
  if (day === today) return { title: 'Today', subtitle: formatLongDay(day) }
  if (day === shiftDay(today, -1)) return { title: 'Yesterday', subtitle: formatLongDay(day) }
  return { title: formatLongDay(day), subtitle: null }
}

/** One page of history grouped by day. A day keeps the place of its first trip, so newest first stays newest first. */
export function groupTripsByDay(trips: readonly DeliverySummary[], today: string = indiaDay()): TripDay[] {
  const days = new Map<string, TripDay>()
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

/** Minutes from pickup to the end of the trip, or null when a time is missing (or the times are the wrong way round). */
export function rideMinutes(trip: Pick<DeliverySummary, 'pickedUpAt' | 'finishedAt'>): number | null {
  if (!trip.pickedUpAt || !trip.finishedAt) return null
  const minutes = (new Date(trip.finishedAt).getTime() - new Date(trip.pickedUpAt).getTime()) / 60_000
  return Number.isFinite(minutes) && minutes >= 0 ? minutes : null
}

/** How a finished trip ended, in the partner's words, with its badge colour and drop-off picture. */
export interface TripOutcome {
  label: string
  tone: BadgeTone
  icon: IconName
  delivered: boolean
}

export function tripOutcome(status: string): TripOutcome {
  if (status === 'DELIVERED') return { label: 'Delivered', tone: 'green', icon: 'homeCheck', delivered: true }
  if (status === 'FAILED') return { label: 'Not delivered', tone: 'red', icon: 'homeCross', delivered: false }
  return { label: deliveryStatusLabel(status), tone: 'gray', icon: 'home', delivered: false }
}

/** `1 trip` / `3 trips`. */
export function tripsText(count: number): string {
  return `${count} trip${count === 1 ? '' : 's'}`
}

/** The same 7 India-time days as the summary tiles ("the week is the last 7 days", today included). */
export function lastSevenDays(today: string = indiaDay()): StatsRange {
  return { from: shiftDay(today, -6), to: today }
}

/** Colour of the success ring: green from 90 %, amber from 75 %, red below. */
export function successTone(ratio: number | null): 'good' | 'warning' | 'critical' | 'none' {
  if (ratio === null) return 'none'
  if (ratio >= 0.9) return 'good'
  return ratio >= 0.75 ? 'warning' : 'critical'
}
