import { useRef } from 'react'
import { Link } from 'react-router'
import { useCategoryTree } from '../catalog/hooks'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { HERO_SLIDES, slidePhoto } from './heroSlides'

/** @typedef {import('react').CSSProperties} CSSProperties */
/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('react').SyntheticEvent} SyntheticEvent */
/** @typedef {import('./heroSlides').HeroSlide} HeroSlide */
/** @typedef {import('./useSlideshow').Slideshow} Slideshow */

/** How long each slide stays (the active dot fills up over this time). */
export const SLIDE_INTERVAL_MS = 6500

const FOCUS_ON_DARK = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white'
/** The house easing (the same curve as the app's entrance animations). */
const EASE = 'ease-[cubic-bezier(0.22,1,0.36,1)]'
/** A finger moving at least this far sideways on the slides turns them. */
const SWIPE_PX = 40

/**
 * A photo that does not load leaves its circle empty instead of showing a broken image.
 * @param {SyntheticEvent<HTMLImageElement>} event
 */
function hideBroken(event) {
  event.currentTarget.style.visibility = 'hidden'
}

/**
 * The hero's moving background: every slide's gradient, glows and a faded product photo, stacked, with the current
 * slide faded in. Decorative (the parent is aria-hidden). Photos load only for slides that are shown or come next.
 * @param {{ show: Slideshow }} props
 */
export function HeroSlideBackdrops({ show }) {
  return HERO_SLIDES.map((slide, position) => {
    const active = position === show.index
    return (
      <div
        key={slide.id}
        data-slide-backdrop={slide.id}
        className={cn(
          'absolute inset-0 isolate bg-gradient-to-br transition-opacity duration-1000 ease-out',
          slide.theme.gradient,
          active ? 'opacity-100' : 'opacity-0',
        )}
      >
        <div className={cn('absolute -top-24 -right-20 size-80 rounded-full blur-3xl', slide.theme.glows[0])} />
        <div className={cn('absolute -bottom-32 left-1/4 size-80 rounded-full blur-3xl', slide.theme.glows[1])} />
        {show.ready.has(position) && (
          // The slide's main photo as a texture on the right, fading out towards the text.
          <img
            src={slidePhoto(slide.photos[0])}
            alt=""
            draggable={false}
            onError={hideBroken}
            className="absolute inset-y-0 right-0 h-full w-3/4 object-cover opacity-25 mix-blend-luminosity [mask-image:linear-gradient(to_left,black_20%,transparent)] sm:w-3/5"
            style={active ? { animation: 'hero-zoom 9s ease-out both' } : undefined}
          />
        )}
      </div>
    )
  })
}

/**
 * "Shop now" opens the slide's category when the catalog has it, otherwise a search for its products.
 * @returns {(slide: HeroSlide) => string}
 */
function useShopLink() {
  const categories = useCategoryTree()
  return (slide) => {
    const wanted = slide.category.toLowerCase()
    const match = (categories.data ?? []).find((category) => category.name.toLowerCase() === wanted)
    return match ? `/search?categoryId=${encodeURIComponent(match.id)}` : `/search?q=${encodeURIComponent(slide.query)}`
  }
}

/**
 * How a photo comes in: its transition classes and delay.
 * @typedef {object} Entrance
 * @property {string} className
 * @property {CSSProperties} style
 */

/**
 * A round product photo (an empty circle until the slide's photos may load).
 * @param {{ file: string, ready: boolean, className: string, entrance: Entrance }} props
 */
function Photo({ file, ready, className, entrance }) {
  const frame = cn('rounded-full shadow-xl shadow-black/25 ring-white/90', className, entrance.className)
  if (!ready) return <span aria-hidden="true" className={cn('block bg-white/15', frame)} style={entrance.style} />
  return (
    <img
      src={slidePhoto(file)}
      alt=""
      draggable={false}
      onError={hideBroken}
      className={cn('object-cover', frame)}
      style={entrance.style}
    />
  )
}

/**
 * One promotion: a photo cluster with the offer on a wide hero, a compact strip on a narrow one.
 * @param {{ slide: HeroSlide, position: number, active: boolean, ready: boolean, href: string }} props
 */
