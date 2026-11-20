import { useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { Alert, ErrorState, Skeleton } from '../../shared/components/ui'
import { useMediaQuery } from '../../shared/hooks/useMediaQuery'
import { useNow } from '../../shared/hooks/useNow'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCountdown } from '../../shared/utils/format'
import { indiaDay } from '../../shared/utils/indiaDate'
import { STORE_KIND_CONFIG, type StoreDelivery, type StoreKind, type StoreOrderStatus, type StoreOrderView } from './api'
import { BoardHeader } from './BoardHeader'
import { columnOrder, lateCount, nextExpiryMs, pickedUpToday, URGENT_MS } from './board'
import { ClosedToday } from './ClosedToday'
import { useActiveStoreOrders, useClosedTodayStoreOrders, useSavingStoreOrderIds, useStoreDeliveries } from './hooks'
import { BOARD_COLUMNS, type BoardColumnStyle, boardColumnEmptyText, boardColumnStyle, boardColumnTitle } from './labels'
import { RejectStoreOrderDialog } from './RejectStoreOrderDialog'
import { StoreOrderCard } from './StoreOrderCard'

interface Column {
  status: StoreOrderStatus
  title: string
  style: BoardColumnStyle
  cards: StoreOrderView[]
  /** Time until the column's first order is rejected automatically (New only). */
  expiresIn: number | null
  /** Orders past their promised time (Accepted, Preparing). */
  late: number
}

/** Needs attention now: a new order about to expire, or an order running late. */
const alarming = (column: Column) => (column.expiresIn !== null && column.expiresIn < URGENT_MS) || column.late > 0

/** The one fact a column header adds to its count: when the next new order expires, or how many are late. */
function ColumnFact({ column }: { column: Column }) {
  if (column.expiresIn !== null) {
    const countdown = formatCountdown(column.expiresIn)
    return (
      <span
        title={`The next order is rejected automatically in ${countdown}`}
        className={cn(
          'ml-auto inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold whitespace-nowrap tabular-nums',
          column.expiresIn < URGENT_MS ? 'bg-red-100 text-red-700' : 'bg-amber-100 text-amber-900',
        )}
      >
        <Icon name="hourglass" className="size-3.5" />
        <span className="sr-only @min-[16.5rem]:not-sr-only">Next expires in</span> {countdown}
      </span>
    )
  }
  if (column.late > 0) {
    return (
      <span className="ml-auto inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-xs font-semibold whitespace-nowrap text-red-700">
        <Icon name="clock" className="size-3.5" />
        {column.late} late
      </span>
    )
  }
  return null
}

interface BoardColumnProps {
  kind: StoreKind
  column: Column
  /** Phones: the switch above shows the counts already. */
  narrow: boolean
  now: number
  onReject: (order: StoreOrderView) => void
  deliveryOf: ReadonlyMap<string, StoreDelivery>
  saving: ReadonlySet<string>
}

