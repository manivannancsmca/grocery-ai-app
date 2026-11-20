import { cn } from '../utils/cn'
import { ICON_ART, type IconName } from './registry'
import type { Shape } from './shapes'

/** A size the caller set (`size-*`, `w-*` or `h-*`); without one the icon is `size-5`. */
const HAS_SIZE = /(^|\s)(size|w|h)-/

function ShapePath({ shape }: { shape: Shape }) {
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

export interface IconProps {
  name: IconName
  /** Size and placement classes. Pictures keep their own colours; control glyphs follow the text colour. */
  className?: string
}

/** One of the app's colourful filled icons (decorative: the text next to it always says the same). */
export function Icon({ name, className }: IconProps) {
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
