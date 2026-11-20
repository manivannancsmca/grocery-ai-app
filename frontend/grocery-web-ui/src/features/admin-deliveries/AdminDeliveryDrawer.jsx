import { useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { useAuth } from '../../shared/auth/useAuth'
import { Alert, Badge, Button, DescriptionList, Dialog, ErrorState, Spinner } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatMoney, formatTime } from '../../shared/utils/format'
import {
  deliveryStatusLabel,
  deliveryStatusTone,
  dropAddressLines,
  failReasonText,
  itemsText,
  kmText,
} from '../deliveries/labels'
import { ORDER_TYPE_LABELS } from '../orders/labels'
import { REASSIGNABLE_STATUSES } from './api'
import { useAdminDelivery } from './hooks'
import { ReassignDialog } from './ReassignDialog'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../deliveries/api').DeliveryTimelineEntry} DeliveryTimelineEntry */
/** @typedef {import('./api').AdminDeliveryView} AdminDeliveryView */
/** @typedef {import('./api').DeliveryOfferRecord} DeliveryOfferRecord */
/** @typedef {import('./api').OfferStatus} OfferStatus */

const linkClass = 'font-semibold text-emerald-700 hover:text-emerald-800 hover:underline'

/** @type {Record<OfferStatus, string>} */
const OFFER_STATUS_LABELS = {
  PENDING: 'Waiting for an answer',
  ACCEPTED: 'Accepted',
  REJECTED: 'Rejected',
  EXPIRED: 'Expired',
  WITHDRAWN: 'Withdrawn',
}

/** @type {Record<OfferStatus, BadgeTone>} */
const OFFER_STATUS_TONES = {
  PENDING: 'amber',
  ACCEPTED: 'green',
  REJECTED: 'red',
  EXPIRED: 'gray',
  WITHDRAWN: 'gray',
}

/** @param {{ status: string }} props */
function OfferStatusBadge({ status }) {
  const known = status in OFFER_STATUS_LABELS
  return (
    <Badge tone={known ? OFFER_STATUS_TONES[status] : 'gray'}>{known ? OFFER_STATUS_LABELS[status] : status}</Badge>
  )
}

/** @param {{ status: string }} props */
export function DeliveryStatusBadge({ status }) {
  return (
    <Badge tone={deliveryStatusTone(status)} dot>
      {deliveryStatusLabel(status)}
    </Badge>
  )
}

/** @param {{ title: string, children: ReactNode }} props */
function Section({ title, children }) {
  return (
    <section>
      <h3 className="mb-3 text-sm font-semibold text-slate-900">{title}</h3>
      {children}
    </section>
  )
}

/** @param {{ timeline: readonly DeliveryTimelineEntry[] }} props */
function DeliveryTimeline({ timeline }) {
  if (timeline.length === 0) return <p className="text-sm text-slate-500">No status changes yet.</p>
  return (
    <ol aria-label="Delivery history" className="space-y-3 border-l border-slate-200 pl-4">
      {timeline.map((entry, index) => (
        <li key={`${entry.status}-${entry.at}-${index}`} className="relative">
          <span
            aria-hidden="true"
            className={cn(
              'absolute top-1.5 -left-[21px] size-2.5 rounded-full border-2 border-white',
              index === timeline.length - 1 ? 'bg-emerald-500' : 'bg-slate-300',
            )}
          />
          <DeliveryStatusBadge status={entry.status} />
          <p className="mt-1 text-xs text-slate-500">{formatDateTime(entry.at)}</p>
        </li>
      ))}
    </ol>
  )
}

/** @param {{ offers: readonly DeliveryOfferRecord[] }} props */
function OffersHistory({ offers }) {
  if (offers.length === 0) return <p className="text-sm text-slate-500">No offers sent yet.</p>
  return (
    <ul aria-label="Offers" className="divide-y divide-slate-100 rounded-lg border border-slate-200">
      {offers.map((offer) => (
        <li key={offer.offerId} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm">
          <span className="min-w-0">
            <span className="block font-medium text-slate-900">{offer.partnerName ?? offer.partnerId}</span>
            <span className="block text-xs text-slate-500">
              {kmText(offer.distanceKm)} from the store · sent {formatTime(offer.sentAt)}
              {offer.closedAt ? ` · closed ${formatTime(offer.closedAt)}` : ''}
            </span>
          </span>
          <OfferStatusBadge status={offer.status} />
        </li>
      ))}
    </ul>
  )
}

/**
 * @typedef {object} AdminDeliveryDrawerProps
 * @property {string} id
 * @property {AdminDeliveryView} [initial] The clicked row, shown while the full delivery loads.
 * @property {() => void} onClose
 */

/**
 * One delivery for admins: status, why nobody was found, timeline, the offers with their outcomes, the
 * partner, pickup and drop — and "Reassign" for SUPER_ADMIN, OPS_ADMIN and DELIVERY_MANAGER while SEARCHING
 * or ASSIGNED.
 * @param {AdminDeliveryDrawerProps} props
 */
