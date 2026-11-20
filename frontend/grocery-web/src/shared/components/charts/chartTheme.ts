/**
 * Chart colours and ink. The categorical slots are a colour-blind-checked palette (validated on the white card
 * surface: lightness band, chroma, adjacent CVD and normal-vision separation all pass). Assign them in this order and
 * never cycle: a series keeps its slot whatever else is shown. Slots 3–5 are under 3:1 on white, so every chart
 * that uses them also prints its values (legend values, table or labels).
 */
export const SERIES_COLORS = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300'] as const

/** One series on its own: the brand emerald (3.8:1 on white). */
export const BRAND_COLOR = '#059669'

export const CHART_INK = {
  /** Values in tooltips. */
  primary: '#0f172a',
  /** Axis tick labels and category names. */
  muted: '#64748b',
  /** Hairline gridlines. */
  grid: '#e2e8f0',
  /** The zero baseline. */
  axis: '#cbd5e1',
  /** The surface the marks sit on (gaps and marker rings). */
  surface: '#ffffff',
} as const

export function seriesColor(index: number): string {
  return SERIES_COLORS[Math.min(index, SERIES_COLORS.length - 1)] ?? BRAND_COLOR
}
