/**
 * The look of the onboarding approval queue: one colour family per stakeholder type, and how long a submission has
 * been waiting (fresh → emerald, a day or more → amber, three days or more → rose). Full class names, so Tailwind
 * sees them.
 * @typedef {'amber' | 'rose' | 'sky'} ApprovalAccent
 */

/**
 * @typedef {object} AccentClasses
 * @property {string} gradient Bars, buttons and the selected tile's underline.
 * @property {string} soft The selected tile's background.
 * @property {string} ring The selected tile's ring.
 * @property {string} text The pending count.
 * @property {string} avatar Initials avatar.
 * @property {string} shadow Coloured shadow under the avatar, the button and the selected tile.
 * @property {string} glow Soft light in a corner.
 * @property {string} focus
 */

/** @type {Record<ApprovalAccent, AccentClasses>} */
export const ACCENTS = {
  amber: {
    gradient: 'from-amber-400 via-orange-500 to-rose-500',
    soft: 'from-amber-50 via-orange-50 to-white',
    ring: 'ring-amber-300',
    text: 'text-orange-600',
    avatar: 'from-amber-400 to-orange-500',
    shadow: 'shadow-orange-500/25',
    glow: 'bg-amber-300/40',
    focus: 'focus-visible:outline-orange-500',
  },
  rose: {
    gradient: 'from-rose-400 via-pink-500 to-fuchsia-500',
    soft: 'from-rose-50 via-pink-50 to-white',
    ring: 'ring-rose-300',
    text: 'text-pink-600',
    avatar: 'from-rose-400 to-fuchsia-500',
    shadow: 'shadow-pink-500/25',
    glow: 'bg-pink-300/40',
    focus: 'focus-visible:outline-pink-500',
  },
  sky: {
    gradient: 'from-sky-400 via-blue-500 to-indigo-500',
    soft: 'from-sky-50 via-blue-50 to-white',
    ring: 'ring-sky-300',
    text: 'text-blue-600',
    avatar: 'from-sky-400 to-indigo-500',
    shadow: 'shadow-blue-500/25',
    glow: 'bg-sky-300/40',
    focus: 'focus-visible:outline-blue-500',
  },
}

/**
 * @typedef {object} Waiting
 * @property {string} label "New · 3 h", "Waiting 2 days".
 * @property {string} tone Chip colours.
 * @property {number | null} days Whole days waited (0 on the first day), null when not submitted.
 */

const FRESH = 'bg-emerald-50 text-emerald-700 ring-emerald-200'
const WAITING = 'bg-amber-50 text-amber-800 ring-amber-200'
const OVERDUE = 'bg-rose-50 text-rose-700 ring-rose-200'

/**
 * How long a submission has waited for a decision, for the chip on its card and the page's "oldest" figure.
 * @param {string | null | undefined} submittedAt
 * @param {number} now
 * @returns {Waiting}
 */
export function waitingSince(submittedAt, now) {
  const at = submittedAt ? Date.parse(submittedAt) : Number.NaN
  if (Number.isNaN(at)) return { label: 'Not submitted', tone: 'bg-slate-50 text-slate-600 ring-slate-200', days: null }
  const hours = Math.max(0, (now - at) / 3_600_000)
  if (hours < 1) return { label: 'New · just now', tone: FRESH, days: 0 }
  if (hours < 24) return { label: `New · ${Math.floor(hours)} h`, tone: FRESH, days: 0 }
  const days = Math.floor(hours / 24)
  return { label: `Waiting ${days} ${days === 1 ? 'day' : 'days'}`, tone: days >= 3 ? OVERDUE : WAITING, days }
}
