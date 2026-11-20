import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useToast } from '../../shared/components/toast/useToast'
import { Button, Spinner } from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { Icon } from '../../shared/icons'
import { useRealtime } from '../../shared/realtime/useRealtime'
import { cn } from '../../shared/utils/cn'
import { BELL_NOTIFICATION_COUNT } from './api'
import { useMarkAllNotificationsRead, useNotifications, useUnreadNotificationCount } from './hooks'
import { NotificationItem } from './NotificationItem'
import { CONNECTION_DOT_CLASSES, connectionLabel } from './style'
import { useOpenNotification } from './useOpenNotification'

const STATUS_NOTE = {
  connecting: 'Connecting to live updates…',
  reconnecting: 'Reconnecting to live updates…',
  offline: 'Live updates are offline. This list may be out of date.',
}

/**
 * Header bell for every signed-in role: unread badge, a dot showing the live-updates connection, and a
 * dropdown with the latest ten notifications ("Mark all as read", "View all").
 */
export function NotificationBell() {
  const { status, replaced } = useRealtime()
  const unread = useUnreadNotificationCount()
  const count = unread.data ?? 0
  const [open, setOpen] = useState(false)
  const latest = useNotifications({ size: BELL_NOTIFICATION_COUNT }, { enabled: open })
  const markAll = useMarkAllNotificationsRead()
  const toast = useToast()
  const now = useNow(60_000, open)
  const openNotification = useOpenNotification(() => setOpen(false))
  const containerRef = useRef(null)
  const buttonRef = useRef(null)
  const panelId = useId()
  const headingId = useId()

  useEffect(() => {
    if (!open) return
    function onPointerDown(event) {
      if (!containerRef.current?.contains(event.target)) setOpen(false)
    }
    function onKeyDown(event) {
      if (event.key === 'Escape') {
        setOpen(false)
        buttonRef.current?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  function markAllRead() {
    markAll.mutate(undefined, {
      onError: (error) => toast.error('Could not mark the notifications as read.', { description: error.message }),
    })
  }

  const label = connectionLabel(status, replaced)
  const items = latest.data?.content ?? []
  const note = replaced ? 'Live updates are open in another tab.' : status === 'live' ? null : STATUS_NOTE[status]

  return (
    <div ref={containerRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        aria-label={count > 0 ? `Notifications, ${count} unread` : 'Notifications'}
        aria-haspopup="true"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
        // A white chip on the dark top bar (`bg-chrome`) keeps the colourful bell readable.
        className="relative rounded-xl bg-white/95 p-2 text-slate-700 shadow-sm transition-colors hover:bg-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white [&>svg]:transition-transform [&>svg]:duration-300 hover:[&>svg]:-rotate-12"
      >
        <Icon name="bell" className="size-6" />
        {count > 0 && (
          <span
            aria-hidden="true"
            className="absolute -top-0.5 -right-0.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-gradient-to-br from-red-500 to-rose-600 px-1 text-[11px] font-semibold text-white tabular-nums shadow-sm ring-2 ring-chrome"
          >
            {count > 99 ? '99+' : count}
          </span>
        )}
      </button>
      <span
        role="img"
        aria-label={label}
        title={label}
        className={cn(
          'pointer-events-none absolute right-1.5 bottom-1.5 size-2.5 rounded-full ring-2 ring-white',
          CONNECTION_DOT_CLASSES[replaced ? 'offline' : status],
        )}
      />

      {open && (
        <section
          id={panelId}
          aria-labelledby={headingId}
          className="animate-pop-in fixed inset-x-2 top-16 z-40 overflow-hidden rounded-2xl border border-slate-200/70 bg-white/95 shadow-xl shadow-slate-900/10 backdrop-blur sm:absolute sm:inset-x-auto sm:top-auto sm:right-0 sm:mt-2 sm:w-96"
        >
          <header className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-3">
            <h2 id={headingId} className="text-sm font-semibold text-slate-900">
              Notifications
            </h2>
            <Button variant="ghost" size="sm" onClick={markAllRead} disabled={count === 0 || markAll.isPending}>
              Mark all as read
            </Button>
          </header>
          {note && <p className="border-b border-slate-100 bg-amber-50 px-4 py-2 text-xs text-amber-800">{note}</p>}

          <div className="max-h-[min(28rem,calc(100vh-12rem))] overflow-y-auto">
            {latest.isPending ? (
              <div className="flex justify-center py-8">
                <Spinner label="Loading notifications" />
              </div>
            ) : latest.isError ? (
              <div role="alert" className="space-y-2 px-4 py-6 text-center text-sm text-red-700">
                <p>Could not load notifications: {latest.error.message}</p>
                <Button variant="secondary" size="sm" onClick={() => void latest.refetch()}>
                  Try again
                </Button>
              </div>
            ) : items.length === 0 ? (
              <p className="px-4 py-8 text-center text-sm text-slate-500">
                You’re all caught up. New updates will appear here.
              </p>
            ) : (
              <ul aria-label="Latest notifications" className="divide-y divide-slate-100">
                {items.map((notification) => (
                  <NotificationItem
                    key={notification.id}
                    notification={notification}
                    onOpen={openNotification}
                    now={now}
                    compact
                  />
                ))}
              </ul>
            )}
          </div>

          <footer className="border-t border-slate-100 px-4 py-2.5 text-center">
            <Link
              to="/notifications"
              onClick={() => setOpen(false)}
              className="text-sm font-semibold text-emerald-700 hover:text-emerald-800 hover:underline"
            >
              View all
            </Link>
          </footer>
        </section>
      )}
    </div>
  )
}
