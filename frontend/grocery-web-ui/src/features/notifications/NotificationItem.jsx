import { useId } from 'react'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatRelativeTime } from '../../shared/utils/format'
import { NotificationIcon } from './NotificationIcon'
import { notificationLabel } from './style'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('./api').NotificationView} NotificationView */

/**
 * @typedef {object} NotificationItemProps
 * @property {NotificationView} notification
 * @property {(notification: NotificationView) => void} onOpen Marks it read and follows its link.
 * @property {number} now Reference time for "5 min ago".
 * @property {boolean} [compact] The bell's dense layout (body clamped to two lines).
 * @property {ReactNode} [actions] Controls kept outside the clickable area (e.g. "Mark as read").
 */

/**
 * One inbox entry: category icon (coloured by severity), title, body, category and age; unread ones stand out.
 * The button is named by the title ("Unread: …" when unread) and described by the body, category and age.
 * @param {NotificationItemProps} props
 */
export function NotificationItem({ notification, onOpen, now, compact = false, actions }) {
  const unread = !notification.read
  const detailsId = useId()
  return (
    <li className={cn('flex items-start gap-2', compact ? 'px-3 py-2.5' : 'px-4 py-3', unread && 'bg-emerald-50/50')}>
      <button
        type="button"
        onClick={() => onOpen(notification)}
        aria-label={unread ? `Unread: ${notification.title}` : notification.title}
        aria-describedby={detailsId}
        className="flex min-w-0 flex-1 items-start gap-3 rounded-lg text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
      >
        <NotificationIcon category={notification.category} severity={notification.severity} type={notification.type} />
        <span className="min-w-0 flex-1">
          <span className={cn('block text-sm', unread ? 'font-semibold text-slate-900' : 'font-medium text-slate-700')}>
            {notification.title}
          </span>
          <span id={detailsId}>
            {notification.body && (
              <span className={cn('mt-0.5 block text-sm text-slate-600', compact && 'line-clamp-2')}>
                {notification.body}
              </span>
            )}
            <span className="mt-1 block text-xs text-slate-500">
              {notificationLabel(notification)} ·{' '}
              <time dateTime={notification.createdAt} title={formatDateTime(notification.createdAt)}>
                {formatRelativeTime(notification.createdAt, now)}
              </time>
            </span>
          </span>
        </span>
        {unread && <span aria-hidden="true" className="mt-1.5 size-2 shrink-0 rounded-full bg-emerald-500" />}
      </button>
      {actions && <div className="shrink-0">{actions}</div>}
    </li>
  )
}
