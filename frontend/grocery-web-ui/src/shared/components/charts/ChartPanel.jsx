import { Icon } from '../../icons'
import { cn } from '../../utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../icons').IconName} IconName */

/**
 * @typedef {object} ChartPanelProps
 * @property {string} title The panel's heading, also its accessible name (a `region`).
 * @property {IconName} icon
 * @property {ReactNode} children
 * @property {string} [className]
 */

/**
 * A white card around one chart (or a group of facts) on a dashboard, with a small picture and a title row.
 * @param {ChartPanelProps} props
 */
export function ChartPanel({ title, icon, children, className }) {
  return (
    <section
      aria-label={title}
      className={cn('min-w-0 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm sm:p-5', className)}
    >
      <h3 className="mb-4 flex items-center gap-2 text-sm font-semibold text-slate-900">
        <span
          aria-hidden="true"
          className="flex size-7 items-center justify-center rounded-lg bg-emerald-50 ring-1 ring-emerald-100"
        >
          <Icon name={icon} className="size-4" />
        </span>
        {title}
      </h3>
      {children}
    </section>
  )
}
