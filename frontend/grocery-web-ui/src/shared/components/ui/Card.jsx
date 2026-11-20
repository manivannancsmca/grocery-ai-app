import { Icon } from '../../icons'
import { cn } from '../../utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../icons').IconName} IconName */

/**
 * @typedef {object} CardProps
 * @property {string} [title]
 * @property {string} [description]
 * @property {ReactNode} [actions]
 * @property {IconName} [icon] A colourful picture on a soft tile before the title.
 * @property {ReactNode} [children]
 * @property {string} [className]
 */

/** @param {CardProps} props */
export function Card({ title, description, actions, icon, children, className }) {
  const hasHeader = Boolean(title ?? description ?? actions)

  return (
    <section
      className={cn(
        'rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm shadow-slate-900/[0.04] transition-shadow duration-300 hover:shadow-md hover:shadow-slate-900/[0.06]',
        className,
      )}
    >
      {/* The actions drop under the title when both do not fit on one line. */}
      {hasHeader && (
        <header className="mb-4 flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
          <div className="flex min-w-0 flex-1 basis-56 items-start gap-3">
            {icon && (
              <span
                aria-hidden="true"
                className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-50 to-teal-50 ring-1 ring-emerald-100"
              >
                <Icon name={icon} className="size-5" />
              </span>
            )}
            <div className={cn('min-w-0', icon && 'pt-0.5')}>
              {title && <h2 className="text-base font-semibold text-slate-900">{title}</h2>}
              {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
            </div>
          </div>
          {actions && <div className="shrink-0">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  )
}