export function AdminDeliveryDrawer({ id, initial, onClose }) {
  const { can } = useAuth()
  const detail = useAdminDelivery(id)
  const [reassigning, setReassigning] = useState(null)

  const delivery = detail.data ?? initial
  const canReassign = can('deliveries.reassign')
  const reassignable = delivery ? REASSIGNABLE_STATUSES.includes(delivery.status) : false

  return (
    <>
      <Dialog
        open
        variant="drawer"
        icon="truck"
        onClose={onClose}
        title={delivery ? `Delivery for order ${delivery.orderNumber}` : 'Loading delivery…'}
        description={
          delivery ? (
            <span className="flex flex-wrap gap-1.5">
              <DeliveryStatusBadge status={delivery.status} />
              {delivery.assignmentFailed && <Badge tone="red">No partner found</Badge>}
            </span>
          ) : undefined
        }
        footer={
          detail.data && canReassign && reassignable ? (
            <Button onClick={() => setReassigning(detail.data)}>
              {detail.data.partnerId ? 'Reassign' : 'Assign a partner'}
            </Button>
          ) : undefined
        }
      >
        {detail.isPending ? (
          <div className="flex justify-center py-10">
            <Spinner size="lg" label="Loading delivery" />
          </div>
        ) : detail.isLoadingError ? (
          <ErrorState
            title="Could not load this delivery"
            message={detail.error.message}
            correlationId={detail.error instanceof ApiError ? detail.error.correlationId : null}
            onRetry={() => void detail.refetch()}
          />
        ) : detail.data ? (
          <div className="space-y-6">
            {detail.data.assignmentFailed && detail.data.status === 'SEARCHING' && (
              <Alert tone="warning" title="No delivery partner found">
                Nobody accepted within the largest search radius. A fresh search runs every minute
                {canReassign ? ' — or assign a partner by hand.' : '.'}
              </Alert>
            )}
            {detail.data.failure && (
              <Alert tone="danger" title={`Not delivered: ${failReasonText(detail.data.failure.reason)}`}>
                {detail.data.failure.note && <p>“{detail.data.failure.note}”</p>}
                <p className="text-xs">Decide on a refund in the payment monitor.</p>
              </Alert>
            )}
            {!canReassign && reassignable && (
              <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
                Your admin role can view deliveries but not reassign them. Super admins, operations admins and delivery
                managers can.
              </p>
            )}

            <Section title="Details">
              <DescriptionList
                items={[
                  {
                    label: 'Order',
                    value: (
                      <Link
                        to={`/admin/orders?q=${encodeURIComponent(detail.data.orderNumber)}`}
                        onClick={onClose}
                        className={cn('font-mono', linkClass)}
                      >
                        {detail.data.orderNumber}
                      </Link>
                    ),
                  },
                  { label: 'Type', value: ORDER_TYPE_LABELS[detail.data.orderType] ?? detail.data.orderType },
                  {
                    label: 'Items',
                    value: `${itemsText(detail.data.itemCount)} · ${formatMoney(detail.data.total, detail.data.currency)}`,
                  },
                  {
                    label: 'Ready for pickup',
                    value: detail.data.readyForPickup
                      ? 'Yes'
                      : `Not yet${detail.data.readyBy ? ` (promised by ${formatTime(detail.data.readyBy)})` : ''}`,
                  },
                  { label: 'Offers sent', value: detail.data.offersSent ?? detail.data.offers.length },
                ]}
              />
            </Section>

            <Section title="Delivery partner">
              {detail.data.partnerId ? (
                <DescriptionList
                  items={[
                    {
                      label: 'Partner',
                      value: (
                        <>
                          {detail.data.partnerName ?? '—'}
                          <span className="block font-mono text-xs text-slate-500">{detail.data.partnerId}</span>
                        </>
                      ),
                    },
                    {
                      label: 'Assigned',
                      value: detail.data.assignedAt ? formatDateTime(detail.data.assignedAt) : null,
                    },
                    {
                      label: 'Picked up',
                      value: detail.data.pickedUpAt ? formatDateTime(detail.data.pickedUpAt) : null,
                    },
                    {
                      label: detail.data.status === 'FAILED' ? 'Failed' : 'Delivered',
                      value:
                        detail.data.status === 'FAILED'
                          ? detail.data.failedAt && formatDateTime(detail.data.failedAt)
                          : detail.data.deliveredAt && formatDateTime(detail.data.deliveredAt),
                    },
                  ]}
                />
              ) : (
                <p className="text-sm text-slate-500">No partner assigned.</p>
              )}
            </Section>

            <Section title="Pickup and drop">
              <DescriptionList
                items={[
                  {
                    label: 'Pickup',
                    value: detail.data.pickup
                      ? [detail.data.pickup.name, detail.data.pickup.city].filter(Boolean).join(', ')
                      : 'Unknown (older order)',
                  },
                  {
                    label: 'Drop',
                    value: (
                      <address className="not-italic">
                        <span className="block font-medium">{detail.data.drop.recipientName}</span>
                        {dropAddressLines(detail.data.drop).map((line) => (
                          <span key={line} className="block">
                            {line}
                          </span>
                        ))}
                        {detail.data.drop.phone && (
                          <span className="block text-slate-500">{detail.data.drop.phone}</span>
                        )}
                      </address>
                    ),
                  },
                  { label: 'Customer note', value: detail.data.note, wide: true },
                ]}
              />
            </Section>

            <Section title="Status history">
              <DeliveryTimeline timeline={detail.data.timeline} />
            </Section>

            <Section title="Offers">
              <OffersHistory offers={detail.data.offers} />
            </Section>
          </div>
        ) : null}
      </Dialog>

      <ReassignDialog delivery={reassigning} onClose={() => setReassigning(null)} />
    </>
  )
}
