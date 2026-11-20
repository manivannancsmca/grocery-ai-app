import { useState } from 'react'
import { ApiError } from '../../shared/api/problemDetail'
import {
  Badge,
  DataTable,
  DescriptionList,
  Dialog,
  EmptyState,
  ErrorState,
  Pagination,
  Spinner,
} from '../../shared/components/ui'
import { formatCount, formatDateTime, formatMoney } from '../../shared/utils/format'
import { useCouponUsage } from './hooks'
import { couponBenefit, REDEMPTION_STATUS_LABELS } from './labels'

/** @typedef {import('../../shared/components/ui').Column} Column */
/** @typedef {import('./api').CouponOwner} CouponOwner */
/** @typedef {import('./api').CouponRedemptionView} CouponRedemptionView */
/** @typedef {import('./api').CouponView} CouponView */

/** @type {Column<CouponRedemptionView>[]} */
const columns = [
  {
    key: 'order',
    header: 'Order',
    cell: (use) => <span className="font-mono text-slate-900">{use.orderNumber ?? use.orderId}</span>,
  },
  {
    key: 'discount',
    header: 'Discount',
    className: 'text-right',
    cell: (use) => (
      <span className="whitespace-nowrap tabular-nums">
        {formatMoney(use.discount)}
        {use.deliveryFeeDiscount > 0 && (
          <span className="block text-xs text-slate-500">+ {formatMoney(use.deliveryFeeDiscount)} delivery</span>
        )}
      </span>
    ),
  },
  {
    key: 'status',
    header: 'Status',
    cell: (use) => (
      <Badge tone={use.status === 'CONFIRMED' ? 'green' : use.status === 'RESERVED' ? 'amber' : 'gray'}>
        {REDEMPTION_STATUS_LABELS[use.status] ?? use.status}
      </Badge>
    ),
  },
  { key: 'when', header: 'When', hideOnMobile: true, cell: (use) => formatDateTime(use.createdAt) },
]

/**
 * How a coupon has been used: totals and one row per order (reserved at placement, confirmed, or released).
 * @param {{ owner: CouponOwner, coupon: CouponView, onClose: () => void }} props
 */
export function CouponUsageDrawer({ owner, coupon, onClose }) {
  const [page, setPage] = useState(0)
  const usage = useCouponUsage(owner, coupon.id, page)
  const usedCount = usage.data?.usedCount ?? coupon.usedCount
  const discountGiven = usage.data?.discountGiven ?? coupon.discountGiven

  return (
    <Dialog
      open
      variant="drawer"
      icon="ticket"
      onClose={onClose}
      title={`Usage of ${coupon.code}`}
      description={`${coupon.name} · ${couponBenefit(coupon)}`}
    >
      <div className="space-y-6">
        <DescriptionList
          items={[
            {
              label: 'Uses',
              value: `${formatCount(usedCount)}${coupon.totalUsageLimit !== null ? ` of ${formatCount(coupon.totalUsageLimit)}` : ''}`,
            },
            { label: 'Discount given', value: formatMoney(discountGiven) },
            { label: 'Per customer', value: formatCount(coupon.perCustomerLimit) },
            { label: 'Funded by', value: coupon.scope === 'STORE' ? 'The store' : 'The platform' },
          ]}
        />
        {usage.isPending ? (
          <div className="flex justify-center py-8">
            <Spinner label="Loading the uses" />
          </div>
        ) : usage.isError ? (
          <ErrorState
            title="Could not load the uses"
            message={usage.error.message}
            correlationId={usage.error instanceof ApiError ? usage.error.correlationId : null}
            onRetry={() => void usage.refetch()}
          />
        ) : (
          <div>
            <DataTable
              caption={`Uses of ${coupon.code}`}
              columns={columns}
              rows={usage.data.redemptions.content}
              getRowKey={(use) => use.id ?? use.orderId}
              empty={<EmptyState title="Not used yet" description="Orders placed with this coupon are listed here." />}
            />
            <Pagination
              page={usage.data.redemptions.page}
              size={usage.data.redemptions.size}
              totalElements={usage.data.redemptions.totalElements}
              totalPages={usage.data.redemptions.totalPages}
              disabled={usage.isFetching}
              onPageChange={setPage}
            />
          </div>
        )}
      </div>
    </Dialog>
  )
}