function BoardColumn({ kind, column, narrow, now, onReject, deliveryOf, saving }: BoardColumnProps) {
  const { status, title, style, cards } = column
  return (
    <section
      aria-label={title}
      className={cn(
        '@container flex min-h-52 flex-col rounded-xl ring-1 ring-inset',
        status === 'NEW' && cards.length > 0 ? 'bg-amber-50/70 ring-amber-200/80' : 'bg-slate-100/60 ring-slate-200/70',
      )}
    >
      <h2 className="flex min-h-11 items-center gap-2 px-3 pt-2.5 pb-1.5 text-sm font-semibold text-slate-800">
        <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', style.dot)} />
        <span className="whitespace-nowrap">
          {title}
          <span className="sr-only">:</span>
        </span>{' '}
        {!narrow && (
          <span
            className={cn(
              'min-w-6 rounded-full px-1.5 py-0.5 text-center text-xs font-bold tabular-nums',
              cards.length ? style.count : 'bg-white text-slate-500 ring-1 ring-slate-200 ring-inset',
            )}
          >
            {cards.length}
          </span>
        )}{' '}
        <ColumnFact column={column} />
      </h2>
      <div className="flex-1 px-2 pb-2">
        {cards.length === 0 ? (
          <div className="flex h-full min-h-36 flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-slate-300/80 px-3 text-center">
            <span aria-hidden="true" className={cn('flex size-10 items-center justify-center rounded-full ring-1', style.tile)}>
              <Icon name={style.icon} className="size-5 opacity-80" />
            </span>
            <p className="text-sm text-slate-500">{boardColumnEmptyText(kind, status)}</p>
          </div>
        ) : (
          <ul className="space-y-2">
            {cards.map((order) => (
              <li key={order.orderId}>
                <StoreOrderCard
                  kind={kind}
                  order={order}
                  now={now}
                  onReject={onReject}
                  delivery={deliveryOf.get(order.orderId)}
                  saving={saving.has(order.orderId)}
                />
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}

/** Phones: one column at a time, picked here; it stays under the top bar while the orders scroll. */
function ColumnSwitcher({ columns, shown, onPick }: { columns: readonly Column[]; shown: StoreOrderStatus; onPick: (status: StoreOrderStatus) => void }) {
  return (
    <div className="sticky top-16 z-20 -mx-4 bg-canvas/85 px-4 py-2 backdrop-blur-md sm:-mx-6 sm:px-6">
      <div role="group" aria-label="Board column" className="grid grid-cols-4 gap-1 rounded-xl bg-white p-1 shadow-sm ring-1 ring-slate-200/80">
        {columns.map((column) => {
          const selected = column.status === shown
          return (
            <button
              key={column.status}
              type="button"
              aria-pressed={selected}
              onClick={() => onPick(column.status)}
              className={cn(
                'relative flex min-w-0 flex-col-reverse items-center gap-0.5 rounded-lg px-1 py-1.5 text-[11px] font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-emerald-600',
                selected ? 'bg-slate-900 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-50',
              )}
            >
              <span className="flex max-w-full items-center gap-1">
                <span aria-hidden="true" className={cn('size-1.5 shrink-0 rounded-full', column.style.dot)} />
                <span className="truncate">
                  {column.title}
                  <span className="sr-only">:</span>
                </span>
              </span>{' '}
              <span className={cn('text-base leading-none font-bold tabular-nums', !selected && column.cards.length === 0 && 'text-slate-400')}>
                {column.cards.length}
              </span>
              {alarming(column) && (
                <span aria-hidden="true" className="absolute top-1 right-1.5 size-2 rounded-full bg-red-500 ring-2 ring-white" />
              )}
            </button>
          )
        })}
      </div>
    </div>
  )
}

/**
 * The live order board of a vendor (`/vendor/orders`) or restaurant (`/restaurant/orders`): a compact header with the
 * store's name and tools, columns New · Accepted · Preparing (Packing for groceries) · Ready (on phones one at a time,
 * picked with a sticky switch), and today's picked-up, rejected and cancelled orders. It follows the live stream
 * (STORE_ORDERS_CHANGED, NEW_ORDER), polls every 30 s only while the stream is down, and moves a card the moment its
 * button is pressed.
 */
export function StoreOrderBoard({ kind }: { kind: StoreKind }) {
  const config = STORE_KIND_CONFIG[kind]
  const active = useActiveStoreOrders(kind)
  const orders = active.data?.content ?? []
  const hasActive = orders.length > 0
  // Accepted orders wait for a partner: show who collects each one (polled; the partner's position is not pushed).
  const deliveries = useStoreDeliveries(kind, orders.some((order) => order.status !== 'NEW'))
  const deliveryOf = new Map((deliveries.data ?? []).map((delivery) => [delivery.orderId, delivery]))
  const saving = useSavingStoreOrderIds(kind)
  const now = useNow(1_000, hasActive)
  const minute = useNow(60_000)
  const clock = Math.max(now, minute)
  const today = indiaDay(clock)
  const closed = useClosedTodayStoreOrders(kind, today)
  const [rejecting, setRejecting] = useState<StoreOrderView | null>(null)
  // Phones and small tablets: one column at a time, picked with the switch above it.
  const narrow = useMediaQuery('(max-width: 767px)')
  const [picked, setPicked] = useState<StoreOrderStatus | null>(null)
  const todayStats = closed.data ? pickedUpToday(closed.data) : undefined

  if (active.isPending) {
    return (
      <div className="space-y-4">
        <BoardHeader kind={kind} today={todayStats} />
        <div role="status" aria-label="Loading orders" className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          {BOARD_COLUMNS.map((status) => (
            <Skeleton key={status} className="h-64 rounded-xl" />
          ))}
        </div>
      </div>
    )
  }

  if (active.isLoadingError) {
    const forbidden = active.error instanceof ApiError && active.error.status === 403
    return (
      <div className="space-y-4">
        <BoardHeader kind={kind} />
        {forbidden ? (
          <Alert
            tone="info"
            title={`Orders appear here once your ${config.storeNoun} is approved`}
            actions={
              <Link to={kind === 'VENDOR' ? '/vendor' : '/restaurant'} className="text-sm font-semibold text-sky-900 underline">
                Check your onboarding status
              </Link>
            }
          >
            {active.error.message}
          </Alert>
        ) : (
          <ErrorState
            title="Could not load your orders"
            message={active.error.message}
            correlationId={active.error instanceof ApiError ? active.error.correlationId : null}
            onRetry={() => void active.refetch()}
          />
        )}
      </div>
    )
  }

  const columns: Column[] = BOARD_COLUMNS.map((status) => {
    const cards = orders.filter((order) => order.status === status).sort(columnOrder(status))
    return {
      status,
      title: boardColumnTitle(kind, status),
      style: boardColumnStyle(kind, status),
      cards,
      expiresIn: status === 'NEW' ? nextExpiryMs(cards, now) : null,
      late: lateCount(cards, now),
    }
  })
  // On phones: the column picked, else the first one with orders (new ones first), else New.
  const shownStatus = picked ?? columns.find((column) => column.cards.length > 0)?.status ?? 'NEW'
  const visible = narrow ? columns.filter((column) => column.status === shownStatus) : columns

  return (
    <div className="space-y-4">
      <BoardHeader
        kind={kind}
        today={todayStats}
        live={{
          updatedAt: active.dataUpdatedAt,
          now: clock,
          refreshing: active.isFetching,
          onRefresh: () => {
            void active.refetch()
            void closed.refetch()
          },
        }}
      />
      {active.isRefetchError && (
        <Alert tone="warning" role="status">
          We could not refresh the board just now ({active.error.message}). Showing the last known orders — we keep trying.
        </Alert>
      )}

      {narrow && <ColumnSwitcher columns={columns} shown={shownStatus} onPick={setPicked} />}

      <div className={cn('grid gap-3', !narrow && 'md:grid-cols-2 xl:grid-cols-4')}>
        {visible.map((column) => (
          <BoardColumn
            key={column.status}
            kind={kind}
            column={column}
            narrow={narrow}
            now={now}
            onReject={setRejecting}
            deliveryOf={deliveryOf}
            saving={saving}
          />
        ))}
      </div>

      <ClosedToday kind={kind} closed={closed} />

      <RejectStoreOrderDialog kind={kind} order={rejecting} onClose={() => setRejecting(null)} />
    </div>
  )
}
