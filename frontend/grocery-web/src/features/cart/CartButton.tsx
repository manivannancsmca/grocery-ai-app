import { useState } from 'react'
import { Dialog } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { CartContents } from './CartContents'
import { CartDrawerSummary } from './CartDrawerSummary'
import { itemsLabel } from './format'
import { useCart } from './hooks'

/** Header cart button (customers) with the item count; opens the cart drawer. */
export function CartButton() {
  const cart = useCart()
  const [open, setOpen] = useState(false)
  const count = cart.data?.itemCount ?? 0
  const view = cart.isSuccess && cart.data.store && cart.data.lines.length > 0 ? cart.data : null

  return (
    <>
      <button
        type="button"
        aria-haspopup="dialog"
        aria-label={`Cart, ${itemsLabel(count)}`}
        onClick={() => setOpen(true)}
        // A white chip on the dark top bar (`bg-chrome`) keeps the colourful cart readable.
        className="relative rounded-xl bg-white/95 p-2 text-slate-700 shadow-sm transition-colors hover:bg-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white [&>svg]:transition-transform [&>svg]:duration-300 hover:[&>svg]:translate-x-0.5"
      >
        <Icon name="cart" className="size-6" />
        {count > 0 && (
          <span
            aria-hidden="true"
            className="absolute -top-0.5 -right-0.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-gradient-to-br from-emerald-500 to-teal-600 px-1 text-[11px] font-semibold text-white tabular-nums shadow-sm ring-2 ring-chrome"
          >
            {count > 99 ? '99+' : count}
          </span>
        )}
      </button>
      {/* Announces count changes ("3 items in your cart") after adds, removals and quantity changes. */}
      <span role="status" className="sr-only">
        {cart.isSuccess ? `${itemsLabel(count)} in your cart` : ''}
      </span>
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title="Your cart"
        variant="drawer"
        icon="cart"
        // Totals and "Checkout" are pinned under the scrolling lines (only for a cart with items that loaded fine).
        footer={view ? <CartDrawerSummary view={view} onNavigate={() => setOpen(false)} /> : undefined}
      >
        <CartContents onNavigate={() => setOpen(false)} />
      </Dialog>
    </>
  )
}
