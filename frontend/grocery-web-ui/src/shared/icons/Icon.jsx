import { cn } from '../utils/cn'
import { ICON_ART } from './registry'

/** @typedef {import('./registry').IconName} IconName */
/** @typedef {import('./shapes').Shape} Shape */

/** A size the caller set (`size-*`, `w-*` or `h-*`); without one the icon is `size-5`. */
const HAS_SIZE = /(^|\s)(size|w|h)-/

/** @param {{ shape: Shape }} props */
function ShapePath({ shape }) {
  const stroked = shape.stroke !== undefined
  return (
    <path
      d={shape.d}
      fill={shape.fill ?? 'none'}
      stroke={shape.stroke}
      strokeWidth={shape.width}
      strokeLinecap={stroked ? 'round' : undefined}
      strokeLinejoin={stroked ? 'round' : undefined}
      strokeDasharray={shape.dash}
      opacity={shape.opacity}
      transform={shape.transform}
    />
  )
}

/**
 * @typedef {object} IconProps
 * @property {IconName} name
 * @property {string} [className] Size and placement classes. Pictures keep their own colours; control glyphs follow the text colour.
 */

/**
 * One of the app's colourful filled icons (decorative: the text next to it always says the same).
 * @param {IconProps} props
 */
export function Icon({ name, className }) {
  return (
    <svg
      className={cn('shrink-0', !HAS_SIZE.test(className ?? '') && 'size-5', className)}
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
    >
      {ICON_ART[name].map((shape, index) => (
        <ShapePath key={index} shape={shape} />
      ))}
    </svg>
  )
}
