import { Icon } from '../../icons'
import { cn } from '../../utils/cn'
import { ACCENT_CLASSES } from './statAccents'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../icons').IconName} IconName */
/** @typedef {import('./statAccents').StatAccent} StatAccent */

/**
 * @typedef {object} FeatureCardProps
 * @property {string} title
 * @property {IconName} icon
 * @property {StatAccent} [accent]
 * @property {ReactNode} [action] A link or button at the end of the heading row ("Add product", "Edit menu").
 * @property {ReactNode} children
 * @property {string} [className]
 */

/**
 * A dashboard tile: colour bar on top, a picture on a soft tile beside the `h3`, a lift on hover. The body grows, so
 * a link at the end of `children` lines up across a row of tiles.
 * @param {FeatureCardProps} props
 */
export function FeatureCard({ title, icon, accent = 'emerald', action, children, className }) {
  const colours = ACCENT_CLASSES[accent]
  return (
    <section
      className={cn(
        'group/feature animate-fade-up relative isolate flex flex-col overflow-hidden rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm shadow-slate-900/[0.04]',
        'transition-all duration-300 hover:-translate-y-1 hover:shadow-xl hover:shadow-slate-900/[0.08]',
        className,
      )}
    >
      <span aria-hidden="true" className={cn('absolute inset-x-0 top-0 h-1 bg-gradient-to-r', colours.bar)} />
      <span
        aria-hidden="true"
        className={cn(
          'absolute -top-10 -right-10 -z-10 size-32 rounded-full blur-3xl transition-transform duration-500 group-hover/feature:scale-125',
          colours.glow,
        )}
      />
      <div className="flex items-start justify-between gap-3">
        <h3 className="flex items-center gap-2.5 text-sm font-semibold text-slate-900">
          <span
            aria-hidden="true"
            className={cn(
              'flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br ring-1 transition-transform duration-300 group-hover/feature:scale-110 group-hover/feature:-rotate-6',
              colours.tile,
            )}
          >
            <Icon name={icon} className="size-5" />
          </span>
          {title}
        </h3>
        {action && <div className="shrink-0 pt-1.5 text-sm">{action}</div>}
      </div>
      <div className="mt-3 flex flex-1 flex-col">{children}</div>
    </section>
  )
}
