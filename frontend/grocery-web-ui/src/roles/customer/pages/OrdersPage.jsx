import { Link } from 'react-router'
import { ChipTabs } from '../../../features/orders/ChipTabs'
import { useMyOrders } from '../../../features/orders/hooks'
import { OrdersOverview, OrdersOverviewSkeleton } from '../../../features/orders/OrdersList'
import { ordersText } from '../../../features/orders/ordersListView'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import { Button, EmptyState, ErrorState, Pagination } from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useNow } from '../../../shared/hooks/useNow'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import {
  DeliveryScooterIllustration,
  EmptyBoxIllustration,
  GroceryBagIllustration,
  NoOrdersIllustration,
} from '../../../shared/illustrations'
import { useRealtimeLive } from '../../../shared/realtime/useRealtime'
import { ShopperPageHeader } from '../components/ShopperPageHeader'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../../features/orders/api').OrderStatusFilter} OrderStatusFilter */
/** @typedef {import('../../../features/orders/ChipTabs').ChipTab} ChipTab */

/**
 * @typedef {(
 *   | 'ALL'
 *   | Extract<OrderStatusFilter, 'OPEN' | 'CONFIRMED' | 'OUT_FOR_DELIVERY' | 'DELIVERED' | 'CANCELLED' | 'REJECTED'>
 * )} OrdersFilter
 */

/** @type {readonly ChipTab<OrdersFilter>[]} */
const FILTERS = [
  { id: 'ALL', label: 'All', icon: 'list' },
  { id: 'OPEN', label: 'Open', icon: 'clock' },
  { id: 'CONFIRMED', label: 'Confirmed', icon: 'checkCircle' },
  // Phase 9
  { id: 'OUT_FOR_DELIVERY', label: 'On the way', icon: 'scooter' },
  { id: 'DELIVERED', label: 'Delivered', icon: 'homeCheck' },
  { id: 'CANCELLED', label: 'Cancelled', icon: 'xCircle' },
  { id: 'REJECTED', label: 'Rejected', icon: 'ban' },
]

/** @type {Record<OrdersFilter, { title: string, description: string }>} */
const EMPTY_TEXT = {
  ALL: {
    title: 'You have not placed any orders yet',
    description:
      'Groceries and restaurant meals you order appear here — fresh produce and hot food are a few taps away.',
  },
  OPEN: { title: 'No open orders', description: 'Orders waiting for stock checks or payment show up here.' },
  CONFIRMED: {
    title: 'No confirmed orders',
    description: 'Paid orders waiting for the store to accept them show up here.',
  },
  OUT_FOR_DELIVERY: {
    title: 'No orders on the way',
    description: 'When a delivery partner picks up your order, you can track it live from here.',
  },
  DELIVERED: {
    title: 'No delivered orders yet',
    description: 'Delivered orders stay here, so you can rate them or report a problem.',
  },
  CANCELLED: { title: 'No cancelled orders', description: 'Nice — none of your orders were cancelled.' },
  REJECTED: { title: 'No rejected orders', description: 'Orders a store could not accept would show up here.' },
}

/** @type {Partial<Record<OrdersFilter, string>>} */
const COUNT_ADJECTIVES = {
  OPEN: 'open',
  CONFIRMED: 'confirmed',
  DELIVERED: 'delivered',
  CANCELLED: 'cancelled',
  REJECTED: 'rejected',
}

/**
 * The header's count: "17 orders", "3 cancelled orders", "1 order on the way".
 * @param {OrdersFilter} filter
 * @param {number} count
 * @returns {string}
 */
function countText(filter, count) {
  const number = count.toLocaleString('en-IN')
  const noun = count === 1 ? 'order' : 'orders'
  if (filter === 'OUT_FOR_DELIVERY') return `${number} ${noun} on the way`
  const adjective = COUNT_ADJECTIVES[filter]
  return adjective ? `${number} ${adjective} ${noun}` : `${number} ${noun}`
}

const primaryLink =
  'inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-600/25 transition-colors hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'
const secondaryLink =
  'inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-white px-4 text-sm font-semibold text-slate-700 shadow-xs ring-1 ring-slate-300 ring-inset transition-colors hover:bg-slate-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/**
 * @param {string | null} value
 * @returns {value is OrdersFilter}
 */
function isOrdersFilter(value) {
  return FILTERS.some((filter) => filter.id === value)
}

/**
 * @param {OrdersFilter} filter
 * @returns {ReactNode}
 */
