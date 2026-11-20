import { createContext, useContext } from 'react'

/** @typedef {import('../../icons').IconName} IconName */

/**
 * How page headers look in an area of the app: `soft` (light banner, the default) or `hero` (a colourful gradient
 * banner with white text, used by the admin area). An area sets it once around its routes, so its pages need no change.
 * @typedef {'soft' | 'hero'} PageHeaderStyle
 */

export const PageHeaderStyleContext = createContext('soft')

/** @returns {PageHeaderStyle} */
export function usePageHeaderStyle() {
  return useContext(PageHeaderStyleContext)
}

/** @typedef {'emerald' | 'sky' | 'violet' | 'amber' | 'rose' | 'teal' | 'indigo'} HeroAccent */

/**
 * The hero's colours. Every gradient stays in the 700–800 shades (white text at 5:1 or more); the lighter accents only
 * appear in the decorative lights.
 * @type {Record<HeroAccent, { gradient: string, light: string, shadow: string }>}
 */
export const HERO_ACCENTS = {
  emerald: {
    gradient: 'from-emerald-700 via-teal-700 to-cyan-800',
    light: 'bg-emerald-300/30',
    shadow: 'shadow-emerald-900/25',
  },
  sky: { gradient: 'from-sky-700 via-blue-700 to-indigo-800', light: 'bg-sky-300/30', shadow: 'shadow-blue-900/25' },
  violet: {
    gradient: 'from-violet-700 via-purple-700 to-fuchsia-800',
    light: 'bg-fuchsia-300/30',
    shadow: 'shadow-purple-900/25',
  },
  amber: {
    gradient: 'from-amber-700 via-orange-700 to-rose-700',
    light: 'bg-amber-300/35',
    shadow: 'shadow-orange-900/25',
  },
  rose: {
    gradient: 'from-rose-700 via-pink-700 to-fuchsia-800',
    light: 'bg-pink-300/30',
    shadow: 'shadow-rose-900/25',
  },
  teal: { gradient: 'from-teal-700 via-cyan-700 to-sky-800', light: 'bg-cyan-300/30', shadow: 'shadow-cyan-900/25' },
  indigo: {
    gradient: 'from-indigo-700 via-violet-700 to-purple-800',
    light: 'bg-indigo-300/30',
    shadow: 'shadow-indigo-900/25',
  },
}

/**
 * Each page's colour follows its picture (the same as its menu item), so a page keeps its colour everywhere. Chosen so
 * neighbours in the admin menu differ (Overview: emerald, violet, teal · Partners: rose, amber, violet, sky, teal,
 * indigo · Marketplace: amber, emerald, indigo, sky, violet, amber, rose, teal · AI: indigo, rose · Operations: teal).
 * @type {Partial<Record<IconName, HeroAccent>>}
 */
const ACCENT_BY_ICON = {
  grid: 'emerald',
  chart: 'violet',
  pulse: 'teal',
  shield: 'emerald',
  tag: 'rose',
  store: 'amber',
  cloche: 'violet',
  scooter: 'sky',
  users: 'teal',
  user: 'indigo',
  basket: 'amber',
  receipt: 'emerald',
  card: 'indigo',
  truck: 'sky',
  return: 'violet',
  star: 'amber',
  ticket: 'rose',
  clipboard: 'teal',
  mail: 'sky',
  sparkle: 'violet',
  sparkleCheck: 'rose',
  sparkleQuestion: 'indigo',
  settings: 'teal',
}

/** @type {readonly HeroAccent[]} */
const FALLBACK = ['emerald', 'sky', 'violet', 'amber', 'rose', 'teal', 'indigo']

/**
 * @param {IconName | undefined} icon
 * @param {string} title
 * @returns {HeroAccent}
 */
export function heroAccentFor(icon, title) {
  const byIcon = icon ? ACCENT_BY_ICON[icon] : undefined
  if (byIcon) return byIcon
  let hash = 0
  for (const char of title) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  return FALLBACK[hash % FALLBACK.length] ?? 'emerald'
}
