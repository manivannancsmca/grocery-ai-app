/** Number formats and small helpers shared by the report sections. */

import { formatPrice } from '../../shared/utils/format'

export const TOP_LIMIT = 10

/** @param {number} value */
export const money = (value) => formatPrice(value)

export const compactMoney = new Intl.NumberFormat('en-IN', { notation: 'compact', maximumFractionDigits: 1 })

/** @param {number | null} value */
export const minutes = (value) =>
  value === null || value === undefined ? '—' : `${value.toLocaleString('en-IN', { maximumFractionDigits: 1 })} min`

/**
 * "Highest day …", the subtitle of a day-by-day chart.
 * @param {readonly number[]} values
 * @param {(value: number) => string} format
 * @returns {string}
 */
export function highest(values, format) {
  return `Highest day ${format(Math.max(0, ...values))}`
}

export const KPI_GRID = 'grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4'

/**
 * @template T
 * @param {T[]} rows
 * @returns {(T & { rank: number })[]}
 */
export function ranked(rows) {
  return rows.map((row, index) => ({ ...row, rank: index + 1 }))
}