function emptyIllustration(filter) {
  if (filter === 'ALL') return <NoOrdersIllustration className="animate-float" />
  if (filter === 'OUT_FOR_DELIVERY') return <DeliveryScooterIllustration className="animate-float" />
  if (filter === 'OPEN' || filter === 'CONFIRMED') return <GroceryBagIllustration className="animate-float" />
  return <EmptyBoxIllustration className="animate-float" />
}

/**
 * `/orders` — the customer's orders, newest first (`?status=` and `?page=` in the URL): the ones still under way in a
 * highlighted strip (where each is, pay / track), then the finished ones grouped by day as compact rows.
 */
export function OrdersPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const statusParam = searchParams.get('status')
  const filter = isOrdersFilter(statusParam) ? statusParam : 'ALL'
  const page = pageFromParam(searchParams.get('page'))
  const orders = useMyOrders(
    { status: filter === 'ALL' ? undefined : filter, page, size: DEFAULT_PAGE_SIZE },
    { followActive: true },
  )
  // Minutes left to pay and "placed 4 min ago" stay current.
  const now = useNow(30_000)
  const live = useRealtimeLive()
  // The selected tab shows how many orders it has (not while the previous tab's answer is still on screen).
  const total = orders.data && !orders.isPlaceholderData ? orders.data.totalElements : undefined
  const tabs = FILTERS.map((tab) => (tab.id === filter && total !== undefined ? { ...tab, count: total } : tab))

  const showAll = () => updateParams({ status: null, page: null }, { replace: true })

  let content
  if (orders.isPending) {
    content = <OrdersOverviewSkeleton />
  } else if (orders.isError) {
    content = (
      <ErrorState
        title="Could not load your orders"
        message={orders.error.message}
        correlationId={orders.error instanceof ApiError ? orders.error.correlationId : null}
        onRetry={() => void orders.refetch()}
      />
    )
  } else if (orders.data.content.length === 0 && orders.data.totalElements > 0) {
    // A page past the end (an old link): the orders are there, just not on this page.
    content = (
      <EmptyState
        title="Nothing on this page"
        description={`There ${orders.data.totalElements === 1 ? 'is' : 'are'} ${ordersText(orders.data.totalElements)} here — go back to the first page to see them.`}
        action={
          <Button variant="secondary" onClick={() => updateParams({ page: null })}>
            <Icon name="arrowLeft" className="size-4" />
            First page
          </Button>
        }
      />
    )
  } else if (orders.data.content.length === 0) {
    const text = EMPTY_TEXT[filter]
    content = (
      <EmptyState
        size={filter === 'ALL' ? 'lg' : 'md'}
        illustration={emptyIllustration(filter)}
        title={text.title}
        description={text.description}
        action={
          filter === 'ALL' ? (
            <>
              <Link to="/" className={primaryLink}>
                <Icon name="bag" className="size-4" />
                Start shopping
              </Link>
              <Link to="/restaurants" className={secondaryLink}>
                <Icon name="cutlery" className="size-4" />
                Order food
              </Link>
            </>
          ) : (
            <>
              <Button variant="secondary" onClick={showAll}>
                <Icon name="list" className="size-4" />
                Show all orders
              </Button>
              <Link to="/" className={secondaryLink}>
                <Icon name="bag" className="size-4" />
                Start shopping
              </Link>
            </>
          )
        }
      />
    )
  } else {
    content = (
      <>
        <OrdersOverview orders={orders.data.content} now={now} live={live} dimmed={orders.isPlaceholderData} />
        <Pagination
          page={orders.data.page}
          size={orders.data.size}
          totalElements={orders.data.totalElements}
          totalPages={orders.data.totalPages}
          disabled={orders.isFetching}
          onPageChange={(next) => updateParams({ page: pageToParam(next) })}
        />
      </>
    )
  }

  return (
    <div className="w-full min-w-0">
      <ShopperPageHeader
        icon="box"
        eyebrow="Orders"
        title="My orders"
        description={
          total !== undefined && total > 0
            ? `${countText(filter, total)} · newest first`
            : 'Track, pay for or cancel your orders.'
        }
        actions={
          <ExportButton
            path="/orders/export"
            label="your orders"
            params={{ status: filter === 'ALL' ? undefined : filter }}
            total={orders.data?.totalElements}
          />
        }
      />
      <ChipTabs
        label="Filter orders"
        tabs={tabs}
        value={filter}
        onChange={(next) => updateParams({ status: next === 'ALL' ? null : next, page: null }, { replace: true })}
      >
        {content}
      </ChipTabs>
    </div>
  )
}
