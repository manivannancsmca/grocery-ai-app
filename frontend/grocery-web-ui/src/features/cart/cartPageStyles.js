/** Tailwind class strings shared by the parts of the cart page. */

export const PAGE = '@container/cart w-full min-w-0'

export const CARD = 'rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.03]'

/** Two columns (items | sticky summary) once the page itself is wide enough, whatever the menu takes. */
export const COLUMNS =
  'grid gap-5 @4xl/cart:grid-cols-[minmax(0,1fr)_22.5rem] @4xl/cart:items-start @6xl/cart:grid-cols-[minmax(0,1fr)_24rem] @6xl/cart:gap-6'
