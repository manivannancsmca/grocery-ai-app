import { Badge, type BadgeTone } from '../../shared/components/ui'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { complaintCategoryIcon, complaintStatusLabel, complaintStatusTone } from './api'

/** The coloured bar on top of a complaint card follows its status badge. */
const STATUS_BARS: Record<BadgeTone, string> = {
  amber: 'from-amber-400 to-orange-500',
  blue: 'from-sky-400 to-blue-500',
  green: 'from-emerald-400 to-teal-500',
  red: 'from-rose-400 to-red-500',
  violet: 'from-violet-400 to-fuchsia-500',
  gray: 'from-slate-300 to-slate-400',
}

const STATUS_ICONS: Record<string, IconName> = {
  OPEN: 'clock',
  IN_PROGRESS: 'hourglass',
  RESOLVED: 'checkCircle',
}

/** "● Open" / "In progress" / "Resolved". */
export function ComplaintStatusBadge({ status }: { status: string }) {
  return (
    <Badge tone={complaintStatusTone(status)} className="gap-1 px-2.5 py-1 font-semibold">
      <Icon name={STATUS_ICONS[status] ?? 'info'} className="size-3.5" />
      {complaintStatusLabel(status)}
    </Badge>
  )
}

/** A thin gradient bar across the top of a card, in the status colour (decorative). */
export function StatusBar({ status }: { status: string }) {
  return <span aria-hidden="true" className={cn('absolute inset-x-0 top-0 h-1 bg-gradient-to-r', STATUS_BARS[complaintStatusTone(status)])} />
}

/** The category's picture on a soft tile (decorative: the category is also written next to it). */
export function CategoryTile({ category, className }: { category: string; className?: string }) {
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
