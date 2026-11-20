/**
 * Building blocks of the colourful icon set: every icon is a list of shapes on a 24 × 24 grid, drawn back to front.
 * Icons are data rather than JSX so the same art can be rendered by React and as an HTML string (map markers).
 */
export interface Shape {
  d: string
  /** Fill colour; omit for a stroke-only shape. `currentColor` follows the surrounding text. */
  fill?: string
  /** Stroke colour (round caps and joins). */
  stroke?: string
  /** Stroke width in grid units. */
  width?: number
  opacity?: number
  transform?: string
  /** `stroke-dasharray`. */
  dash?: string
}

export type IconArt = readonly Shape[]

/** Tailwind palette values, so icons sit naturally next to the app's colours. */
export const C = {
  white: '#ffffff',
  slate100: '#f1f5f9',
  slate200: '#e2e8f0',
  slate300: '#cbd5e1',
  slate400: '#94a3b8',
  slate500: '#64748b',
  slate600: '#475569',
  slate700: '#334155',
  slate800: '#1e293b',
  red400: '#f87171',
  red500: '#ef4444',
  red600: '#dc2626',
  red700: '#b91c1c',
  orange200: '#fed7aa',
  orange300: '#fdba74',
  orange400: '#fb923c',
  orange500: '#f97316',
  orange600: '#ea580c',
  amber100: '#fef3c7',
  amber200: '#fde68a',
  amber300: '#fcd34d',
  amber400: '#fbbf24',
  amber500: '#f59e0b',
  amber600: '#d97706',
  amber700: '#b45309',
  amber800: '#92400e',
  amber900: '#78350f',
  yellow300: '#fde047',
  yellow400: '#facc15',
  lime500: '#84cc16',
  green500: '#22c55e',
  green600: '#16a34a',
  emerald100: '#d1fae5',
  emerald200: '#a7f3d0',
  emerald400: '#34d399',
  emerald500: '#10b981',
  emerald600: '#059669',
  emerald700: '#047857',
  teal400: '#2dd4bf',
  teal500: '#14b8a6',
  teal600: '#0d9488',
  sky100: '#e0f2fe',
  sky200: '#bae6fd',
  sky300: '#7dd3fc',
  sky400: '#38bdf8',
  sky500: '#0ea5e9',
  sky600: '#0284c7',
  sky700: '#0369a1',
  blue200: '#bfdbfe',
  blue400: '#60a5fa',
  blue500: '#3b82f6',
  blue600: '#2563eb',
  indigo300: '#a5b4fc',
  indigo500: '#6366f1',
  indigo900: '#312e81',
  violet300: '#c4b5fd',
  violet500: '#8b5cf6',
  violet600: '#7c3aed',
  pink200: '#fbcfe8',
  pink300: '#f9a8d4',
  pink400: '#f472b6',
  pink500: '#ec4899',
  rose500: '#f43f5e',
} as const

/** Keeps generated path data short. */
const n = (value: number): number => Math.round(value * 100) / 100

export function circle(cx: number, cy: number, r: number): string {
  return `M${n(cx - r)} ${n(cy)}a${n(r)} ${n(r)} 0 1 0 ${n(r * 2)} 0a${n(r)} ${n(r)} 0 1 0 ${n(-r * 2)} 0Z`
}

export function ellipse(cx: number, cy: number, rx: number, ry: number): string {
  return `M${n(cx - rx)} ${n(cy)}a${n(rx)} ${n(ry)} 0 1 0 ${n(rx * 2)} 0a${n(rx)} ${n(ry)} 0 1 0 ${n(-rx * 2)} 0Z`
}

export function rect(x: number, y: number, w: number, h: number, r = 0): string {
  if (r <= 0) return `M${n(x)} ${n(y)}h${n(w)}v${n(h)}h${n(-w)}Z`
  const k = Math.min(r, w / 2, h / 2)
  return (
    `M${n(x + k)} ${n(y)}h${n(w - 2 * k)}a${n(k)} ${n(k)} 0 0 1 ${n(k)} ${n(k)}v${n(h - 2 * k)}` +
    `a${n(k)} ${n(k)} 0 0 1 ${n(-k)} ${n(k)}h${n(-(w - 2 * k))}a${n(k)} ${n(k)} 0 0 1 ${n(-k)} ${n(-k)}` +
    `v${n(-(h - 2 * k))}a${n(k)} ${n(k)} 0 0 1 ${n(k)} ${n(-k)}Z`
  )
}

/** A filled shape. */
export function fill(color: string, d: string, opacity?: number): Shape {
  return opacity === undefined ? { d, fill: color } : { d, fill: color, opacity }
}

/** A stroked line (round ends), for handles, steam, ticks and other thin details. */
export function line(color: string, d: string, width = 1.6, opacity?: number): Shape {
  return opacity === undefined ? { d, stroke: color, width } : { d, stroke: color, width, opacity }
}
