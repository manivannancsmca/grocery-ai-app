/**
 * Where a tooltip goes: fixed-position coordinates, the side of the target it is on, and its arrow's x offset.
 * @typedef {object} TooltipPlacement
 * @property {number} top
 * @property {number} left
 * @property {'top' | 'bottom'} side
 * @property {number} arrowLeft Distance of the arrow's centre from the tooltip's left edge.
 */

/**
 * @typedef {object} Box
 * @property {number} top
 * @property {number} bottom
 * @property {number} left
 * @property {number} width
 */

/**
 * @typedef {object} Size
 * @property {number} width
 * @property {number} height
 */

/**
 * @param {number} value
 * @param {number} min
 * @param {number} max
 */
const clamp = (value, min, max) => Math.min(Math.max(value, min), Math.max(min, max))

/**
 * Centres the tooltip above the target (below it when there is no room above), kept `margin` px inside the viewport
 * horizontally; the arrow keeps pointing at the target's centre.
 * @param {Box} target
 * @param {Size} tooltip
 * @param {Size} viewport
 * @param [gap=8]
 * @param [margin=8]
 * @returns {TooltipPlacement}
 */
export function placeTooltip(target, tooltip, viewport, gap = 8, margin = 8) {
  const side = target.top - gap - tooltip.height >= margin ? 'top' : 'bottom'
  const top = side === 'top' ? target.top - gap - tooltip.height : target.bottom + gap
  const centre = target.left + target.width / 2
  const left = clamp(centre - tooltip.width / 2, margin, viewport.width - tooltip.width - margin)
  const arrowLeft = clamp(centre - left, 10, tooltip.width - 10)
  return { top, left, side, arrowLeft }
}
