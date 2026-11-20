import { cn } from '../utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */

/**
 * @typedef {object} IllustrationProps
 * @property {string} [className]
 * @property {string} [title] Accessible description. Without it the illustration is decorative and hidden from assistive technology.
 */

/** @typedef {IllustrationProps & { viewBox: string, children: ReactNode }} SvgProps */

/**
 * The shared frame of every illustration: scales to its container's width and keeps its aspect ratio.
 * A width class from the caller (`w-24`, `w-40 sm:w-48`) sets a fixed size instead. The full-width default is the
 * `width` attribute, not a `w-full` class, because any CSS class beats an SVG presentation attribute, while two
 * width classes conflict (`cn` only joins them) and `w-full` comes later in Tailwind's stylesheet.
 * @param {SvgProps} props
 */
export function IllustrationSvg({ viewBox, className, title, children }) {
  return (
    <svg
      viewBox={viewBox}
      xmlns="http://www.w3.org/2000/svg"
      width="100%"
      className={cn('h-auto', className)}
      role={title ? 'img' : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
      focusable="false"
    >
      {children}
    </svg>
  )
}

/**
 * A soft round backdrop behind a scene.
 * @param {{ cx: number, cy: number, r: number, tone?: 'emerald' | 'amber' | 'sky' | 'rose' }} props
 */
export function Backdrop({ cx, cy, r, tone = 'emerald' }) {
  const fill = { emerald: '#d1fae5', amber: '#fef3c7', sky: '#e0f2fe', rose: '#ffe4e6' }[tone]
  return <circle cx={cx} cy={cy} r={r} fill={fill} />
}

/**
 * The faint shadow an object casts on the ground.
 * @param {{ cx: number, cy: number, rx: number, ry?: number }} props
 */
export function GroundShadow({ cx, cy, rx, ry = 6 }) {
  return <ellipse cx={cx} cy={cy} rx={rx} ry={ry} fill="#0f172a" opacity="0.08" />
}

/**
 * A four-pointed sparkle.
 * @param {{ x: number, y: number, size?: number, fill?: string }} props
 */
export function Sparkle({ x, y, size = 8, fill = '#fbbf24' }) {
  const s = size
  return (
    <path
      d={`M${x} ${y - s} Q${x + s * 0.18} ${y - s * 0.18} ${x + s} ${y} Q${x + s * 0.18} ${y + s * 0.18} ${x} ${y + s} Q${x - s * 0.18} ${y + s * 0.18} ${x - s} ${y} Q${x - s * 0.18} ${y - s * 0.18} ${x} ${y - s}Z`}
      fill={fill}
    />
  )
}

/**
 * The FreshCart leaf mark, used on bags, boxes and cards.
 * @param {{ x: number, y: number, size?: number, color?: string }} props
 */
export function LeafMark({ x, y, size = 16, color = '#059669' }) {
  const k = size / 24
  return (
    <g
      transform={`translate(${x - 12 * k} ${y - 12 * k}) scale(${k})`}
      fill="none"
      stroke={color}
      strokeWidth="2.4"
      strokeLinecap="round"
    >
      <path d="M5 16c0-6 4-9 12-9-1 7-4 10-9 10-1 0-2-.3-3-1" fill={color} fillOpacity="0.15" />
      <path d="M5 18c2-3 5-5 8-6" />
    </g>
  )
}
