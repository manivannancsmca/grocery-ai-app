/** Axis maths for the charts: clean tick steps and compact Indian-style tick labels. */

/**
 * Ticks from 0 to a clean maximum at or above `max` (about `count` steps of 1, 2, 2.5 or 5 × 10ⁿ).
 * @param {number} max
 * @param [count=4]
 * @returns {number[]}
 */
export function niceTicks(max, count = 4) {
  if (!Number.isFinite(max) || max <= 0) return [0, 1]
  const rough = max / count
  const magnitude = 10 ** Math.floor(Math.log10(rough))
  const step = ([1, 2, 2.5, 5, 10].find((factor) => factor * magnitude >= rough) ?? 10) * magnitude
  const top = Math.ceil(max / step) * step
  const ticks = []
  for (let value = 0; value <= top + step / 2; value += step) ticks.push(Math.round(value * 1e6) / 1e6)
  return ticks
}

const compact = new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 })

/**
 * `950`, `1.2K`, `3.4L`, `1.1Cr` (Indian compact notation).
 * @param {number} value
 * @returns {string}
 */
export function compactNumber(value) {
  return compact.format(value)
}

/**
 * Which category labels fit along an axis `width` px wide: every `step`-th one.
 * @param {number} count
 * @param {number} width
 * @param [labelWidth=56]
 * @returns {number}
 */
export function labelStep(count, width, labelWidth = 56) {
  const fits = Math.max(1, Math.floor(width / labelWidth))
  return Math.max(1, Math.ceil(count / fits))
}
