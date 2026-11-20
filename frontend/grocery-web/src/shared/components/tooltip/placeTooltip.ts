/** Where a tooltip goes: fixed-position coordinates, the side of the target it is on, and its arrow's x offset. */
export interface TooltipPlacement {
  top: number
  left: number
  side: 'top' | 'bottom'
  /** Distance of the arrow's centre from the tooltip's left edge. */
  arrowLeft: number
}

interface Box {
  top: number
  bottom: number
  left: number
  width: number
}

interface Size {
  width: number
  height: number
}

const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), Math.max(min, max))

/**
 * Centres the tooltip above the target (below it when there is no room above), kept `margin` px inside the viewport
 * horizontally; the arrow keeps pointing at the target's centre.
 */
export function placeTooltip(target: Box, tooltip: Size, viewport: Size, gap = 8, margin = 8): TooltipPlacement {
  const side = target.top - gap - tooltip.height >= margin ? 'top' : 'bottom'
  const top = side === 'top' ? target.top - gap - tooltip.height : target.bottom + gap
  const centre = target.left + target.width / 2
  const left = clamp(centre - tooltip.width / 2, margin, viewport.width - tooltip.width - margin)
  const arrowLeft = clamp(centre - left, 10, tooltip.width - 10)
  return { top, left, side, arrowLeft }
}
