import { Link } from 'react-router'
import { Button, Card, Spinner } from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { useNotifications } from './hooks'
import { NotificationItem } from './NotificationItem'
import { useOpenNotification } from './useOpenNotification'

const LATEST_COUNT = 5

/**
 * @typedef {object} LatestNotificationsCardProps
 * @property {string} title
 * @property {string} description
 */

/**
 * The newest few notifications on a dashboard; live notifications appear at the top as they arrive.
 * @param {LatestNotificationsCardProps} props
 */
export function LatestNotificationsCard({ title, description }) {
  const latest = useNotifications({ size: LATEST_COUNT })
  const openNotification = useOpenNotification()
  const now = useNow(60_000)
  const items = latest.data?.content ?? []

  return (
    <Card
      icon="bell"
      title={title}
      description={description}
      actions={
        <Link to="/notifications" className="text-sm font-medium text-emerald-700 hover:text-emerald-800">
          View all →
        </Link>
      }
    >
      {latest.isPending ? (
        <div className="flex justify-center py-6">
          <Spinner label="Loading notifications" />
        </div>
      ) : latest.isError ? (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
          <span>Could not load notifications: {latest.error.message}</span>
          <Button variant="secondary" size="sm" onClick={() => void latest.refetch()}>
            Try again
          </Button>
        </div>
      ) : items.length === 0 ? (
        <p className="py-4 text-center text-sm text-slate-500">
          Nothing yet. Approvals, refunds and price changes will appear here live.
        </p>
      ) : (
        <ul aria-label={title} className="-mx-3 divide-y divide-slate-100">
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
    </Card>
  )
}