function Slide({ slide, position, active, ready, href }) {
  // The photos come in one after another, from the right; the outgoing slide simply fades.
  const enter = (delayMs) => ({
    className: cn(
      'transition duration-700',
      EASE,
      active ? 'translate-x-0 scale-100 opacity-100' : 'translate-x-6 scale-90 opacity-0',
    ),
    style: { transitionDelay: active ? `${delayMs}ms` : '0ms' },
  })
  const [main, second, third] = slide.photos
  const shopLabel = `Shop now: ${slide.eyebrow}`

  return (
    <div
      role="group"
      aria-roledescription="slide"
      aria-label={`${position + 1} of ${HERO_SLIDES.length}: ${slide.eyebrow}`}
      aria-hidden={active ? undefined : true}
      inert={!active}
      className={cn(
        'min-w-0 [grid-area:1/1] transition-opacity duration-700',
        EASE,
        active ? 'opacity-100' : 'pointer-events-none opacity-0',
      )}
    >
      {/* Wide hero: three overlapping photos, then the offer. Top-aligned, so the photos stay put whatever the
          length of the title; a soft shadow keeps the text readable on a light photo. */}
      <div className="hidden flex-col items-center text-center [text-shadow:0_1px_10px_rgb(0_0_0/0.25)] @3xl:flex">
        <div className="relative h-48 w-full max-w-64 @5xl:h-52 @5xl:max-w-72">
          <div aria-hidden="true" className="absolute inset-8 rounded-full bg-white/20 blur-2xl" />
          <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2">
            <Photo file={main} ready={ready} entrance={enter(0)} className="size-36 ring-4 @5xl:size-40" />
          </div>
          <div className="absolute bottom-1 left-1">
            <Photo file={second} ready={ready} entrance={enter(120)} className="size-20 ring-[3px] @5xl:size-24" />
          </div>
          <div className="absolute top-0 right-2">
            <Photo file={third} ready={ready} entrance={enter(220)} className="size-16 ring-[3px] @5xl:size-20" />
          </div>
        </div>
        <p className="mt-3 text-xs font-semibold tracking-wider text-white/80 uppercase">{slide.eyebrow}</p>
        <p className="mt-1 max-w-64 text-lg leading-snug font-bold text-balance">{slide.title}</p>
        <p className="mt-1 max-w-64 text-sm leading-snug text-white/85">{slide.text}</p>
        <Link
          to={href}
          aria-label={shopLabel}
          className={cn(
            'mt-3 inline-flex h-9 items-center gap-1.5 rounded-xl bg-white px-3.5 text-sm font-semibold shadow-sm transition [text-shadow:none] hover:bg-white/90',
            slide.theme.accent,
            FOCUS_ON_DARK,
          )}
        >
          Shop now
          <Icon name="chevronRight" className="size-4" />
        </Link>
      </div>

      {/* Narrow hero: a strip under the buttons. On a phone two photos and "Shop", so the title has room for two
          lines. */}
      <div className="flex items-center gap-3 rounded-2xl bg-black/15 p-2.5 pr-3 ring-1 ring-white/20 ring-inset backdrop-blur-sm @3xl:hidden">
        <div className="flex shrink-0 -space-x-3">
          {slide.photos.map((file, i) => (
            <Photo
              key={file}
              file={file}
              ready={ready}
              entrance={enter(i * 90)}
              className={cn('size-11 ring-2', i === 2 && 'hidden @md:block')}
            />
          ))}
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[0.7rem] font-semibold tracking-wider text-white/80 uppercase">{slide.eyebrow}</p>
          <p className="line-clamp-2 text-sm leading-snug font-semibold">{slide.title}</p>
        </div>
        <Link
          to={href}
          aria-label={shopLabel}
          className={cn(
            'inline-flex h-8 shrink-0 items-center gap-1 rounded-lg bg-white px-2.5 text-xs font-semibold shadow-sm transition hover:bg-white/90',
            slide.theme.accent,
            FOCUS_ON_DARK,
          )}
        >
          Shop<span className="hidden @md:inline"> now</span>
          <Icon name="chevronRight" className="size-3.5" />
        </Link>
      </div>
    </div>
  )
}

