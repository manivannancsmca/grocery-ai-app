import { useId, useState } from 'react'
import { ApiError, errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../shared/api/types'
import { useAuth } from '../../shared/auth/useAuth'
import { ReasonDialog } from '../../shared/components/ReasonDialog'
import { useToast } from '../../shared/components/toast/useToast'
import {
  Alert,
  Avatar,
  Button,
  DataTable,
  Dialog,
  EmptyState,
  ErrorState,
  Pagination,
  Spinner,
  StatCard,
  Textarea,
} from '../../shared/components/ui'
import { ExportButton } from '../../shared/export'
import { emptyToNull } from '../../shared/forms/validation'
import { Icon } from '../../shared/icons'
import { formatDateTime, formatMoney, formatSignedPercent } from '../../shared/utils/format'
import { PRICE_CHANGE_NOT_PENDING, PRICE_CHANGE_STATUS_LABELS, PRICE_LIMITS } from '../vendor-pricing/api'
import { ChangePercent, MoneyPair, PriceChangeStatusBadge } from '../vendor-pricing/PriceBits'
import { useAdminPriceRequests, usePriceDecision } from './hooks'

/** @typedef {import('../../shared/components/ui').StatAccent} StatAccent */
/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('../vendor-pricing/api').PriceChangeRequestView} PriceChangeRequestView */
/** @typedef {import('../vendor-pricing/api').PriceChangeStatus} PriceChangeStatus */

/**
 * @typedef {object} PriceRequestQueueProps
 * @property {PriceChangeStatus} status
 * @property {number} page
 * @property {(page: number) => void} onPageChange
 */

/**
 * The colour and picture of the "how many" tile, per tab.
 * @type {Record<PriceChangeStatus, { accent: StatAccent, icon: IconName }>}
 */
const STATUS_LOOK = {
  PENDING: { accent: 'amber', icon: 'hourglass' },
  APPROVED: { accent: 'emerald', icon: 'checkCircle' },
  REJECTED: { accent: 'rose', icon: 'xCircle' },
  CANCELLED: { accent: 'sky', icon: 'ban' },
}

/**
 * What an empty tab means and what will show up there.
 * @type {Record<PriceChangeStatus, string>}
 */
const EMPTY_DESCRIPTIONS = {
  PENDING: 'All caught up. Vendors’ price changes bigger than the approval threshold appear here, oldest first.',
  APPROVED: 'Price changes approved by an admin are listed here, newest first.',
  REJECTED: 'Price changes rejected by an admin are listed here with the reason, newest first.',
  CANCELLED: 'Price changes a vendor withdrew before a decision are listed here, newest first.',
}

/**
 * @param {PriceChangeRequestView} request
 * @returns {string}
 */
function alreadyDecidedMessage(request) {
  return `The price change for ${request.productName} was already decided (or cancelled by the vendor). The list has been refreshed.`
}

/**
 * @typedef {object} QueueSummaryProps
 * @property {PriceChangeStatus} status
 * @property {number} total
 * @property {readonly PriceChangeRequestView[]} rows
 * @property {boolean} paged More than one page: the raise / drop counts then only cover the rows on screen, and say so.
 */

/**
 * Headline numbers above the table: how many requests have this status, and how many on screen raise or lower a price.
 * @param {QueueSummaryProps} props
 */
function QueueSummary({ status, total, rows, paged }) {
  const raises = rows.filter((request) => request.changePercent > 0).length
  const drops = rows.filter((request) => request.changePercent < 0).length
  const scope = paged ? ' on this page' : ''
  const look = STATUS_LOOK[status]
  return (
    <section aria-label="Price changes at a glance">
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <StatCard
          className="col-span-2 sm:col-span-1"
          label={status === 'PENDING' ? 'Waiting for a decision' : `${PRICE_CHANGE_STATUS_LABELS[status]} requests`}
          value={total.toLocaleString('en-IN')}
          icon={look.icon}
          accent={look.accent}
        />
        <StatCard
          label={`Price raises${scope}`}
          value={raises.toLocaleString('en-IN')}
          icon="trendUp"
          accent="violet"
        />
        <StatCard label={`Price drops${scope}`} value={drops.toLocaleString('en-IN')} icon="savings" accent="teal" />
      </dl>
    </section>
  )
}

/** Price-change requests of one status. Pending ones are oldest first and can be approved or rejected. */
/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['current', 'requested', 'change', 'requestedAt']

