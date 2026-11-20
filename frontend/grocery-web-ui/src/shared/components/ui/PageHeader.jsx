import { Icon } from '../../icons'
import { cn } from '../../utils/cn'
import { HERO_ACCENTS, heroAccentFor, usePageHeaderStyle } from './pageHeaderStyle'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../icons').IconName} IconName */

/**
 * @typedef {object} PageHeaderProps
 * @property {string} title
 * @property {string} [description]
 * @property {ReactNode} [actions]
 * @property {string} [eyebrow] Small label above the title (e.g. the section: "Your account").
 * @property {IconName} [icon] The page's picture (the same as its menu item), on a tile before the title.
 * @property {ReactNode} [illustration] Decorative scene (see `shared/illustrations`). With it the header becomes a soft banner with the art on the right (hidden on phones, where space goes to the content).
 */

/** Soft colour shapes in the corners of a header banner. */
function Glow() {
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10 overflow-hidden">
      <div className="absolute -top-16 -right-10 size-56 rounded-full bg-emerald-200/40 blur-3xl" />
      <div className="absolute -bottom-20 left-1/3 size-48 rounded-full bg-sky-200/30 blur-3xl" />
      <div className="absolute top-1/2 -left-12 size-32 rounded-full bg-amber-200/30 blur-2xl" />
      <div className="absolute inset-0 [background-image:radial-gradient(rgb(16_185_129/0.10)_1px,transparent_1px)] [background-size:18px_18px] [mask-image:linear-gradient(to_left,black,transparent_70%)]" />
    </div>
  )
}

/**
 * The `hero` look (admin area): a colourful gradient banner in the page's colour (from its icon), white text, soft
 * lights, a dotted texture, the page's picture on a white tile and a large faint copy of it as a watermark. Actions sit
 * on a white panel so buttons and fields keep their usual look on any colour.
 * @param {PageHeaderProps} props
 */
function HeroHeader({ title, description, actions, eyebrow, icon, illustration }) {
  const colours = HERO_ACCENTS[heroAccentFor(icon, title)]
  return (
    <div
      className={cn(
        'group/header animate-fade-up relative isolate mb-6 overflow-hidden rounded-3xl bg-gradient-to-br px-5 py-6 text-white shadow-lg sm:px-7 sm:py-7',
        colours.gradient,
        colours.shadow,
      )}
    >
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10 overflow-hidden">
        <div className="absolute -top-24 -right-16 size-72 rounded-full bg-white/10 blur-2xl" />
        <div className={cn('absolute -bottom-28 left-1/4 size-64 rounded-full blur-3xl', colours.light)} />
        <div className="absolute top-4 right-1/3 size-36 rounded-full bg-white/10 blur-3xl" />
        <div className="absolute inset-0 [background-image:radial-gradient(rgb(255_255_255/0.13)_1px,transparent_1px)] [background-size:20px_20px] [mask-image:linear-gradient(to_left,black,transparent_75%)]" />
        {icon && !illustration && (
          <Icon
            name={icon}
            className="absolute -right-6 -bottom-10 size-44 rotate-12 opacity-[0.12] transition-transform duration-700 group-hover/header:rotate-6 motion-reduce:transition-none"
          />
        )}
      </div>
      <div className="flex flex-col gap-5 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex min-w-0 items-start gap-4">
          {icon && (
            <span
              aria-hidden="true"
              className="hidden size-14 shrink-0 items-center justify-center rounded-2xl bg-white shadow-lg shadow-black/15 ring-1 ring-white/60 transition-transform duration-300 group-hover/header:scale-105 group-hover/header:-rotate-3 motion-reduce:transition-none sm:flex"
            >
              <Icon name={icon} className="size-8" />
            </span>
          )}
          <div className="min-w-0">
            {eyebrow && (
              <p className="mb-2 inline-flex items-center rounded-full bg-white/15 px-3 py-1 text-[11px] font-semibold tracking-wider uppercase ring-1 ring-white/25 ring-inset">
                {eyebrow}
              </p>
            )}
            <h1 className="text-2xl font-bold tracking-tight text-white sm:text-3xl">{title}</h1>
            {description && <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-white/90">{description}</p>}
          </div>
        </div>
        {(actions || illustration) && (
          <div className="flex shrink-0 items-center gap-4">
            {actions && (
              <div className="flex flex-wrap items-end gap-2 rounded-2xl bg-white/95 p-2 text-slate-900 shadow-lg shadow-black/10 ring-1 ring-white/60">
                {actions}
              </div>
            )}
            {illustration && <div className="hidden w-32 shrink-0 sm:block lg:w-36">{illustration}</div>}
          </div>
        )}
      </div>
    </div>
  )
}

/** @param {PageHeaderProps} props */
export function PageHeader(props) {
  const style = usePageHeaderStyle()
  if (style === 'hero') return <HeroHeader {...props} />
  return <SoftHeader {...props} />
}

/** @param {PageHeaderProps} props */
function SoftHeader({ title, description, actions, eyebrow, icon, illustration }) {
  const text = (
    <div className="flex min-w-0 items-start gap-4">
      {icon && (
        <span
          aria-hidden="true"
          className="hidden size-14 shrink-0 items-center justify-center rounded-2xl bg-white shadow-md ring-1 shadow-emerald-900/10 ring-slate-200/70 transition-transform duration-300 group-hover/header:scale-105 group-hover/header:-rotate-3 sm:flex"
        >
          <Icon name={icon} className="size-8" />
        </span>
      )}
      <div className="min-w-0">
        {eyebrow && <p className="mb-1 text-xs font-semibold tracking-wider text-emerald-700 uppercase">{eyebrow}</p>}
        <h1 className="bg-gradient-to-r from-slate-900 via-slate-800 to-emerald-800 bg-clip-text text-2xl font-bold tracking-tight text-transparent sm:text-[1.7rem]">
          {title}
        </h1>
        {description && <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-slate-600">{description}</p>}
      </div>
    </div>
  )

  if (illustration) {
    return (
      <div className="group/header animate-fade-up relative isolate mb-6 overflow-hidden rounded-2xl border border-emerald-100 bg-gradient-to-br from-emerald-50 via-white to-amber-50 px-5 py-5 sm:px-7">
        <Glow />
        <div className="flex items-center gap-6">
          <div className="min-w-0 flex-1">
            {text}
            {actions && <div className="mt-4 flex flex-wrap gap-2">{actions}</div>}
          </div>
          <div className="hidden w-32 shrink-0 sm:block lg:w-40">{illustration}</div>
        </div>
      </div>
    )
  }

  return (
    <div className="group/header animate-fade-up relative isolate mb-6 overflow-hidden rounded-2xl border border-slate-200/70 bg-gradient-to-br from-white via-emerald-50/40 to-sky-50/50 px-5 py-5 shadow-sm shadow-slate-900/[0.03] sm:px-6">
      <Glow />
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        {text}
        {actions && <div className="flex shrink-0 flex-wrap gap-2">{actions}</div>}
      </div>
    </div>
  )
}
