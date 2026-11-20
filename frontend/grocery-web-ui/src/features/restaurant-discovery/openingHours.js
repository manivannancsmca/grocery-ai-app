import { DAYS_OF_WEEK } from '../restaurant/api'

/** @typedef {import('../restaurant/api').DayOfWeek} DayOfWeek */
/** @typedef {import('../restaurant/api').OpeningHours} OpeningHours */

const weekdayInIndia = new Intl.DateTimeFormat('en-US', { weekday: 'long', timeZone: 'Asia/Kolkata' })

/**
 * Today's weekday in India time — opening hours (and `openNow`) are evaluated in Asia/Kolkata.
 * @param {Date} [now=new Date()]
 * @returns {DayOfWeek}
 */
export function todayInIndia(now = new Date()) {
  const name = weekdayInIndia.format(now).toUpperCase()
  return DAYS_OF_WEEK.includes(name) ? name : 'MONDAY'
}

/**
 * `09:00:00` → `09:00`
 * @param {string} value
 * @returns {string}
 */
function hhmm(value) {
  return value.slice(0, 5)
}

/**
 * Slots of one day as text, e.g. `11:00–15:00, 19:00–23:30`; null when closed that day.
 * @param {readonly OpeningHours[] | null | undefined} hours
 * @param {DayOfWeek} day
 * @returns {string | null}
 */
export function hoursForDay(hours, day) {
  const slots = (hours ?? []).filter((slot) => slot.dayOfWeek === day)
  if (slots.length === 0) return null
  return slots
    .slice()
    .sort((a, b) => a.opensAt.localeCompare(b.opensAt))
    .map((slot) => `${hhmm(slot.opensAt)}–${hhmm(slot.closesAt)}`)
    .join(', ')
}