/** @param {PriceRequestQueueProps} props */
export function PriceRequestQueue({ status, page, onPageChange }) {
  const { can } = useAuth()
  const canDecide = can('prices.approve')
  const toast = useToast()
  const requests = useAdminPriceRequests({ status, page, size: DEFAULT_PAGE_SIZE })
  const [sort, setSort] = useState(null)
  const decision = usePriceDecision()
  const [approving, setApproving] = useState(null)
  const [rejecting, setRejecting] = useState(null)
  const pending = status === 'PENDING'

  async function reject(request, reason) {
    try {
      await decision.mutateAsync({ action: 'reject', id: request.id, reason })
      setRejecting(null)
      toast.success(`Price change for ${request.productName} rejected. The live price stays.`)
    } catch (error) {
      if (hasErrorCode(error, PRICE_CHANGE_NOT_PENDING)) {
        setRejecting(null)
        toast.error(alreadyDecidedMessage(request))
        return
      }
      throw error
    }
  }

  const columns = [
    {
      key: 'product',
      header: 'Product',
      sortValue: (request) => request.productName,
      cell: (request) => (
        <div className="min-w-0">
          <p className="font-medium text-slate-900">{request.productName}</p>
          <p className="text-xs text-slate-500">
            {request.unitLabel}
            <span className="md:hidden"> · {request.vendorName ?? 'Vendor'}</span>
          </p>
        </div>
      ),
    },
    {
      key: 'vendor',
      header: 'Vendor',
      hideOnMobile: true,
      sortValue: (request) => request.vendorName ?? request.vendorId,
      cell: (request) =>
        request.vendorName ? (
          <div className="flex min-w-0 items-center gap-2.5">
            <Avatar name={request.vendorName} size="sm" />
            <span className="min-w-0">{request.vendorName}</span>
          </div>
        ) : (
          <span className="font-mono text-xs text-slate-500">{request.vendorId}</span>
        ),
    },
    {
      key: 'current',
      header: 'Current',
      sortValue: (request) => request.currentSellingPrice,
      cell: (request) => <MoneyPair sellingPrice={request.currentSellingPrice} mrp={request.currentMrp} />,
    },
    {
      key: 'requested',
      header: 'Requested',
      sortValue: (request) => request.requestedSellingPrice,
      cell: (request) => <MoneyPair sellingPrice={request.requestedSellingPrice} mrp={request.requestedMrp} />,
    },
    {
      key: 'change',
      header: 'Change',
      sortValue: (request) => request.changePercent,
      cell: (request) => <ChangePercent value={request.changePercent} />,
    },
    {
      key: 'note',
      header: pending ? 'Vendor note' : 'Note / decision',
      hideOnMobile: true,
      cell: (request) => (
        <div className="max-w-60 space-y-0.5 text-xs">
          {request.note ? (
            <p className="text-slate-600">“{request.note}”</p>
          ) : (
            <p className="text-slate-400">No note</p>
          )}
          {request.decisionReason && <p className="font-medium text-slate-800">{request.decisionReason}</p>}
        </div>
      ),
    },
    {
      key: 'requestedAt',
      header: pending ? 'Requested' : 'Requested / decided',
      hideOnMobile: true,
      // ISO timestamps sort correctly as text.
      sortValue: (request) => request.requestedAt,
      cell: (request) => (
        <div className="text-xs whitespace-nowrap">
          <p>{formatDateTime(request.requestedAt)}</p>
          {request.decidedAt && <p className="text-slate-500">{formatDateTime(request.decidedAt)}</p>}
        </div>
      ),
    },
    ...(pending
      ? []
      : [{ key: 'status', header: 'Status', cell: (request) => <PriceChangeStatusBadge status={request.status} /> }]),
    ...(pending && canDecide
      ? [
          {
            key: 'actions',
            header: <span className="sr-only">Actions</span>,
            className: 'text-right',
            // Deciding is this queue's job, so both decisions stay one click away (no "⋯" menu); each still opens its
            // own dialog before anything changes.
            cell: (request) => (
              <span className="inline-flex items-center justify-end gap-2">
                <Button
                  size="sm"
                  onClick={() => setApproving(request)}
                  aria-label={`Approve the price change for ${request.productName}`}
                >
                  <Icon name="check" className="size-4" />
                  Approve
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => setRejecting(request)}
                  aria-label={`Reject the price change for ${request.productName}`}
                  className="text-red-700 hover:bg-red-50! hover:text-red-800! hover:ring-red-200!"
                >
                  <Icon name="close" className="size-4" />
                  Reject
                </Button>
              </span>
            ),
          },
        ]
      : []),
  ]

  return (
    <div className="space-y-4">
      {pending && !canDecide && (
        <Alert tone="neutral" role="status">
          Your role can view price changes but not approve or reject them. Super, operations and finance admins decide.
        </Alert>
      )}

      {requests.isPending ? (
        <div className="flex justify-center rounded-2xl border border-slate-200/80 bg-white py-12 shadow-sm">
          <Spinner size="lg" label="Loading price changes" />
        </div>
      ) : requests.isError ? (
        <ErrorState
          title="Could not load the price changes"
          message={requests.error.message}
          correlationId={requests.error instanceof ApiError ? requests.error.correlationId : null}
          onRetry={() => void requests.refetch()}
        />
      ) : (
        <div className="space-y-4">
          <div className="flex justify-end">
            <ExportButton
              path="/admin/price-change-requests/export"
              label="price changes"
              params={{ status }}
              total={requests.data.totalElements}
              sort={sort}
              sortable={EXPORT_SORTS}
            />
          </div>
          {requests.data.content.length > 0 && (
            <QueueSummary
              status={status}
              total={requests.data.totalElements}
              rows={requests.data.content}
              paged={requests.data.totalPages > 1}
            />
          )}
          <div>
            <DataTable
              caption={`${PRICE_CHANGE_STATUS_LABELS[status]} price changes`}
              columns={columns}
              rows={requests.data.content}
              getRowKey={(request) => request.id}
              sort={sort}
              onSortChange={setSort}
              fetching={requests.isFetching && requests.isPlaceholderData}
              empty={
                <EmptyState
                  title={
                    pending
                      ? 'No price changes waiting'
                      : `No ${PRICE_CHANGE_STATUS_LABELS[status].toLowerCase()} price changes`
                  }
                  description={EMPTY_DESCRIPTIONS[status]}
                />
              }
            />
            <Pagination
              page={requests.data.page}
              size={requests.data.size}
              totalElements={requests.data.totalElements}
              totalPages={requests.data.totalPages}
              disabled={requests.isFetching}
              onPageChange={onPageChange}
            />
          </div>
        </div>
      )}

      <ApproveDialog
        request={approving}
        onClose={() => setApproving(null)}
        onAlreadyDecided={(request) => {
          setApproving(null)
          toast.error(alreadyDecidedMessage(request))
        }}
      />
      <ReasonDialog
        open={rejecting !== null}
        title={rejecting ? `Reject the price change for ${rejecting.productName}?` : 'Reject the price change?'}
        description="The live price stays. The vendor sees your reason."
        confirmLabel="Reject change"
        maxLength={PRICE_LIMITS.note}
        onSubmit={(reason) => (rejecting ? reject(rejecting, reason) : Promise.resolve())}
        onClose={() => setRejecting(null)}
      />
    </div>
  )
}

