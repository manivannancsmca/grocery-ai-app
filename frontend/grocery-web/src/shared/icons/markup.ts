import { ICON_ART, type IconName } from './registry'
import type { Shape } from './shapes'

function shapeMarkup(shape: Shape): string {
  const attributes = [`d="${shape.d}"`, `fill="${shape.fill ?? 'none'}"`]
  if (shape.stroke !== undefined) {
    attributes.push(`stroke="${shape.stroke}"`, `stroke-width="${shape.width ?? 1}"`, 'stroke-linecap="round"', 'stroke-linejoin="round"')
  }
  if (shape.dash !== undefined) attributes.push(`stroke-dasharray="${shape.dash}"`)
  if (shape.opacity !== undefined) attributes.push(`opacity="${shape.opacity}"`)
  if (shape.transform !== undefined) attributes.push(`transform="${shape.transform}"`)
  return `<path ${attributes.join(' ')}/>`
}

/** An icon as an SVG string, for HTML that React does not render (Leaflet map markers). */
export function iconMarkup(name: IconName, className: string): string {
  return `<svg class="${className}" viewBox="0 0 24 24" aria-hidden="true" focusable="false">${ICON_ART[name].map(shapeMarkup).join('')}</svg>`
}
