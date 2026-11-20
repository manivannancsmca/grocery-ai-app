import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('./theme').CuisineTheme} CuisineTheme */

/**
 * @typedef {object} RestaurantCoverProps
 * @property {CuisineTheme} theme
 * @property {string} [className]
 * @property {boolean} [muted] Muted colours (closed restaurants).
 * @property {'md' | 'lg' | 'none'} [disc] Size of the icon disc in the middle; `none` leaves the cover plain (the caller places its own art).
 * @property {ReactNode} [children]
 */

/**
 * The decorative gradient cover: soft bubbles, a dot texture and the cuisine icon on a white disc.
 * @param {RestaurantCoverProps} props
 */
export function RestaurantCover({ theme, className, muted = false, disc = 'md', children }) {
  return (
    <div
      className={cn(
        'relative isolate overflow-hidden bg-gradient-to-br',
        theme.cover,
        muted && 'saturate-[0.35]',
        className,
      )}
    >
      <div aria-hidden="true" className="absolute inset-0 -z-10">
        <span className="absolute -top-10 -right-8 size-36 rounded-full bg-white/25" />
        <span className="absolute -bottom-12 -left-6 size-28 rounded-full bg-white/20" />
        <span className="absolute top-3 left-1/3 size-3 rounded-full bg-white/50" />
        <span className="absolute right-1/4 bottom-4 size-2 rounded-full bg-white/60" />
        <span className="absolute inset-0 bg-[radial-gradient(rgb(255_255_255/0.45)_1px,transparent_1.5px)] [background-size:14px_14px] opacity-70" />
      </div>
      {disc !== 'none' && (
        <span
          aria-hidden="true"
          className={cn(
            'absolute top-1/2 left-1/2 flex -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full bg-white/90 shadow-lg ring-4 shadow-black/5 ring-white/40 transition-transform duration-300 group-hover:scale-105',
            disc === 'lg' ? 'size-20' : 'size-14',
          )}
        >
          <Icon name={theme.icon} className={disc === 'lg' ? 'size-11' : 'size-8'} />
        </span>
      )}
      {children}
    </div>
  )
}
