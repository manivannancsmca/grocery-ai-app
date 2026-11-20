import { useState } from 'react'
import { isIsoDate } from '../../../features/admin-orders/api'
import { AdminPaymentDrawer } from '../../../features/admin-payments/AdminPaymentDrawer'
import { useAdminPayments } from '../../../features/admin-payments/hooks'
import {
  PAYMENT_STATUS_LABELS,
  paymentMethodLabel,
  REFUND_STATUS_LABELS,
} from '../../../features/admin-payments/labels'
import { PaymentStatusBadge, RefundStatusBadge } from '../../../features/admin-payments/PaymentBits'
import { ReconciliationSummary } from '../../../features/admin-payments/ReconciliationSummary'
import { isPaymentStatus, isRefundStatus, PAYMENT_STATUSES, REFUND_STATUSES } from '../../../features/payments/api'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import {
  Button,
  DataTable,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Spinner,
  TextField,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { formatDateTime, formatMoney } from '../../../shared/utils/format'

/** @typedef {import('../../../features/payments/api').PaymentView} PaymentView */
/** @typedef {import('../../../shared/components/ui').Column} Column */

/**
 * @param {(payment: PaymentView) => void} onView
 * @returns {Column<PaymentView>[]}
 */
const columns = (onView) => [
  {
    key: 'order',
    header: 'Order',
    sortValue: (payment) => payment.orderNumber,
    cell: (payment) => (
      <div className="min-w-0">
        <p className="font-mono font-semibold text-slate-900">{payment.orderNumber}</p>
        <p className="flex items-center gap-1 text-xs whitespace-nowrap text-slate-500">
          <Icon name="clock" className="size-3.5" />
          {formatDateTime(payment.createdAt)}
        </p>
      </div>
    ),
  },
  {
    key: 'amount',
    header: 'Amount',
    className: 'text-right',
    sortValue: (payment) => payment.amount,
    cell: (payment) => (
      <span className="font-semibold whitespace-nowrap text-slate-900 tabular-nums">
        {formatMoney(payment.amount, payment.currency)}
      </span>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    sortValue: (payment) => PAYMENT_STATUS_LABELS[payment.status],
    cell: (payment) => <PaymentStatusBadge status={payment.status} />,
  },
  {
    key: 'refunds',
    header: 'Refunds',
    // Sorted by the amount refunded so far (money going back to customers).
    sortValue: (payment) => payment.refundedAmount,
    cell: (payment) => (
      <span className="flex flex-col items-start gap-1">
        <RefundStatusBadge status={payment.refundStatus} />
        {payment.refundedAmount > 0 && (
          <span className="flex items-center gap-1 text-xs font-semibold whitespace-nowrap text-rose-700 tabular-nums">
            <Icon name="return" className="size-3.5" />
            {formatMoney(payment.refundedAmount, payment.currency)} refunded
          </span>
        )}
      </span>
    ),
  },
  {
    key: 'method',
    header: 'Method',
    hideOnMobile: true,
    sortValue: (payment) => (payment.method ? paymentMethodLabel(payment.method) : null),
    cell: (payment) =>
      payment.method ? paymentMethodLabel(payment.method) : <span className="text-slate-400">—</span>,
  },
  {
    key: 'captured',
    header: 'Captured',
    hideOnMobile: true,
    sortValue: (payment) => payment.capturedAt,
    cell: (payment) =>
      payment.capturedAt ? (
        <span className="whitespace-nowrap">{formatDateTime(payment.capturedAt)}</span>
      ) : (
        <span className="text-slate-400">—</span>
      ),
  },
  {
    key: 'actions',
    header: <span className="sr-only">Actions</span>,
    className: 'text-right',
    cell: (payment) => (
      <Button
        variant="secondary"
        size="sm"
        onClick={() => onView(payment)}
        aria-label={`View payment for order ${payment.orderNumber}`}
      >
        <Icon name="eye" className="size-4" />
        View
      </Button>
    ),
  },
]

/**
 * `/admin/payments` — every payment, newest first, filterable by status, refund status, order id and dates (in
 * the URL). Any admin may read; refunds are for SUPER_ADMIN and FINANCE_ADMIN.
 */
/** Columns the server can order the export by (its sort options use the same keys). */
const EXPORT_SORTS = ['order', 'amount', 'status', 'refunds']

export function AdminPaymentsPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [selected, setSelected] = useState(null)
  const [sort, setSort] = useState(null)

  const statusParam = searchParams.get('status')
  const status = isPaymentStatus(statusParam) ? statusParam : undefined
  const refundParam = searchParams.get('refundStatus')
  const refundStatus = isRefundStatus(refundParam) ? refundParam : undefined
  const orderIdText = searchParams.get('orderId') ?? ''
  const orderId = useDebouncedValue(orderIdText.trim(), 300)
  const fromParam = searchParams.get('from')
  const from = isIsoDate(fromParam) ? fromParam : undefined
  const toParam = searchParams.get('to')
  const to = isIsoDate(toParam) ? toParam : undefined
  const page = pageFromParam(searchParams.get('page'))

  const payments = useAdminPayments({
    status,
    refundStatus,
    orderId: orderId || undefined,
    from,
    to,
    page,
    size: DEFAULT_PAGE_SIZE,
  })
  const filtered = Boolean(status || refundStatus || orderId || from || to)
  const activeFilters = [orderIdText.trim(), status, refundStatus, from, to].filter(Boolean).length

  function clearFilters() {
    updateParams({ orderId: null, status: null, refundStatus: null, from: null, to: null, page: null })
  }

  return (
    <div className="space-y-4">
      <PageHeader
        icon="card"
        title="Payments"
        description="Razorpay payments of every order, newest first, with their attempts and refunds."
        actions={
          <ExportButton
            path="/admin/payments/export"
            label="payments"
            params={{ status, refundStatus, orderId: orderId || undefined, from, to }}
            total={payments.data?.totalElements}
            sort={sort}
            sortable={EXPORT_SORTS}
          />
        }
      />

      <ReconciliationSummary />

      <FilterBar activeCount={activeFilters} onClear={clearFilters}>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <TextField
            label="Order ID"
            type="search"
            value={orderIdText}
            placeholder="Paste an order ID"
            autoComplete="off"
            onChange={(event) => updateParams({ orderId: event.target.value, page: null }, { replace: true })}
          />
          <SelectField
            label="Status"
            value={status ?? ''}
            onChange={(event) => updateParams({ status: event.target.value, page: null })}
          >
            <option value="">All statuses</option>
            {PAYMENT_STATUSES.map((value) => (
              <option key={value} value={value}>
                {PAYMENT_STATUS_LABELS[value]}
              </option>
            ))}
          </SelectField>
          <SelectField
            label="Refunds"
            value={refundStatus ?? ''}
            onChange={(event) => updateParams({ refundStatus: event.target.value, page: null })}
          >
            <option value="">Any refund status</option>
            {REFUND_STATUSES.map((value) => (
              <option key={value} value={value}>
                {REFUND_STATUS_LABELS[value]}
              </option>
            ))}
          </SelectField>
          <TextField
            label="From"
            type="date"
            value={from ?? ''}
            max={to}
            onChange={(event) => updateParams({ from: event.target.value, page: null })}
          />
          <TextField
            label="To"
            type="date"
            value={to ?? ''}
            min={from}
            onChange={(event) => updateParams({ to: event.target.value, page: null })}
          />
        </div>
      </FilterBar>

      {payments.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading payments" />
        </div>
      ) : payments.isError ? (
        <ErrorState
          title="Could not load payments"
          message={payments.error.message}
          correlationId={payments.error instanceof ApiError ? payments.error.correlationId : null}
          onRetry={() => void payments.refetch()}
        />
      ) : (
        <div>
          <DataTable
            caption="Payments"
            columns={columns(setSelected)}
            rows={payments.data.content}
            getRowKey={(payment) => payment.id}
            sort={sort}
            onSortChange={setSort}
            fetching={payments.isFetching && payments.isPlaceholderData}
            empty={
              <EmptyState
                title="No payments found"
                description={filtered ? 'Try different filters.' : 'Payments appear here once orders wait for payment.'}
              />
            }
          />
          <Pagination
            page={payments.data.page}
            size={payments.data.size}
            totalElements={payments.data.totalElements}
            totalPages={payments.data.totalPages}
            disabled={payments.isFetching}
            onPageChange={(next) => updateParams({ page: pageToParam(next) })}
          />
        </div>
      )}

      {selected && <AdminPaymentDrawer id={selected.id} initial={selected} onClose={() => setSelected(null)} />}
    </div>
  )
}
