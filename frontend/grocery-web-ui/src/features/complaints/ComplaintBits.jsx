import { Badge } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { complaintCategoryIcon, complaintStatusLabel, complaintStatusTone } from './api'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../../shared/icons').IconName} IconName */

/**
 * The coloured bar on top of a complaint card follows its status badge.
 * @type {Record<BadgeTone, string>}
 */
const STATUS_BARS = {
  amber: 'from-amber-400 to-orange-500',
  blue: 'from-sky-400 to-blue-500',
  green: 'from-emerald-400 to-teal-500',
  red: 'from-rose-400 to-red-500',
  violet: 'from-violet-400 to-fuchsia-500',
  gray: 'from-slate-300 to-slate-400',
}

/** @type {Record<string, IconName>} */
const STATUS_ICONS = {
  OPEN: 'clock',
  IN_PROGRESS: 'hourglass',
  RESOLVED: 'checkCircle',
}

/**
 * "● Open" / "In progress" / "Resolved".
 * @param {{ status: string }} props
 */
export function ComplaintStatusBadge({ status }) {
  return (
    <Badge tone={complaintStatusTone(status)} className="gap-1 px-2.5 py-1 font-semibold">
      <Icon name={STATUS_ICONS[status] ?? 'info'} className="size-3.5" />
      {complaintStatusLabel(status)}
    </Badge>
  )
}

/**
 * A thin gradient bar across the top of a card, in the status colour (decorative).
 * @param {{ status: string }} props
 */
export function StatusBar({ status }) {
  return (
    <span
      aria-hidden="true"
      className={cn('absolute inset-x-0 top-0 h-1 bg-gradient-to-r', STATUS_BARS[complaintStatusTone(status)])}
    />
  )
}

/**
 * The category's picture on a soft tile (decorative: the category is also written next to it).
 * @param {{ category: string, className?: string }} props
 */
export function CategoryTile({ category, className }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'flex size-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-sky-50 to-indigo-50 ring-1 ring-sky-100 ring-inset',
        className,
      )}
    >
      <Icon name={complaintCategoryIcon(category)} className="size-6" />
    </span>
  )
}
