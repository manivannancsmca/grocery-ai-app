import { Link } from 'react-router'
import { ExportButton } from '../../shared/export'
import { Icon } from '../../shared/icons'
import { useRealtime } from '../../shared/realtime/useRealtime'
import { cn } from '../../shared/utils/cn'
import { formatPrice, formatRelativeTime } from '../../shared/utils/format'
import { useStoreIdentity } from './hooks'
import { SoundAlertControls } from './SoundAlertControls'

/** @typedef {import('./api').StoreKind} StoreKind */

/**
 * @typedef {object} LiveStatusProps
 * @property {number} updatedAt When the board was last read (0 before the first read).
 * @property {number} now
 * @property {boolean} refreshing
 * @property {() => void} onRefresh
 */

/**
 * "● Live · updated just now ⟳": whether the board follows the live stream, when it last changed, and a refresh.
 * @param {LiveStatusProps} props
 */
function LiveStatus({ updatedAt, now, refreshing, onRefresh }) {
  const { status, replaced } = useRealtime()
  const live = status === 'live' && !replaced
  const updated = updatedAt ? formatRelativeTime(new Date(updatedAt).toISOString(), now).toLowerCase() : null
  return (
    <div className="inline-flex h-10 items-center rounded-xl bg-white pl-3 shadow-xs ring-1 ring-slate-200 ring-inset">
      <span
        role="status"
        title={
          live
            ? 'New orders appear here instantly'
            : 'Live updates are not connected — the board refreshes every 30 seconds'
        }
        className={cn(
          'inline-flex items-center gap-1.5 text-xs font-semibold whitespace-nowrap',
          live ? 'text-emerald-700' : 'text-amber-800',
        )}
      >
        <span aria-hidden="true" className="relative flex size-2">
          {live && (
            <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-75 motion-reduce:hidden" />
          )}
          <span
            className={cn(
              'relative inline-flex size-2 rounded-full',
              live ? 'bg-emerald-500' : 'animate-pulse bg-amber-400',
            )}
          />
        </span>
        {live ? 'Live' : 'Reconnecting'}
        <span className="sr-only">
          {live
            ? ' — new orders appear here instantly.'
            : ' — live updates are not connected; the board refreshes every 30 seconds.'}
        </span>
      </span>
      {updated && (
        <span className="ml-1.5 hidden text-xs whitespace-nowrap text-slate-500 sm:inline">· updated {updated}</span>
      )}
      <button
        type="button"
        onClick={onRefresh}
        disabled={refreshing}
        aria-label="Refresh the board"
        title={updated ? `Refresh now (updated ${updated})` : 'Refresh now'}
        className="ml-1 inline-flex size-10 items-center justify-center rounded-r-xl text-slate-500 transition-colors hover:bg-emerald-50 hover:text-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 disabled:cursor-wait"
      >
        <Icon name="refresh" className={cn('size-4', refreshing && 'animate-spin motion-reduce:animate-none')} />
      </button>
    </div>
  )
}

/**
 * @typedef {object} BoardHeaderProps
 * @property {StoreKind} kind
 * @property {LiveStatusProps} [live] The live state and refresh; absent while the board cannot be shown.
 * @property {{ count: number, sales: number, currency: string }} [today] Today's picked-up orders, once known.
 */

/**
 * The board's header: "Orders" and the store's name (owners with two accounts mixed them up), today's pickups, a
 * one-line reminder of the deadline, and the tools — live state and refresh, sound alerts, export (icons on phones).
 * @param {BoardHeaderProps} props
 */
export function BoardHeader({ kind, live, today }) {
  const store = useStoreIdentity(kind)
  return (
    // Phones: title, today, tools. From `xl`: the title and the tools on one row, today and the reminder under them.
    <header className="grid gap-x-6 gap-y-2.5 xl:grid-cols-[minmax(0,1fr)_auto] xl:items-center">
      <div className="flex min-w-0 items-center gap-3 xl:col-start-1 xl:row-start-1">
        <span
          aria-hidden="true"
          className="hidden size-9 shrink-0 items-center justify-center rounded-lg bg-white shadow-sm ring-1 ring-slate-200/70 sm:flex"
        >
          <Icon name="receipt" className="size-5" />
        </span>
        <h1 className="flex min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1 text-2xl leading-tight font-bold tracking-tight text-slate-900">
          <span>Orders</span>
          {store.name && (
            <>
              {/* Read as "Orders · <store>"; the spaces are text of their own so the accessible name keeps them. */}{' '}
              <span className="sr-only">·</span>{' '}
              <span className="inline-flex max-w-full min-w-0 items-center gap-1.5 rounded-lg bg-white px-2 py-1 text-sm font-semibold tracking-normal text-slate-700 shadow-xs ring-1 ring-slate-200/80">
                <Icon name={kind === 'VENDOR' ? 'store' : 'cloche'} className="size-4" />
                <span className="truncate">{store.name}</span>
              </span>
            </>
          )}
        </h1>
      </div>
      <p className="-mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-500 sm:pl-12 xl:col-span-2 xl:row-start-2 xl:mt-0">
        {store.acceptingOrders === false && (
          <Link
            to="/restaurant"
            className="inline-flex items-center gap-1 rounded-md bg-amber-50 px-1.5 py-0.5 font-semibold text-amber-900 ring-1 ring-amber-200 ring-inset hover:bg-amber-100"
          >
            <Icon name="pauseCircle" className="size-3.5" />
            Not accepting new orders
          </Link>
        )}
        {today && (
          <span className="inline-flex items-center gap-1 whitespace-nowrap text-slate-600">
            <Icon name="checkCircle" className="size-3.5" />
            {today.count === 0 ? (
              'Nothing picked up yet today'
            ) : (
              <>
                Today: <span className="font-semibold text-slate-800 tabular-nums">{today.count}</span> picked up ·{' '}
                <span className="font-semibold text-slate-800 tabular-nums">
                  {formatPrice(today.sales, today.currency)}
                </span>
              </>
            )}
          </span>
        )}
        <span className="hidden md:inline">
          Accept before the timer runs out — unanswered orders are rejected and refunded automatically.
        </span>
      </p>
      <div
        role="group"
        aria-label="Board tools"
        className="flex flex-wrap items-center gap-2 xl:col-start-2 xl:row-start-1 xl:justify-end"
      >
        {live && <LiveStatus {...live} />}
        <SoundAlertControls />
        <ExportButton
          path={kind === 'VENDOR' ? '/vendor/orders/export' : '/restaurant/orders/export'}
          label="orders"
          scopeNote="Every order your store has received, in every status, not only the open ones on the board."
          // Phones: the download icon alone (the button's name stays "Export orders").
          className="max-sm:w-10 max-sm:gap-0 max-sm:px-0 max-sm:text-[0px]"
        />
      </div>
    </header>
  )
}