/**
 * @typedef {object} ApproveDialogProps
 * @property {PriceChangeRequestView | null} request
 * @property {() => void} onClose
 * @property {(request: PriceChangeRequestView) => void} onAlreadyDecided
 */

/** @param {ApproveDialogProps} props */
function ApproveDialog({ request, onClose, onAlreadyDecided }) {
  return (
    <Dialog
      open={request !== null}
      onClose={onClose}
      size="sm"
      title={request ? `Approve the price change for ${request.productName}?` : 'Approve?'}
    >
      {request && (
        <ApproveForm key={request.id} request={request} onClose={onClose} onAlreadyDecided={onAlreadyDecided} />
      )}
    </Dialog>
  )
}

/** @param {{ request: PriceChangeRequestView } & Omit<ApproveDialogProps, 'request'>} props */
function ApproveForm({ request, onClose, onAlreadyDecided }) {
  const toast = useToast()
  const decision = usePriceDecision()
  const noteId = useId()
  const [note, setNote] = useState('')
  const [error, setError] = useState(null)

  function approve() {
    setError(null)
    decision.mutate(
      { action: 'approve', id: request.id, note: emptyToNull(note) },
      {
        onSuccess: () => {
          onClose()
          toast.success(`Approved. ${request.productName} now sells at ${formatMoney(request.requestedSellingPrice)}.`)
        },
        onError: (failure) => {
          if (hasErrorCode(failure, PRICE_CHANGE_NOT_PENDING)) onAlreadyDecided(request)
          else if (hasErrorCode(failure, 'PRODUCT_ARCHIVED'))
            setError('The product was archived, so its price can no longer change.')
          else setError(`Could not approve: ${errorMessage(failure)}`)
        },
      },
    )
  }

  return (
    <div className="space-y-4 text-sm text-slate-600">
      <p>
        The selling price changes from {formatMoney(request.currentSellingPrice)} to{' '}
        <strong className="text-slate-900">{formatMoney(request.requestedSellingPrice)}</strong> (
        {formatSignedPercent(request.changePercent)}), and the MRP from {formatMoney(request.currentMrp)} to{' '}
        {formatMoney(request.requestedMrp)}. It goes live immediately.
      </p>
      <div className="space-y-1.5">
        <label htmlFor={noteId} className="block font-medium text-slate-700">
          Note <span className="font-normal text-slate-500">(optional)</span>
        </label>
        <Textarea
          id={noteId}
          rows={2}
          maxLength={PRICE_LIMITS.note}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
      </div>
      {error && (
        <Alert tone="danger" role="alert">
          {error}
        </Alert>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={decision.isPending}>
          Cancel
        </Button>
        <Button onClick={approve} disabled={decision.isPending}>
          {decision.isPending && <Spinner size="sm" className="[&_svg]:text-white" />}
          Approve change
        </Button>
      </div>
    </div>
  )
}
