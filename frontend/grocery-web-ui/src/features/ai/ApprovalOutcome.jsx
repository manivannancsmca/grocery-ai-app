/** What an AI approval card shows once the request is decided: the order it placed or cancelled, or why nothing was done (not approved, expired, failed). */

import { Link } from 'react-router'
import { Alert, Spinner } from '../../shared/components/ui'
import { formatDateTime } from '../../shared/utils/format'
import { adminOrderLink } from '../admin-orders/labels'
import { approvalResultLink, approvalResultNote, resultOrder } from './labels'

/** @typedef {import('./api').ApprovalView} ApprovalView */

/**
 * Where a finished order action's order is shown: the customer's order page, or the admin order drawer.
 * @param {string} orderId
 * @param {string | null} orderNumber
 * @param {boolean} admin
 * @returns {string}
 */
function orderHref(orderId, orderNumber, admin) {
  return admin ? adminOrderLink({ orderId, orderNumber }) : `/orders/${encodeURIComponent(orderId)}`
}

/** @param {{ view: ApprovalView, admin: boolean }} props */
export function Outcome({ view, admin }) {
  const decided = [view.decidedAt ? formatDateTime(view.decidedAt) : null].filter(Boolean).join(' · ')
  switch (view.status) {
    case 'APPROVED':
    case 'EXECUTING':
      return (
        <Alert tone="info" title="Running the approved action…">
          <span className="inline-flex items-center gap-2">
            <Spinner size="sm" /> This takes a moment.
          </span>
        </Alert>
      )
    case 'COMPLETED': {
      const { orderId, orderNumber } = resultOrder(view)
      if (view.action === 'PLACE_ORDER') {
        return (
          <Alert tone="success" role="status" title={orderNumber ? `Order ${orderNumber} placed` : 'Order placed'}>
            <p>Pay for it on the order page to confirm it with the store.</p>
            {orderId && (
              <Link
                to={orderHref(orderId, orderNumber, admin)}
                className="mt-1 inline-block font-semibold underline underline-offset-2"
              >
                Open order {orderNumber ?? ''}
              </Link>
            )}
          </Alert>
        )
      }
      // Phase 12 store and delivery actions link to the page they changed (a store never opens the customer's order).
      const changed = approvalResultLink(view)
      const note = approvalResultNote(view)
      return (
        <Alert tone="success" role="status" title="Done">
          {orderNumber && <p>Order {orderNumber}.</p>}
          {note && <p>{note}</p>}
          {changed ? (
            <Link to={changed.to} className="inline-block font-semibold underline underline-offset-2">
              {changed.label}
            </Link>
          ) : (
            orderId && (
              <Link
                to={orderHref(orderId, orderNumber, admin)}
                className="inline-block font-semibold underline underline-offset-2"
              >
                Open order {orderNumber ?? ''}
              </Link>
            )
          )}
          {view.executedAt && <p className="text-xs">Completed {formatDateTime(view.executedAt)}.</p>}
        </Alert>
      )
    }
    case 'FAILED':
      return (
        <Alert tone="danger" role="status" title="Could not complete">
          <p>{view.failure?.message ?? 'The action failed.'}</p>
          {view.failure?.code && <p className="mt-1 font-mono text-xs">{view.failure.code}</p>}
          <p className="mt-1 text-xs">Nothing was changed. Ask the assistant again if it is still needed.</p>
        </Alert>
      )
    case 'REJECTED':
      return (
        <Alert tone="neutral" title="Not approved">
          {view.comment && <p className="break-words">“{view.comment}”</p>}
          {decided && <p className="text-xs text-slate-500">{decided}</p>}
        </Alert>
      )
    case 'EXPIRED':
      return (
        <Alert tone="neutral" title="Expired">
          Nobody decided in time, so nothing was done.
        </Alert>
      )
    case 'CANCELLED':
      return (
        <Alert tone="neutral" title="Cancelled">
          The request was withdrawn; nothing was done.
        </Alert>
      )
    default:
      return null
  }
}