/** @param {{ label: string, onClick: () => void, children: ReactNode }} props */
function ControlButton({ label, onClick, children }) {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className={cn(
        'flex size-8 items-center justify-center rounded-full bg-white/15 text-white ring-1 ring-white/25 backdrop-blur-sm transition ring-inset hover:bg-white/25',
        FOCUS_ON_DARK,
      )}
    >
      {children}
    </button>
  )
}

/**
 * Play / pause first (the carousel pattern), then a dot per slide (the current one fills up), then back / forward.
 * @param {{ show: Slideshow }} props
 */
function SlideControls({ show }) {
  return (
    <div className="mt-3 flex items-center justify-between gap-2 @3xl:mt-4 @3xl:justify-center">
      <div className="flex items-center gap-1">
        <ControlButton label={show.playing ? 'Pause the offers' : 'Play the offers'} onClick={show.togglePlaying}>
          <Icon name={show.playing ? 'pause' : 'play'} className="size-3.5" />
        </ControlButton>
        <div className="flex items-center">
          {HERO_SLIDES.map((slide, position) => {
            const active = position === show.index
            return (
              <button
                key={slide.id}
                type="button"
                aria-label={`Show offer ${position + 1} of ${HERO_SLIDES.length}: ${slide.eyebrow}`}
                aria-current={active ? 'true' : undefined}
                onClick={() => show.go(position)}
                className={cn('group flex h-6 min-w-6 items-center justify-center rounded-full px-0.5', FOCUS_ON_DARK)}
              >
                <span
                  className={cn(
                    'relative block h-1.5 overflow-hidden rounded-full transition-all duration-500',
                    active ? 'w-7 bg-white/35' : 'w-1.5 bg-white/55 group-hover:bg-white/85',
                  )}
                >
                  {active &&
                    (show.running ? (
                      // Restarts with each slide and each resume, in step with the countdown.
                      <span
                        key={`${show.index}`}
                        className="absolute inset-0 origin-left rounded-full bg-white"
                        style={{ animation: `grow-x ${SLIDE_INTERVAL_MS}ms linear both` }}
                      />
                    ) : (
                      <span className="absolute inset-0 rounded-full bg-white" />
                    ))}
                </span>
              </button>
            )
          })}
        </div>
      </div>
      <div className="flex items-center gap-1.5">
        <ControlButton label="Previous offer" onClick={show.previous}>
          <Icon name="chevronRight" className="size-4 rotate-180" />
        </ControlButton>
        <ControlButton label="Next offer" onClick={show.next}>
          <Icon name="chevronRight" className="size-4" />
        </ControlButton>
      </div>
    </div>
  )
}

/**
 * The hero's slide panel (WAI-ARIA carousel): the current promotion and its controls. A pointer resting on it holds
 * the slideshow; a swipe turns it. Screen readers hear a new slide only when it was not turned automatically.
 * @param {{ show: Slideshow, className?: string }} props
 */
export function HeroSlideshow({ show, className }) {
  const shopLink = useShopLink()
  const touchStart = useRef(null)

  return (
    <div
      role="region"
      aria-roledescription="carousel"
      aria-label="Offers"
      className={cn('flex min-w-0 flex-col', className)}
      {...show.pointerHold}
      onTouchStart={(event) => {
        touchStart.current = event.touches[0]?.clientX ?? null
      }}
      onTouchEnd={(event) => {
        const start = touchStart.current
        const end = event.changedTouches[0]?.clientX
        touchStart.current = null
        if (start == null || end == null || Math.abs(end - start) < SWIPE_PX) return
        if (end < start) show.next()
        else show.previous()
      }}
    >
      {/* One shrinkable column (an `auto` one grew to a title's unwrapped width on a phone). */}
      <div className="grid flex-1 grid-cols-1" aria-live={show.running ? 'off' : 'polite'}>
        {HERO_SLIDES.map((slide, position) => (
          <Slide
            key={slide.id}
            slide={slide}
            position={position}
            active={position === show.index}
            ready={show.ready.has(position)}
            href={shopLink(slide)}
          />
        ))}
      </div>
      <SlideControls show={show} />
    </div>
  )
}
