import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../../shared/icons').IconName} IconName */

/**
 * @typedef {object} HeaderStat
 * @property {string} label
 * @property {ReactNode} value
 * @property {IconName} icon
 * @property {'emerald' | 'amber' | 'sky' | 'violet' | 'rose' | 'slate'} [tone] Tint of the icon disc.
 */

/**
 * Tailwind needs literal class names: the stats grid by number of tiles.
 * @type {Record<number, string>}
 */
const STAT_COLUMNS = { 1: 'sm:max-w-xs', 2: 'sm:max-w-2xl sm:grid-cols-2', 3: 'sm:grid-cols-3', 4: 'sm:grid-cols-4' }

/** @type {Record<NonNullable<HeaderStat['tone']>, string>} */
const TONES = {
  emerald: 'bg-emerald-50 ring-emerald-100',
  amber: 'bg-amber-50 ring-amber-100',
  sky: 'bg-sky-50 ring-sky-100',
  violet: 'bg-violet-50 ring-violet-100',
  rose: 'bg-rose-50 ring-rose-100',
  slate: 'bg-slate-50 ring-slate-200',
}

/**
 * @typedef {object} ShopperPageHeaderProps
 * @property {string} title
 * @property {ReactNode} [description]
 * @property {IconName} icon
 * @property {string} [eyebrow]
 * @property {ReactNode} [actions]
 * @property {readonly HeaderStat[]} [stats] Small summary tiles under the title (counts, totals).
 * @property {string} [className]
 */

/**
 * The compact head of the shopper's pages: icon tile, title, one line of help and the page's actions on one row,
 * and optional summary tiles. Content starts higher than under a hero banner, which matters on phones.
 * @param {ShopperPageHeaderProps} props
 */
export function ShopperPageHeader({ title, description, icon, eyebrow, actions, stats, className }) {
  return (
    <header className={cn('animate-fade-up mb-6 space-y-4', className)}>
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 items-center gap-3.5">
          <span
            aria-hidden="true"
            className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-white to-emerald-50 shadow-sm ring-1 shadow-emerald-900/5 ring-emerald-100"
          >
            <Icon name={icon} className="size-7" />
          </span>
          <div className="min-w-0">
            {eyebrow && (
              <p className="text-[0.7rem] font-semibold tracking-wider text-emerald-700 uppercase">{eyebrow}</p>
            )}
            <h1 className="text-2xl leading-tight font-bold tracking-tight break-words text-slate-900 sm:text-[1.65rem]">
              {title}
            </h1>
            {description && <p className="mt-0.5 text-sm text-slate-500">{description}</p>}
          </div>
        </div>
        {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {stats && stats.length > 0 && (
        <dl className={cn('grid grid-cols-2 gap-3', STAT_COLUMNS[Math.min(stats.length, 4)])}>
          {stats.map((stat) => (
            <div
              key={stat.label}
              className="flex items-center gap-3 rounded-2xl border border-slate-200/80 bg-white px-3.5 py-3 shadow-xs"
            >
              <span
                aria-hidden="true"
                className={cn(
                  'flex size-9 shrink-0 items-center justify-center rounded-xl ring-1',
                  TONES[stat.tone ?? 'emerald'],
                )}
              >
                <Icon name={stat.icon} className="size-5" />
              </span>
              <div className="min-w-0">
                <dt className="truncate text-xs font-medium text-slate-500">{stat.label}</dt>
                <dd className="text-lg leading-tight font-bold text-slate-900 tabular-nums">{stat.value}</dd>
              </div>
            </div>
          ))}
        </dl>
      )}
    </header>
  )
}

/** A toolbar card that sticks under the app header while the list scrolls (search, filters, sort). */
export const STICKY_TOOLBAR =
  'sticky top-16 z-20 -mx-4 border-y border-slate-200/70 bg-white/85 px-4 py-3 shadow-xs backdrop-blur-md sm:-mx-6 sm:px-6 lg:mx-0 lg:rounded-2xl lg:border lg:px-4'

/**
 * A pill toggle ("Pure veg", "Open now", category chips): `aria-pressed` buttons.
 * @param {{ pressed: boolean, onClick: () => void, children: ReactNode, icon?: ReactNode, count?: number, role?: 'switch' }} props
 */
export function PillToggle({ pressed, onClick, children, icon, count, role }) {
  return (
    <button
      type="button"
      role={role}
      aria-pressed={role === 'switch' ? undefined : pressed}
      aria-checked={role === 'switch' ? pressed : undefined}
      onClick={onClick}
      className={cn(
        'inline-flex h-9 shrink-0 items-center gap-1.5 rounded-full pr-3.5 pl-2.5 text-sm font-medium whitespace-nowrap ring-1 transition ring-inset',
        'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
        pressed
          ? 'bg-slate-900 text-white shadow-sm ring-slate-900'
          : 'bg-white text-slate-700 ring-slate-200 hover:bg-slate-50 hover:ring-slate-300',
      )}
    >
      {icon && (
        <span
          aria-hidden="true"
          className={cn('-ml-0.5 flex size-6 items-center justify-center rounded-full', pressed && 'bg-white')}
        >
          {pressed && role === 'switch' ? <Icon name="check" className="size-3.5 text-slate-900" /> : icon}
        </span>
      )}
      {children}
      {count !== undefined && count > 0 && (
        <span
          className={cn(
            'rounded-full px-1.5 text-xs tabular-nums',
            pressed ? 'bg-white/20 text-white' : 'bg-slate-100 text-slate-500',
          )}
        >
          {count}
          <span className="sr-only"> results</span>
        </span>
      )}
    </button>
  )
}
