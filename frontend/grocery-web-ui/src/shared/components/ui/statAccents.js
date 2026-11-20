/** @typedef {'emerald' | 'sky' | 'violet' | 'amber' | 'rose' | 'teal'} StatAccent */

/**
 * Accents for a row of stat tiles, in order, so neighbours differ.
 * @type {readonly StatAccent[]}
 */
export const STAT_ACCENTS = ['emerald', 'sky', 'violet', 'amber', 'teal', 'rose']

/**
 * Full class names per accent (so Tailwind sees them).
 * @type {Record<StatAccent, { bar: string, glow: string, tile: string }>}
 */
export const ACCENT_CLASSES = {
  emerald: {
    bar: 'from-emerald-400 to-teal-500',
    glow: 'bg-emerald-200/50',
    tile: 'from-emerald-50 to-teal-50 ring-emerald-100',
  },
  sky: { bar: 'from-sky-400 to-blue-500', glow: 'bg-sky-200/50', tile: 'from-sky-50 to-blue-50 ring-sky-100' },
  violet: {
    bar: 'from-violet-400 to-fuchsia-500',
    glow: 'bg-violet-200/50',
    tile: 'from-violet-50 to-fuchsia-50 ring-violet-100',
  },
  amber: {
    bar: 'from-amber-400 to-orange-500',
    glow: 'bg-amber-200/60',
    tile: 'from-amber-50 to-orange-50 ring-amber-100',
  },
  rose: { bar: 'from-rose-400 to-red-500', glow: 'bg-rose-200/50', tile: 'from-rose-50 to-red-50 ring-rose-100' },
  teal: { bar: 'from-teal-400 to-cyan-500', glow: 'bg-teal-200/50', tile: 'from-teal-50 to-cyan-50 ring-teal-100' },
}
