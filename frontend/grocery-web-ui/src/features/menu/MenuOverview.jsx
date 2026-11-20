import { StatCard } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { AcceptingOrdersSwitch } from './AcceptingOrdersSwitch'
import { MENU_LIMITS } from './api'
import { countSoldOut } from './menuView'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('./api').MenuView} MenuView */

/**
 * @typedef {object} MenuOverviewProps
 * @property {MenuView} menu
 * @property {boolean} readOnly Suspended restaurants cannot pause or resume orders.
 */

/**
 * "Up to 500" under a count, or an amber note once the limit is reached.
 * @param {number} count
 * @param {number} limit
 * @returns {ReactNode}
 */
function limitHint(count, limit) {
  if (count < limit) return `Up to ${limit}`
  return <span className="font-medium text-amber-700">Limit of {limit} reached</span>
}

/**
 * Open / paused for new orders, with the switch to change it. Like a `StatCard`, so it sits in the same `<dl>`.
 * @param {{ acceptingOrders: boolean, disabled: boolean }} props
 */
function OrdersTile({ acceptingOrders, disabled }) {
  return (
    <div
      className={cn(
        'relative isolate flex flex-col overflow-hidden rounded-2xl border px-4 py-3.5 shadow-sm shadow-slate-900/[0.04] transition-all duration-300 hover:-translate-y-0.5 hover:shadow-lg hover:shadow-slate-900/[0.07]',
        acceptingOrders
          ? 'border-emerald-200 bg-gradient-to-br from-emerald-50 via-white to-white'
          : 'border-amber-200 bg-gradient-to-br from-amber-50 via-white to-white',
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'absolute inset-x-0 top-0 h-1 bg-gradient-to-r',
          acceptingOrders ? 'from-emerald-400 to-teal-500' : 'from-amber-400 to-orange-500',
        )}
      />
      {/* The term comes first in the markup but is shown under the value. */}
      <dt className="order-2 text-xs font-medium text-slate-500">New orders</dt>
      <dd className="order-1 flex flex-wrap items-center justify-between gap-2">
        <span
          className={cn(
            'flex items-center gap-2 text-xl font-bold tracking-tight sm:text-2xl',
            acceptingOrders ? 'text-emerald-700' : 'text-amber-700',
          )}
        >
          <span aria-hidden="true" className="relative flex size-2.5">
            {acceptingOrders && (
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-60 motion-reduce:hidden" />
            )}
            <span
              className={cn(
                'relative inline-flex size-2.5 rounded-full',
                acceptingOrders ? 'bg-emerald-500' : 'bg-amber-500',
              )}
            />
          </span>
          {acceptingOrders ? 'Open' : 'Paused'}
        </span>
        <AcceptingOrdersSwitch compact acceptingOrders={acceptingOrders} disabled={disabled} />
      </dd>
      <dd className="order-3 mt-1 text-xs text-slate-400">
        {acceptingOrders ? 'Customers can order' : 'Menu stays visible'}
      </dd>
    </div>
  )
}

/**
 * The menu at a glance: dishes, categories, how many can be ordered, and whether new orders are open.
 * @param {MenuOverviewProps} props
 */
export function MenuOverview({ menu, readOnly }) {
  const dishes = menu.categories.flatMap((category) => category.items)
  const soldOut = countSoldOut(dishes)

  return (
    <div className="@container">
      <dl aria-label="Menu overview" className="grid grid-cols-2 gap-3 @2xl:grid-cols-4">
        <StatCard
          icon="cloche"
          accent="emerald"
          label="Dishes"
          value={menu.itemCount}
          hint={limitHint(menu.itemCount, MENU_LIMITS.items)}
        />
        <StatCard
          icon="menuBook"
          accent="sky"
          label="Categories"
          value={menu.categories.length}
          hint={limitHint(menu.categories.length, MENU_LIMITS.categories)}
        />
        <StatCard
          icon="checkCircle"
          accent="teal"
          label="Available now"
          value={dishes.length - soldOut}
          hint={
            soldOut > 0 ? (
              <span className="inline-flex items-center gap-1 font-medium text-amber-700">
                <Icon name="warning" className="size-3" />
                {soldOut} sold out
              </span>
            ) : (
              <span className="text-emerald-700">None sold out</span>
            )
          }
        />
        <OrdersTile acceptingOrders={menu.acceptingOrders} disabled={readOnly} />
      </dl>
    </div>
  )
}
