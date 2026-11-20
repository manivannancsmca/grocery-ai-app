import { cn } from '../../utils/cn'
import { initialsOf } from '../../utils/initials'

/** Gradient pairs; a name always gets the same one. */
const GRADIENTS = [
  'from-emerald-400 to-teal-500',
  'from-sky-400 to-indigo-500',
  'from-violet-400 to-fuchsia-500',
  'from-amber-400 to-orange-500',
  'from-rose-400 to-pink-500',
  'from-cyan-400 to-blue-500',
  'from-lime-400 to-emerald-500',
]

/**
 * @param {string} name
 * @returns {string}
 */
function gradientFor(name) {
  let hash = 0
  for (const char of name) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  return GRADIENTS[hash % GRADIENTS.length] ?? GRADIENTS[0]
}

/**
 * @typedef {object} AvatarProps
 * @property {string | null | undefined} name
 * @property {'sm' | 'md'} [size]
 * @property {string} [className]
 */

/**
 * A person's or business's initials on a colourful tile (decorative: the name is always written next to it).
 * @param {AvatarProps} props
 */
export function Avatar({ name, size = 'md', className }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'flex shrink-0 items-center justify-center bg-gradient-to-br font-bold text-white shadow-sm',
        size === 'sm' ? 'size-8 rounded-lg text-[11px]' : 'size-10 rounded-xl text-sm',
        gradientFor(name ?? ''),
        className,
      )}
    >
      {initialsOf(name)}
    </span>
  )
}
