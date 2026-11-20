import { Icon } from '../../icons'
import { cn } from '../../utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../icons').IconName} IconName */

/** @typedef {'info' | 'success' | 'warning' | 'danger' | 'neutral'} AlertTone */

/** @type {Record<AlertTone, { box: string, title: string }>} */
const toneClasses = {
  info: { box: 'border-sky-200 bg-sky-50 text-sky-800', title: 'text-sky-900' },
  success: { box: 'border-emerald-200 bg-emerald-50 text-emerald-800', title: 'text-emerald-900' },
  warning: { box: 'border-amber-200 bg-amber-50 text-amber-800', title: 'text-amber-900' },
  danger: { box: 'border-red-200 bg-red-50 text-red-800', title: 'text-red-900' },
  neutral: { box: 'border-slate-200 bg-slate-50 text-slate-700', title: 'text-slate-900' },
}

/** @type {Record<AlertTone, IconName>} */
const TONE_ICONS = {
  info: 'info',
  success: 'checkCircle',
  warning: 'warning',
  danger: 'alertCircle',
  neutral: 'info',
}

/**
 * @typedef {object} AlertProps
 * @property {AlertTone} [tone]
 * @property {ReactNode} [title]
 * @property {ReactNode} [children]
 * @property {ReactNode} [actions]
 * @property {'alert' | 'status'} [role] `alert` for errors the user must notice now; `status` for informational banners.
 * @property {string} [className]
 */

/** @param {AlertProps} props */
export function Alert({ tone = 'info', title, children, actions, role, className }) {
  const classes = toneClasses[tone]
  return (
    <div role={role} className={cn('rounded-xl border p-4', classes.box, className)}>
      <div className="flex items-start gap-3">
        <Icon name={TONE_ICONS[tone]} className="mt-0.5 size-5" />
        <div className="min-w-0 flex-1 text-sm">
          {title && <p className={cn('font-semibold', classes.title)}>{title}</p>}
          {children && <div className={cn(title ? 'mt-1' : undefined)}>{children}</div>}
          {actions && <div className="mt-3 flex flex-wrap gap-2">{actions}</div>}
        </div>
      </div>
    </div>
  )
}
