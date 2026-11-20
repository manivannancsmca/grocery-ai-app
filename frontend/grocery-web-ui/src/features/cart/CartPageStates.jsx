/** Cart page title, loading skeleton and the empty cart (with shortcuts to start an order). */

import { Link } from 'react-router'
import { Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { EmptyCartIllustration } from '../../shared/illustrations'
import { cn } from '../../shared/utils/cn'
import { CARD, COLUMNS } from './cartPageStyles'

/** @typedef {import('../../shared/icons').IconName} IconName */

/** @param {{ description: string }} props */
export function PageTitle({ description }) {
  return (
    <header className="animate-fade-up mb-5 flex min-w-0 items-center gap-3.5">
      <span
        aria-hidden="true"
        className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-white to-emerald-50 shadow-sm ring-1 shadow-emerald-900/5 ring-emerald-100"
      >
        <Icon name="cart" className="size-7" />
      </span>
      <div className="min-w-0">
        <h1 className="text-2xl leading-tight font-bold tracking-tight text-slate-900 sm:text-[1.65rem]">Your cart</h1>
        <p className="mt-0.5 text-sm break-words text-slate-500">{description}</p>
      </div>
    </header>
  )
}

export function LoadingCart() {
  return (
    <div role="status" aria-label="Loading your cart" className={COLUMNS}>
      <div className={cn(CARD, 'min-w-0 p-4 sm:p-5')}>
        <div className="flex items-center gap-3">
          <Skeleton className="size-12 rounded-2xl" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-3 w-24" />
            <Skeleton className="h-4 w-1/2" />
          </div>
        </div>
        <div className="mt-4 divide-y divide-slate-100">
          {Array.from({ length: 3 }, (_, index) => (
            <div key={index} className="flex gap-3 py-4">
              <Skeleton className="size-16 rounded-xl" />
              <div className="flex-1 space-y-2">
                <Skeleton className="h-4 w-1/2" />
                <Skeleton className="h-3 w-1/3" />
                <Skeleton className="h-8 w-28 rounded-xl" />
              </div>
            </div>
          ))}
        </div>
      </div>
      <Skeleton className="h-96 w-full rounded-2xl" />
    </div>
  )
}

/** @param {{ to: string, icon: IconName, title: string, text: string, tone: 'green' | 'amber' }} props */
function StartTile({ to, icon, title, text, tone }) {
  return (
    <Link
      to={to}
      className="group flex items-center gap-3 rounded-2xl border border-slate-200/80 bg-white p-3.5 text-left shadow-xs transition hover:-translate-y-0.5 hover:border-emerald-300 hover:shadow-md focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 motion-reduce:transition-none"
    >
      <span
        aria-hidden="true"
        className={cn(
          'flex size-11 shrink-0 items-center justify-center rounded-xl ring-1 ring-inset',
          tone === 'green'
            ? 'bg-gradient-to-br from-emerald-50 to-emerald-100 ring-emerald-200/70'
            : 'bg-gradient-to-br from-amber-50 to-orange-100 ring-amber-200/70',
        )}
      >
        <Icon name={icon} className="size-6" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-semibold text-slate-900">{title}</span>
        <span className="block text-xs text-slate-500">{text}</span>
      </span>
      <Icon name="arrowRight" className="size-4 text-slate-400 transition-transform group-hover:translate-x-0.5" />
    </Link>
  )
}

export function EmptyCart() {
  return (
    <section
      aria-labelledby="empty-cart-title"
      className="animate-fade-up relative isolate overflow-hidden rounded-3xl border border-slate-200/80 bg-gradient-to-br from-white via-emerald-50/40 to-amber-50/50 px-5 py-10 text-center shadow-sm sm:px-8 sm:py-12"
    >
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
        <div className="absolute -top-20 -right-16 size-64 rounded-full bg-emerald-200/30 blur-3xl" />
        <div className="absolute -bottom-24 -left-10 size-56 rounded-full bg-amber-200/30 blur-3xl" />
      </div>
      <EmptyCartIllustration className="animate-float mx-auto w-40 sm:w-48" />
      <h2 id="empty-cart-title" className="mt-4 text-xl font-bold tracking-tight text-slate-900">
        Your cart is empty
      </h2>
      <p className="mx-auto mt-1.5 max-w-md text-sm leading-relaxed text-slate-500">
        Add groceries from one store, or dishes from one restaurant. Prices and stock are checked live when you come
        back.
      </p>
      <div className="mx-auto mt-6 grid max-w-2xl gap-3 sm:grid-cols-2">
        <StartTile
          to="/search"
          icon="bag"
          title="Browse products"
          text="Fresh produce, dairy and daily needs"
          tone="green"
        />
        <StartTile
          to="/restaurants"
          icon="cutlery"
          title="Find restaurants"
          text="Meals from restaurants near you"
          tone="amber"
        />
      </div>
    </section>
  )
}
