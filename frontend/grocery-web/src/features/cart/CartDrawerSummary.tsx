import { Link } from 'react-router'
import { Icon } from '../../shared/icons'
import { formatPrice } from '../../shared/utils/format'
import type { CartView } from './api'
import { itemsLabel } from './format'

interface CartDrawerSummaryProps {
  view: CartView
  /** Called when "Checkout" navigates away (the drawer closes itself). */
  onNavigate?: () => void
}

/**
 * The cart drawer's pinned footer: subtotal, savings, the delivery-fee note and "Checkout". It sits in the drawer's
 * footer slot (below the scrolling lines), so it is always at the bottom and never covers the cart's content.
 */
export function CartDrawerSummary({ view, onNavigate }: CartDrawerSummaryProps) {
  return (
    <div className="w-full space-y-3 pt-1">
      <dl className="space-y-2 text-sm">
        <div className="flex items-baseline justify-between gap-4">
          <dt className="text-slate-600">Subtotal ({itemsLabel(view.itemCount)})</dt>
          <dd className="font-semibold text-slate-900 tabular-nums">{formatPrice(view.subtotal)}</dd>
        </div>
        {view.savings > 0 && (
          <div className="flex items-baseline justify-between gap-4 text-emerald-700">
            <dt>Your savings on MRP</dt>
            <dd className="font-semibold tabular-nums">{formatPrice(view.savings)}</dd>
          </div>
        )}
        <div className="flex items-baseline justify-between gap-4">
          <dt className="text-slate-600">Delivery fee</dt>
          <dd className="flex items-center gap-1.5 text-xs font-medium text-slate-500">
            <Icon name="truck" className="size-4" />
            At checkout
          </dd>
        </div>
      </dl>
      <Link
        to="/checkout"
        onClick={onNavigate}
        className="group/checkout flex h-14 w-full items-center justify-between gap-3 rounded-xl bg-emerald-600 px-4 text-white shadow-md shadow-emerald-600/25 transition hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
      >
        {/* Repeats the subtotal above for a glance; screen readers hear just "Checkout". */}
        <span aria-hidden="true" className="text-left leading-tight">
          <span className="block text-base font-bold tabular-nums">{formatPrice(view.subtotal)}</span>
          <span className="block text-[11px] font-medium text-emerald-100">{itemsLabel(view.itemCount)} · incl. taxes</span>
        </span>
        <span className="flex items-center gap-1.5 text-base font-semibold">
          Checkout
          <Icon name="arrowRight" className="size-5 transition-transform group-hover/checkout:translate-x-0.5" />
        </span>
      </Link>
    </div>
  )
}
