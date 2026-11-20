import { Link } from 'react-router'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { BrandLogo } from './BrandLogo'

/** @typedef {import('react').ReactNode} ReactNode */

/**
 * @typedef {object} AuthShellProps
 * @property {ReactNode} panel The brand panel (`BrandPanel`): the left half on large screens, a short header above the form on smaller ones.
 * @property {ReactNode} children The form side.
 * @property {boolean} [wide] Gives the form side more room (registration).
 */

/**
 * Full-screen split layout of the sign-in and registration screens (AppLayout leaves its top bar and menu out on
 * these pages): the dark brand panel, and the form on white with a way back to the store.
 * @param {AuthShellProps} props
 */
export function AuthShell({ panel, children, wide }) {
  return (
    <div
      className={cn(
        'min-h-dvh bg-canvas lg:grid',
        wide ? 'lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)]' : 'lg:grid-cols-2',
      )}
    >
      {panel}
      <section className="flex flex-col px-5 pt-5 pb-6 sm:px-10 lg:px-14 lg:pt-8 xl:px-20">
        <div className="flex justify-end">
          <Link
            to="/"
            className="group inline-flex items-center gap-1.5 rounded-lg px-2 py-1 text-sm font-medium text-slate-500 transition-colors hover:text-emerald-700 focus-visible:outline-2 focus-visible:outline-emerald-600"
          >
            <Icon name="arrowLeft" className="size-4 transition-transform duration-200 group-hover:-translate-x-0.5" />
            Back to the store
          </Link>
        </div>
        <div
          className={cn(
            'mx-auto flex w-full flex-1 flex-col justify-center py-6 lg:py-10',
            wide ? 'max-w-xl' : 'max-w-[26rem]',
          )}
        >
          {children}
        </div>
        <p className="text-center text-xs text-slate-400">FreshCart · Groceries and restaurant meals, delivered.</p>
      </section>
    </div>
  )
}

/** Soft colour fields and a fading dot grid behind the brand panel. */
function Aurora() {
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
      <div className="absolute -top-48 -left-40 size-[36rem] rounded-full bg-emerald-400/25 blur-3xl" />
      <div className="absolute top-1/4 -right-48 size-[32rem] rounded-full bg-teal-300/15 blur-3xl" />
      <div className="absolute -bottom-56 left-1/5 size-[30rem] rounded-full bg-amber-300/10 blur-3xl" />
      <div className="absolute inset-0 bg-[radial-gradient(rgb(255_255_255/0.16)_1px,transparent_1px)] bg-[size:22px_22px] [mask-image:radial-gradient(ellipse_80%_70%_at_15%_15%,black,transparent)]" />
    </div>
  )
}

/**
 * @typedef {object} BrandPanelProps
 * @property {string} label Accessible name of the panel.
 * @property {string} eyebrow
 * @property {ReactNode} children
 * @property {ReactNode} [footnote] Small print pinned to the bottom of the panel on large screens.
 */

/**
 * The dark brand side (the app chrome colour): logo, eyebrow, the pitch; it stays put while the form scrolls.
 * @param {BrandPanelProps} props
 */
export function BrandPanel({ label, eyebrow, children, footnote }) {
  return (
    <aside
      aria-label={label}
      className="relative isolate flex flex-col overflow-hidden bg-chrome px-5 pt-5 pb-7 text-white sm:px-10 sm:pb-9 lg:sticky lg:top-0 lg:h-dvh lg:px-14 lg:py-10 xl:px-20"
    >
      <Aurora />
      <BrandLogo onDark />
      <div className="mt-6 sm:mt-8 lg:my-auto lg:py-8">
        <p className="hidden items-center gap-2 rounded-full bg-white/10 px-3 py-1 text-[11px] font-semibold tracking-widest text-emerald-50 uppercase ring-1 ring-white/15 backdrop-blur-sm sm:inline-flex">
          <span
            aria-hidden="true"
            className="size-1.5 rounded-full bg-emerald-300 shadow-[0_0_0_4px_rgb(110_231_183/0.2)]"
          />
          {eyebrow}
        </p>
        {children}
      </div>
      {footnote && <p className="mt-8 hidden items-center gap-2 text-xs text-emerald-100/75 lg:flex">{footnote}</p>}
    </aside>
  )
}

/**
 * @typedef {object} BrandPitchProps
 * @property {string} headline
 * @property {string} summary
 * @property {readonly string[]} items In the glass card on large screens.
 * @property {boolean} [ordered] Numbered, connected steps instead of check marks.
 * @property {string} [itemsTitle] Heading above the list.
 * @property {ReactNode} [art] The stakeholder's illustration, beside the list in the glass card.
 */

/**
 * Headline, one line of context, and a frosted card with the illustration and a short list, inside a `BrandPanel`.
 * @param {BrandPitchProps} props
 */
export function BrandPitch({ headline, summary, items, ordered, itemsTitle, art }) {
  const List = ordered ? 'ol' : 'ul'
  return (
    <>
      <h2 className="bg-gradient-to-br from-white via-white to-emerald-200 bg-clip-text text-2xl leading-[1.12] font-extrabold tracking-tight text-balance text-transparent sm:mt-5 sm:text-4xl lg:mt-6 xl:text-[2.75rem]">
        {headline}
      </h2>
      <p className="mt-2 max-w-lg text-sm text-emerald-50/80 sm:mt-3 sm:text-base lg:mt-4 lg:text-lg">{summary}</p>
      <div className="mt-8 hidden items-center gap-6 rounded-3xl bg-white/[0.07] p-5 shadow-2xl ring-1 shadow-black/20 ring-white/15 backdrop-blur-md lg:flex xl:p-6">
        {art}
        <div className="min-w-0 flex-1">
          {itemsTitle && (
            <p className="mb-3 text-[11px] font-semibold tracking-widest text-emerald-200 uppercase">{itemsTitle}</p>
          )}
          <List className="space-y-4">
            {items.map((item, index) => (
              <li key={item} className="relative flex items-start gap-3 text-sm leading-snug text-emerald-50">
                {/* The line that joins one step to the next. */}
                {ordered && index < items.length - 1 && (
                  <span aria-hidden="true" className="absolute top-7 -bottom-4 left-3 w-px bg-white/20" />
                )}
                <span
                  aria-hidden="true"
                  className="flex size-6 shrink-0 items-center justify-center rounded-full bg-emerald-300/20 text-[11px] font-bold text-emerald-100 ring-1 ring-emerald-200/30"
                >
                  {ordered ? index + 1 : <Icon name="check" className="size-3.5" />}
                </span>
                <span className="pt-0.5">{item}</span>
              </li>
            ))}
          </List>
        </div>
      </div>
    </>
  )
}
