import { DAYS_OF_WEEK, type DayOfWeek, type OpeningHours } from '../restaurant/api'

const weekdayInIndia = new Intl.DateTimeFormat('en-US', { weekday: 'long', timeZone: 'Asia/Kolkata' })

/** Today's weekday in India time — opening hours (and `openNow`) are evaluated in Asia/Kolkata. */
export function todayInIndia(now: Date = new Date()): DayOfWeek {
  const name = weekdayInIndia.format(now).toUpperCase()
  return (DAYS_OF_WEEK as readonly string[]).includes(name) ? (name as DayOfWeek) : 'MONDAY'
}

/** `09:00:00` → `09:00` */
function hhmm(value: string): string {
  return value.slice(0, 5)
}

/** Slots of one day as text, e.g. `11:00–15:00, 19:00–23:30`; null when closed that day. */
export function hoursForDay(hours: readonly OpeningHours[] | null | undefined, day: DayOfWeek): string | null {
  const slots = (hours ?? []).filter((slot) => slot.dayOfWeek === day)
  if (slots.length === 0) return null
  return slots
    .slice()
    .sort((a, b) => a.opensAt.localeCompare(b.opensAt))
    .map((slot) => `${hhmm(slot.opensAt)}–${hhmm(slot.closesAt)}`)
    .join(', ')
}
