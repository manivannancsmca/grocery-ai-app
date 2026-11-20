import { useId } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  PageHeader,
  Pagination,
  Skeleton,
  Tabs,
} from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../shared/icons'
import { useRealtime, useSuppressNotificationToasts } from '../../shared/realtime/useRealtime'
import { NotificationsIllustration } from '../../shared/illustrations'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatRelativeTime } from '../../shared/utils/format'
import { NOTIFICATION_PAGE_SIZE } from './api'
import {
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
  useUnreadNotificationCount,
} from './hooks'
import { NotificationIcon } from './NotificationIcon'
import { notificationLabel } from './style'
import { useOpenNotification } from './useOpenNotification'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../../shared/realtime/messages').RealtimeStatus} RealtimeStatus */
/** @typedef {import('./api').NotificationView} NotificationView */

/** @typedef {'all' | 'unread'} InboxFilter */

/** @type {Record<RealtimeStatus, { tone: BadgeTone, label: string }>} */
const STATUS_BADGE = {
  live: { tone: 'green', label: 'Live' },
  connecting: { tone: 'amber', label: 'Connecting…' },
  reconnecting: { tone: 'amber', label: 'Reconnecting…' },
  offline: { tone: 'gray', label: 'Offline' },
}

function LiveBadge() {
  const { status, replaced } = useRealtime()
  const badge = replaced ? { tone: 'gray', label: 'Open in another tab' } : STATUS_BADGE[status]
  return (
    <Badge tone={badge.tone} dot className="self-center">
      <span className="sr-only">Live updates: </span>
      {badge.label}
    </Badge>
  )
}

/** @param {{ notification: NotificationView }} props */
function MarkReadButton({ notification }) {
  const markRead = useMarkNotificationRead()
  const toast = useToast()
  return (
    <Button
      variant="ghost"
      size="sm"
      aria-label={`Mark as read: ${notification.title}`}
      disabled={markRead.isPending}
      onClick={() =>
        markRead.mutate(notification, {
          onError: (error) => toast.error('Could not mark the notification as read.', { description: error.message }),
        })
      }
    >
      <Icon name="check" className="size-4" />
      <span className="hidden sm:inline">Mark as read</span>
    </Button>
  )
}

/**
 * One inbox entry on this page (the bell keeps its compact `NotificationItem`): a larger icon, an accent bar and
 * bold title while unread. The button is named by the title ("Unread: …" when unread) and described by the body,
 * label and age.
 * @param {{ notification: NotificationView, onOpen: (notification: NotificationView) => void, now: number, actions?: ReactNode }} props
 */
function InboxItem({ notification, onOpen, now, actions }) {
  const unread = !notification.read
  const detailsId = useId()
  return (
    <li
      className={cn(
        'relative flex items-start gap-2 px-4 py-4 transition-colors sm:px-5',
        unread ? 'bg-emerald-50/50 hover:bg-emerald-50' : 'hover:bg-slate-50',
      )}
    >
      {unread && <span aria-hidden="true" className="absolute inset-y-3 left-0 w-1 rounded-r-full bg-emerald-500" />}
      <button
        type="button"
        onClick={() => onOpen(notification)}
        aria-label={unread ? `Unread: ${notification.title}` : notification.title}
        aria-describedby={detailsId}
        className="flex min-w-0 flex-1 items-start gap-3.5 rounded-lg text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
      >
        <NotificationIcon category={notification.category} severity={notification.severity} type={notification.type} />
        <span className="min-w-0 flex-1">
          <span
            className={cn(
              'block text-sm leading-snug',
              unread ? 'font-semibold text-slate-900' : 'font-medium text-slate-700',
            )}
          >
            {notification.title}
          </span>
          <span id={detailsId}>
            {notification.body && (
              <span className="mt-1 block text-sm leading-relaxed text-slate-600">{notification.body}</span>
            )}
            <span className="mt-1.5 flex flex-wrap items-center gap-1.5 text-xs text-slate-500">
              <span className="rounded-full bg-slate-100 px-2 py-0.5 font-medium text-slate-600">
                {notificationLabel(notification)}
              </span>{' '}
              ·{' '}
              <time dateTime={notification.createdAt} title={formatDateTime(notification.createdAt)}>
                {formatRelativeTime(notification.createdAt, now)}
              </time>
            </span>
          </span>
        </span>
        {unread && (
          <span
            aria-hidden="true"
            className="mt-1.5 size-2.5 shrink-0 rounded-full bg-emerald-500 ring-4 ring-emerald-100"
          />
        )}
      </button>
      {actions && <div className="shrink-0">{actions}</div>}
    </li>
  )
}

