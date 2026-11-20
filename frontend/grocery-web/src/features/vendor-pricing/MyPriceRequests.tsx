import { useId, useState } from 'react'
import { ApiError, errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../shared/api/types'
import { useToast } from '../../shared/components/toast/useToast'
import { Button, type Column, ConfirmDialog, DataTable, EmptyState, ErrorState, Pagination, Select, Spinner } from '../../shared/components/ui'
import { ExportButton } from '../../shared/export'
import { formatDateTime, formatMoney } from '../../shared/utils/format'
import {
  isPriceChangeStatus,
  PRICE_CHANGE_NOT_PENDING,
  PRICE_CHANGE_STATUS_LABELS,
  PRICE_CHANGE_STATUSES,
  type PriceChangeRequestView,
  type PriceChangeStatus,
} from './api'
import { useCancelPriceRequest, useMyPriceRequests } from './hooks'
import { ChangePercent, MoneyPair, PriceChangeStatusBadge } from './PriceBits'

/** "My requests": the vendor's price changes that needed approval, newest first, with cancel for pending ones. */
export function MyPriceRequests() {
  const toast = useToast()
  const statusId = useId()
  const [status, setStatus] = useState<PriceChangeStatus | undefined>(undefined)
  const [page, setPage] = useState(0)
  const [toCancel, setToCancel] = useState<PriceChangeRequestView | null>(null)
  const [cancelError, setCancelError] = useState<string | null>(null)
  const requests = useMyPriceRequests({ status, page, size: DEFAULT_PAGE_SIZE })
  const cancel = useCancelPriceRequest()

  function confirmCancel(request: PriceChangeRequestView) {
    setCancelError(null)
    cancel.mutate(request.id, {
      onSuccess: () => {
        setToCancel(null)
        toast.success(`The price change for ${request.productName} was cancelled.`)
      },
      onError: (error) => {
        if (hasErrorCode(error, PRICE_CHANGE_NOT_PENDING)) {
          setToCancel(null)
          toast.error(`The request for ${request.productName} was already decided, so it can no longer be cancelled. The list has been refreshed.`)
        } else {
          setCancelError(`Could not cancel the request: ${errorMessage(error)}`)
        }
      },
    })
  }

  const columns: Column<PriceChangeRequestView>[] = [
    {
      key: 'product',
      header: 'Product',
      cell: (request) => (
        <div className="min-w-0">
          <p className="font-medium text-slate-900">{request.productName}</p>
          <p className="text-xs text-slate-500">{request.unitLabel}</p>
        </div>
      ),
    },
    {
      key: 'current',
      header: 'Before',
      hideOnMobile: true,
      cell: (request) => <MoneyPair sellingPrice={request.currentSellingPrice} mrp={request.currentMrp} />,
    },
    {
      key: 'requested',
      header: 'Requested',
      cell: (request) => <MoneyPair sellingPrice={request.requestedSellingPrice} mrp={request.requestedMrp} />,
    },
    { key: 'change', header: 'Change', cell: (request) => <ChangePercent value={request.changePercent} /> },
    { key: 'status', header: 'Status', cell: (request) => <PriceChangeStatusBadge status={request.status} /> },
    {
      key: 'dates',
      header: 'Requested / decided',
      hideOnMobile: true,
      cell: (request) => (
        <div className="text-xs whitespace-nowrap">
          <p>{formatDateTime(request.requestedAt)}</p>
          {request.decidedAt && <p className="text-slate-500">{formatDateTime(request.decidedAt)}</p>}
        </div>
      ),
    },
    {
      key: 'note',
      header: 'Note / reason',
      hideOnMobile: true,
      cell: (request) => (
        <div className="max-w-56 text-xs">
          {request.note && <p className="text-slate-600">“{request.note}”</p>}
          {request.decisionReason && <p className="mt-0.5 font-medium text-slate-800">{request.decisionReason}</p>}
          {!request.note && !request.decisionReason && <span className="text-slate-400">—</span>}
        </div>
      ),
    },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'text-right',
      cell: (request) =>
        request.status === 'PENDING' ? (
          <Button
            size="sm"
            variant="ghost"
            className="text-red-600 hover:bg-red-50 hover:text-red-700"
            aria-label={`Cancel the price change for ${request.productName}`}
            onClick={() => {
              setCancelError(null)
              setToCancel(request)
            }}
          >
            Cancel
          </Button>
        ) : null,
    },
  ]

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={statusId} className="text-sm text-slate-600">
          Status
        </label>
        <Select
          id={statusId}
          className="h-9 w-44"
          value={status ?? ''}
          onChange={(event) => {
            const next = event.target.value
            setStatus(isPriceChangeStatus(next) ? next : undefined)
            setPage(0)
          }}
        >
          <option value="">All statuses</option>
          {PRICE_CHANGE_STATUSES.map((value) => (
            <option key={value} value={value}>
              {PRICE_CHANGE_STATUS_LABELS[value]}
            </option>
          ))}
        </Select>
        <ExportButton
          className="ml-auto"
          path="/vendor/price-change-requests/export"
          label="price change requests"
          params={{ status }}
          total={requests.data?.totalElements}
        />
      </div>

      {requests.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading your price requests" />
        </div>
      ) : requests.isError ? (
        <ErrorState
          title="Could not load your price requests"
          message={requests.error.message}
          correlationId={requests.error instanceof ApiError ? requests.error.correlationId : null}
          onRetry={() => void requests.refetch()}
        />
      ) : (
        <div>
          <DataTable
            caption="Your price-change requests"
            columns={columns}
            rows={requests.data.content}
            getRowKey={(request) => request.id}
            fetching={requests.isFetching && requests.isPlaceholderData}
            empty={
              <EmptyState
                title="No requests"
                description="Price changes bigger than the approval threshold are listed here while an admin reviews them."
              />
            }
          />
          <Pagination
            page={requests.data.page}
            size={requests.data.size}
            totalElements={requests.data.totalElements}
            totalPages={requests.data.totalPages}
            disabled={requests.isFetching}
            onPageChange={setPage}
          />
        </div>
      )}

      <ConfirmDialog
        open={toCancel !== null}
        title={toCancel ? `Cancel the price change for ${toCancel.productName}?` : 'Cancel the price change?'}
        confirmLabel="Cancel request"
        cancelLabel="Keep it"
        tone="danger"
        pending={cancel.isPending}
        error={cancelError}
        onClose={() => setToCancel(null)}
        onConfirm={() => toCancel && confirmCancel(toCancel)}
      >
        {toCancel && (
          <p>
            The request to sell at {formatMoney(toCancel.requestedSellingPrice)} is withdrawn. The live price stays as it is, and you can set a
            new one afterwards.
          </p>
        )}
      </ConfirmDialog>
    </div>
  )
}
