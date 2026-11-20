import { Link } from 'react-router'
import { Button } from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime } from '../../shared/utils/format'
import { RETURN_WINDOW_HOURS } from '../returns/api'
import { isOpenReturn, returnActionLabel } from '../returns/labels'
import { reviewWindowOpen } from '../reviews/labels'
import type { OrderView } from './api'
import { deliveredAtOf } from './statusSummary'

const primaryLinkClass =
  'inline-flex h-9 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-3 text-sm font-semibold whitespace-nowrap text-white shadow-sm shadow-emerald-600/25 transition-colors hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

interface AfterDeliveryActionsProps {
  order: OrderView
  /** Opens the return dialog. */
  onReturn: () => void
  className?: string
}

/**
 * Phase 12, on a delivered order: "Rate your order" (within the review window) and "Return items" / "Report a problem"
 * while the server says the order is `returnable` (within the return window, one open request at a time) — one slim
 * strip at the foot of the order's status card.
 */
export function AfterDeliveryActions({ order, onReturn, className }: AfterDeliveryActionsProps) {
  const now = useNow(60_000)
  const delivered = deliveredAtOf(order)
  const canRate = order.status === 'DELIVERED' && reviewWindowOpen(delivered, now)
  const returnable = order.returnable === true
  const openReturn = (order.returns ?? []).some((entry) => isOpenReturn(entry.status))
  if (!canRate && !returnable && !openReturn) return null

  const returnUntil = delivered ? new Date(Date.parse(delivered) + RETURN_WINDOW_HOURS * 3_600_000).toISOString() : null
  const action = returnActionLabel(order.type).toLowerCase()

  return (
    <section
      aria-label="After delivery"
      className={cn('@container bg-gradient-to-r from-amber-50/90 via-amber-50/40 to-emerald-50/60 px-4 py-3.5 sm:px-6', className)}
    >
      <div className="flex flex-col gap-3 @2xl:flex-row @2xl:items-center @2xl:justify-between">
        <div className="flex min-w-0 items-start gap-3">
          <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-amber-100">
            <Icon name="star" className="size-5" />
          </span>
          <div className="min-w-0 text-sm text-slate-600">
            <p className="font-semibold text-slate-900">How was your order?</p>
            {(canRate || returnable) && (
              <p className="mt-0.5">
                {canRate && 'Rate what you received, the store and the delivery.'}
                {canRate && returnable && ' '}
                {returnable && (
                  <>
                    Something wrong? You can {action}
                    {returnUntil ? ` until ${formatDateTime(returnUntil)}` : ''}.
                  </>
                )}
              </p>
            )}
            {openReturn && (
              <p className="mt-0.5 inline-flex items-center gap-1.5 font-medium text-violet-700">
                <Icon name="refresh" className="size-4" />
                Your request is being handled — see the details below.
              </p>
            )}
          </div>
        </div>
        {(canRate || returnable) && (
          <div className={cn('grid gap-2 @md:flex @md:pl-12 @2xl:shrink-0 @2xl:pl-0', canRate && returnable ? 'grid-cols-2' : 'grid-cols-1')}>
            {canRate && (
              <Link to={`/orders/${encodeURIComponent(order.id)}/review`} className={primaryLinkClass}>
                <Icon name="star" className="size-4" />
                Rate your order
              </Link>
            )}
            {returnable && (
              <Button variant="secondary" className="h-9 px-3 whitespace-nowrap" onClick={onReturn}>
                <Icon name="return" className="size-4" />
                {returnActionLabel(order.type)}
              </Button>
            )}
          </div>
        )}
      </div>
    </section>
  )
}