/**
 * `/notifications` — the signed-in user's inbox for every role: All / Unread (`?filter=unread`), paged
 * (`?page=`), mark read, mark all read. Live notifications appear at the top without toasts while it is open.
 */
export function NotificationsPage() {
  useSuppressNotificationToasts()
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const filter = searchParams.get('filter') === 'unread' ? 'unread' : 'all'
  const page = pageFromParam(searchParams.get('page'))
  const notifications = useNotifications({
    unread: filter === 'unread' ? true : undefined,
    page,
    size: NOTIFICATION_PAGE_SIZE,
  })
  const unread = useUnreadNotificationCount()
  const unreadCount = unread.data ?? 0
  const markAll = useMarkAllNotificationsRead()
  const toast = useToast()
  const now = useNow(60_000)
  const openNotification = useOpenNotification()

  const tabs = [
    { id: 'all', label: 'All' },
    {
      id: 'unread',
      label: (
        <>
          Unread
          {unreadCount > 0 && (
            <span className="rounded-full bg-emerald-100 px-1.5 text-xs font-semibold text-emerald-800 tabular-nums">
              {unreadCount}
            </span>
          )}
        </>
      ),
    },
  ]

  function markAllRead() {
    markAll.mutate(undefined, {
      onSuccess: (updated) =>
        toast.success(updated === 1 ? 'Marked 1 notification as read.' : `Marked ${updated} notifications as read.`),
      onError: (error) => toast.error('Could not mark the notifications as read.', { description: error.message }),
    })
  }

  let content
  if (notifications.isPending) {
    content = (
      <div role="status" aria-label="Loading notifications" className="space-y-3">
        <Skeleton className="h-20 w-full rounded-2xl" />
        <Skeleton className="h-20 w-full rounded-2xl" />
        <Skeleton className="h-20 w-full rounded-2xl" />
      </div>
    )
  } else if (notifications.isError) {
    content = (
      <ErrorState
        title="Could not load your notifications"
        message={notifications.error.message}
        correlationId={notifications.error instanceof ApiError ? notifications.error.correlationId : null}
        onRetry={() => void notifications.refetch()}
      />
    )
  } else if (notifications.data.content.length === 0) {
    content =
      filter === 'unread' ? (
        <EmptyState
          size="lg"
          illustration={<NotificationsIllustration className="animate-float" />}
          title="You’re all caught up"
          description="No unread notifications."
          action={
            <Button
              variant="secondary"
              size="sm"
              onClick={() => updateParams({ filter: null, page: null }, { replace: true })}
            >
              Show all notifications
            </Button>
          }
        />
      ) : (
        <EmptyState
          size="lg"
          illustration={<NotificationsIllustration className="animate-float" />}
          title="No notifications yet"
          description="Order updates, approvals, stock and price alerts will appear here as they happen."
        />
      )
  } else {
    content = (
      <>
        <div className="animate-fade-up overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm">
          <ul
            aria-label={filter === 'unread' ? 'Unread notifications' : 'All notifications'}
            className={cn(
              'divide-y divide-slate-100 transition-opacity',
              notifications.isPlaceholderData && 'opacity-60',
            )}
          >
            {notifications.data.content.map((notification) => (
              <InboxItem
                key={notification.id}
                notification={notification}
                onOpen={openNotification}
                now={now}
                actions={notification.read ? undefined : <MarkReadButton notification={notification} />}
              />
            ))}
          </ul>
        </div>
        <Pagination
          page={notifications.data.page}
          size={notifications.data.size}
          totalElements={notifications.data.totalElements}
          totalPages={notifications.data.totalPages}
          disabled={notifications.isFetching}
          onPageChange={(next) => updateParams({ page: pageToParam(next) })}
        />
      </>
    )
  }

  return (
    <div className="w-full">
      <PageHeader
        title="Notifications"
        description="Order updates, approvals, stock and price alerts — newest first."
        actions={
          <>
            <LiveBadge />
            {/* Phase 12 §5 */}
            <Link
              to="/notifications/preferences"
              className="inline-flex h-10 items-center gap-2 rounded-xl px-3 text-sm font-semibold text-emerald-700 transition-colors hover:bg-emerald-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
            >
              <Icon name="mail" className="size-4" />
              Email preferences
            </Link>
            <Button variant="secondary" onClick={markAllRead} disabled={unreadCount === 0 || markAll.isPending}>
              <Icon name="checkAll" className="size-4" />
              Mark all as read
            </Button>
          </>
        }
      />
      <Tabs
        label="Filter notifications"
        tabs={tabs}
        value={filter}
        onChange={(next) =>
          updateParams({ filter: next === 'unread' ? 'unread' : null, page: null }, { replace: true })
        }
      >
        {content}
      </Tabs>
    </div>
  )
}
