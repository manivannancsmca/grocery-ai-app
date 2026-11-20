/** Axis maths for the charts: clean tick steps and compact Indian-style tick labels. */

/** Ticks from 0 to a clean maximum at or above `max` (about `count` steps of 1, 2, 2.5 or 5 × 10ⁿ). */
export function niceTicks(max: number, count = 4): number[] {
  if (!Number.isFinite(max) || max <= 0) return [0, 1]
  const rough = max / count
  const magnitude = 10 ** Math.floor(Math.log10(rough))
  const step = ([1, 2, 2.5, 5, 10].find((factor) => factor * magnitude >= rough) ?? 10) * magnitude
  const top = Math.ceil(max / step) * step
  const ticks: number[] = []
  for (let value = 0; value <= top + step / 2; value += step) ticks.push(Math.round(value * 1e6) / 1e6)
  return ticks
}

const compact = new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 })

/** `950`, `1.2K`, `3.4L`, `1.1Cr` (Indian compact notation). */
export function compactNumber(value: number): string {
  return compact.format(value)
}

/** Which category labels fit along an axis `width` px wide: every `step`-th one. */
export function labelStep(count: number, width: number, labelWidth = 56): number {
  const fits = Math.max(1, Math.floor(width / labelWidth))
  return Math.max(1, Math.ceil(count / fits))
}
