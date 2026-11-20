import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { BrandLogo } from './BrandLogo'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/icons').IconName} IconName */

/**
 * @typedef {object} AuthCardProps
 * @property {string} title
 * @property {ReactNode} [description]
 * @property {IconName} [icon] The picture in the badge above the title.
 * @property {'brand' | 'danger'} [tone] Badge and glow colour: `danger` for blocking states.
 * @property {ReactNode} children
 */

/**
 * Full-screen auth state with no form beside it (the suspended screen): the logo, and one frosted card centred on a
 * soft colour wash.
 * @param {AuthCardProps} props
 */
export function AuthCard({ title, description, icon, tone = 'brand', children }) {
  const danger = tone === 'danger'
  return (
    <div className="relative isolate flex min-h-dvh flex-col items-center overflow-hidden bg-canvas px-4 py-6 sm:py-10">
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
        <div className="absolute -top-56 left-1/2 size-[42rem] -translate-x-1/2 rounded-full bg-emerald-200/45 blur-3xl" />
        <div
          className={cn(
            'absolute -right-40 -bottom-56 size-[32rem] rounded-full blur-3xl',
            danger ? 'bg-rose-200/45' : 'bg-teal-200/45',
          )}
        />
        <div className="absolute inset-0 bg-[radial-gradient(rgb(15_23_42/0.07)_1px,transparent_1px)] bg-[size:22px_22px] [mask-image:radial-gradient(ellipse_60%_60%_at_50%_40%,black,transparent)]" />
      </div>
      <BrandLogo />
      <div className="my-auto w-full max-w-md py-8">
        <div className="animate-fade-up rounded-3xl bg-white/90 p-6 text-center shadow-2xl ring-1 shadow-slate-900/10 ring-slate-200/70 backdrop-blur-xl sm:p-10">
          {icon && (
            <span
              className={cn(
                'mx-auto mb-5 flex size-16 items-center justify-center rounded-2xl shadow-lg ring-1',
                danger
                  ? 'bg-red-50 shadow-red-500/10 ring-red-100'
                  : 'bg-emerald-50 shadow-emerald-500/10 ring-emerald-100',
              )}
              aria-hidden="true"
            >
              <Icon name={icon} className="size-8" />
            </span>
          )}
          <h1 className="text-2xl font-extrabold tracking-tight text-slate-900 sm:text-3xl">{title}</h1>
          {description && <p className="mt-2 text-sm text-slate-500">{description}</p>}
          <div className="mt-6 text-left">{children}</div>
        </div>
      </div>
    </div>
  )
}
