import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { AssistantPromoCard } from '../../../features/ai/AssistantPromoCard'
import { LiveProductCatalog } from '../../../features/catalog/LiveProductCatalog'
import { SearchBox } from '../../../features/catalog/SearchBox'
import { ShopByCategory } from '../../../features/catalog/ShopByCategory'
import { NearbyRestaurants } from '../../../features/restaurant-discovery/NearbyRestaurants'
import { HERO_SLIDES } from '../../../features/promotions/heroSlides'
import { HeroSlideBackdrops, HeroSlideshow, SLIDE_INTERVAL_MS } from '../../../features/promotions/HeroSlideshow'
import { useSlideshow } from '../../../features/promotions/useSlideshow'
import { NearbyStores } from '../../../features/stores/NearbyStores'
import { useAuth } from '../../../shared/auth/useAuth'
import { Icon } from '../../../shared/icons'
import { DeliveryScooterIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'

const FOCUS_ON_DARK = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white'
const FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'
const SOLID_CTA = cn(
  'inline-flex h-10 items-center gap-2 rounded-xl bg-white px-4 text-sm font-semibold text-emerald-800 shadow-sm transition hover:bg-emerald-50',
  FOCUS_ON_DARK,
)
const OUTLINE_CTA = cn(
  'inline-flex h-10 items-center gap-2 rounded-xl px-4 text-sm font-semibold text-white ring-1 ring-white/40 transition ring-inset hover:bg-white/10',
  FOCUS_ON_DARK,
)

/** Search shortcuts under the hero's search box. */
const POPULAR_SEARCHES = ['Atta', 'Paneer', 'Basmati rice', 'Toor dal', 'Fresh fruits']

/**
 * The storefront's hero: the welcome, the search and the main links stay put while the background and the offer panel
 * turn through the promotions (HERO_SLIDES). The panel sits beside the content when the hero is wide enough (a container
 * query: the left menu takes part of the screen), otherwise under the buttons.
 */
function Hero({ firstName, signedIn }: { firstName: string | undefined; signedIn: boolean }) {
  const show = useSlideshow(HERO_SLIDES.length, SLIDE_INTERVAL_MS)
  return (
    <section
      className="animate-fade-up relative z-10 rounded-3xl bg-gradient-to-br from-emerald-600 via-emerald-700 to-teal-800 text-white shadow-lg shadow-emerald-900/15"
      {...show.focusHold}
    >
      {/* The slides' backgrounds and soft shapes, clipped to the card (the search suggestions must not be clipped, so
          the hero itself is not). A light shade on the left keeps the text readable on every slide. */}
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10 overflow-hidden rounded-3xl">
        <HeroSlideBackdrops show={show} />
        <div className="absolute inset-0 bg-gradient-to-r from-black/25 via-black/5 to-transparent" />
        <div className="absolute inset-0 [background-image:radial-gradient(rgb(255_255_255/0.14)_1px,transparent_1px)] [background-size:22px_22px] [mask-image:linear-gradient(to_bottom,black,transparent_85%)]" />
        <svg className="absolute right-0 bottom-0 h-28 w-2/3 text-white/[0.06]" viewBox="0 0 400 100" preserveAspectRatio="none" fill="currentColor">
          <path d="M0 100C80 40 160 30 240 55s120 10 160-25v70H0Z" />
        </svg>
      </div>

      <div className="@container px-5 py-8 sm:px-8 sm:py-10 lg:px-10">
        <div className="grid gap-5 @3xl:grid-cols-[minmax(0,1fr)_16rem] @3xl:gap-8 @5xl:grid-cols-[minmax(0,1fr)_19rem]">
          <div className="min-w-0">
            <p className="inline-flex items-center gap-2 rounded-full bg-white/15 px-3 py-1 text-sm font-medium text-white ring-1 ring-white/20 ring-inset backdrop-blur-sm">
              <Icon name="basket" className="size-4" />
              {firstName ? `Welcome back, ${firstName}` : 'Groceries · Restaurants · Delivery'}
            </p>
            <h1 className="mt-4 max-w-xl text-3xl leading-[1.12] font-extrabold tracking-tight text-balance sm:text-4xl">
              Fresh groceries and restaurant meals, delivered.
            </h1>
            <p className="mt-3 max-w-xl text-[0.95rem] leading-relaxed text-white/90">
              Browse local vendors and restaurants, track your order live, and get help from an AI shopping assistant.
            </p>

            <div className="max-w-3xl">
              <SearchBox
                size="lg"
                className="mt-6 text-slate-900 [&_input]:border-white [&_input]:shadow-lg [&_input]:shadow-emerald-950/20"
                label="Search groceries"
              />

              <div className="-mx-1 mt-3 flex items-center gap-2 overflow-x-auto px-1 py-1 [scrollbar-width:none]">
                <span className="shrink-0 text-xs font-semibold tracking-wide text-white/75 uppercase">Popular</span>
                {POPULAR_SEARCHES.map((term) => (
                  <Link
                    key={term}
                    to={`/search?q=${encodeURIComponent(term.toLowerCase())}`}
                    aria-label={`Search for ${term.toLowerCase()}`}
                    className={cn(
                      'shrink-0 rounded-full bg-white/10 px-3 py-1 text-xs font-medium whitespace-nowrap text-white ring-1 ring-white/20 transition ring-inset hover:bg-white/20',
                      FOCUS_ON_DARK,
                    )}
                  >
                    {term}
                  </Link>
                ))}
              </div>

              <div className="mt-6 flex flex-wrap gap-2.5">
                {signedIn ? (
                  <>
                    <Link to="/restaurants" className={SOLID_CTA}>
                      <Icon name="cutlery" className="size-4" />
                      Order from restaurants
                    </Link>
                    <Link to="/orders" className={OUTLINE_CTA}>
                      <Icon name="box" className="size-4" />
                      My orders
                    </Link>
                    <Link to="/account/addresses" className={OUTLINE_CTA}>
                      <Icon name="mapPin" className="size-4" />
                      Manage delivery addresses
                    </Link>
                  </>
                ) : (
                  <>
                    <Link to="/register" className={SOLID_CTA}>
                      <Icon name="user" className="size-4" />
                      Create an account
                    </Link>
                    <Link to="/register?role=VENDOR" className={OUTLINE_CTA}>
                      <Icon name="store" className="size-4" />
                      Sell on FreshCart
                    </Link>
                    <Link to="/register?role=DELIVERY_PARTNER" className={OUTLINE_CTA}>
                      <Icon name="scooter" className="size-4" />
                      Deliver with us
                    </Link>
                  </>
                )}
              </div>
            </div>
          </div>

          <HeroSlideshow show={show} />
        </div>
      </div>
    </section>
  )
}

const VALUE_PROPS: Array<{ title: string; text: string; icon: ReactNode; tint: string }> = [
  { title: 'Fast delivery', text: 'From stores near you', icon: <Icon name="bolt" className="size-5" />, tint: 'bg-amber-100' },
  { title: 'Fresh & local', text: 'Packed by local stores', icon: <Icon name="leaf" className="size-5" />, tint: 'bg-emerald-100' },
  { title: 'Live tracking', text: 'Watch your order move', icon: <Icon name="route" className="size-5" />, tint: 'bg-sky-100' },
  { title: 'AI assistant', text: 'Shop in your own words', icon: <Icon name="sparkle" className="size-5" />, tint: 'bg-violet-100' },
]

function ValueProps() {
  return (
    <section aria-label="Why FreshCart" className="animate-fade-up">
      <ul className="grid grid-cols-2 gap-px overflow-hidden rounded-2xl border border-slate-200/80 bg-slate-200/60 shadow-sm shadow-slate-900/[0.03] lg:grid-cols-4">
        {VALUE_PROPS.map((prop) => (
          <li key={prop.title} className="flex items-center gap-3 bg-white px-3.5 py-3.5 sm:px-4">
            <span className={cn('flex size-10 shrink-0 items-center justify-center rounded-xl', prop.tint)}>{prop.icon}</span>
            <span className="min-w-0">
              <span className="block text-sm leading-snug font-semibold text-slate-900">{prop.title}</span>
              <span className="block text-xs leading-snug text-slate-500">{prop.text}</span>
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}

function DeliveryPromoCard({ signedIn }: { signedIn: boolean }) {
  return (
    <div className="relative flex overflow-hidden rounded-2xl border border-amber-100 bg-gradient-to-br from-amber-50 via-white to-emerald-50 p-5 shadow-sm sm:p-6">
      <div aria-hidden="true" className="pointer-events-none absolute -bottom-16 -left-10 size-48 rounded-full bg-amber-200/40 blur-2xl" />
      <div className="relative flex min-w-0 flex-1 flex-col">
        <p className="flex items-center gap-1.5 text-xs font-semibold tracking-wider text-amber-700 uppercase">
          <Icon name="route" className="size-4" />
          On its way
        </p>
        <h2 className="mt-1.5 text-lg font-semibold text-slate-900">
          Delivery you can follow
        </h2>
        <p className="mt-1.5 max-w-sm text-sm leading-relaxed text-slate-600">
          Local stores pack your order and a delivery partner brings it over. Watch each step live, from the store to your door.
        </p>
        <div className="mt-auto pt-4">
          <Link
            to={signedIn ? '/orders' : '/restaurants'}
            className={cn(
              'inline-flex h-10 items-center gap-2 rounded-xl bg-white px-4 text-sm font-semibold text-emerald-700 shadow-xs ring-1 ring-emerald-200 ring-inset hover:bg-emerald-50 hover:text-emerald-800',
              FOCUS,
            )}
          >
            {signedIn ? 'Track your orders' : 'Explore restaurants'}
            <Icon name="arrowRight" className="size-4" />
          </Link>
        </div>
      </div>
      <div className="relative ml-3 hidden w-32 shrink-0 self-center sm:block xl:w-40">
        <DeliveryScooterIllustration className="animate-float" />
      </div>
    </div>
  )
}

export function StorefrontPage() {
  const { user } = useAuth()
  const firstName = user?.fullName.split(' ')[0]
  const signedIn = Boolean(user)

  return (
    <div className="space-y-10">
      <div className="space-y-4">
        <Hero firstName={firstName} signedIn={signedIn} />
        <ValueProps />
      </div>

      <NearbyStores />

      <ShopByCategory />

      <LiveProductCatalog />

      <NearbyRestaurants />

      {/* Two columns only when the content column is wide enough (a container query: the sidebar takes
          part of the screen), otherwise each card squeezes its text to a word per line. */}
      <div className="animate-fade-up @container">
        <div className="grid gap-4 @3xl:grid-cols-2">
          <AssistantPromoCard signedIn={signedIn} />
          <DeliveryPromoCard signedIn={signedIn} />
        </div>
      </div>
    </div>
  )
}
