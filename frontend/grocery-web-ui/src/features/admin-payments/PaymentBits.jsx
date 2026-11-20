import { Badge, DescriptionList } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { formatDateTime, formatMoney, humanizeToken } from '../../shared/utils/format'
import { refundableAmount } from './api'
import {
  PAYMENT_STATUS_LABELS,
  PAYMENT_STATUS_TONES,
  paymentMethodLabel,
  REFUND_REASON_LABELS,
  REFUND_STATE_TONES,
  REFUND_STATUS_LABELS,
  REFUND_STATUS_TONES,
} from './labels'

/** @typedef {import('../payments/api').PaymentRefundStatus} PaymentRefundStatus */
/** @typedef {import('../payments/api').PaymentStatus} PaymentStatus */
/** @typedef {import('../payments/api').PaymentView} PaymentView */

/** @param {{ status: PaymentStatus }} props */
export function PaymentStatusBadge({ status }) {
  return (
    <Badge tone={PAYMENT_STATUS_TONES[status]} dot>
      {PAYMENT_STATUS_LABELS[status] ?? status}
    </Badge>
  )
}

/**
 * "No refund" stays a plain grey pill; a refund in progress or done gets a status dot so it stands out.
 * @param {{ status: PaymentRefundStatus }} props
 */
export function RefundStatusBadge({ status }) {
  return (
    <Badge tone={REFUND_STATUS_TONES[status]} dot={status !== 'NONE'}>
      {REFUND_STATUS_LABELS[status] ?? status}
    </Badge>
  )
}

/**
 * Amount, status, provider ids, refunds total and dates of one payment.
 * @param {{ payment: PaymentView }} props
 */
export function PaymentSummary({ payment }) {
  const refundable = refundableAmount(payment)
  return (
    <DescriptionList
      items={[
        { label: 'Amount', value: formatMoney(payment.amount, payment.currency) },
        { label: 'Status', value: <PaymentStatusBadge status={payment.status} /> },
        {
          label: 'Refunded',
          value: `${formatMoney(payment.refundedAmount, payment.currency)} · ${REFUND_STATUS_LABELS[payment.refundStatus] ?? payment.refundStatus}`,
        },
        {
          label: 'Still refundable',
          value: payment.status === 'CAPTURED' ? formatMoney(refundable, payment.currency) : null,
        },
        { label: 'Method', value: payment.method ? paymentMethodLabel(payment.method) : null },
        { label: 'Captured', value: payment.capturedAt ? formatDateTime(payment.capturedAt) : null },
        { label: 'Payment window ends', value: formatDateTime(payment.expiresAt) },
        { label: 'Created', value: formatDateTime(payment.createdAt) },
        { label: 'Razorpay order', value: <span className="font-mono text-xs">{payment.razorpayOrderId}</span> },
        {
          label: 'Razorpay payment',
          value: payment.razorpayPaymentId && <span className="font-mono text-xs">{payment.razorpayPaymentId}</span>,
        },
        { label: 'Payment ID', value: <span className="font-mono text-xs">{payment.id}</span>, wide: true },
      ]}
    />
  )
}

/**
 * Every attempt the customer made (declines included), oldest first as the API lists them.
 * @param {{ payment: PaymentView }} props
 */
export function PaymentAttempts({ payment }) {
  const attempts = payment.attempts ?? []
  if (attempts.length === 0) return <p className="text-sm text-slate-500">No payment attempts yet.</p>
  return (
    <ul aria-label="Payment attempts" className="divide-y divide-slate-100 text-sm">
      {attempts.map((attempt) => (
        <li key={`${attempt.razorpayPaymentId}-${attempt.at}`} className="py-2 first:pt-0 last:pb-0">
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone={attempt.status === 'FAILED' ? 'red' : attempt.status === 'CAPTURED' ? 'green' : 'gray'}>
              {humanizeToken(attempt.status)}
            </Badge>
            {attempt.method && <span className="text-slate-600">{paymentMethodLabel(attempt.method)}</span>}
            <span className="text-xs text-slate-500">{formatDateTime(attempt.at)}</span>
          </div>
          {attempt.errorDescription && (
            <p className="mt-1 text-slate-700">
              {attempt.errorDescription}
              {attempt.errorCode && (
                <span className="ml-1 font-mono text-xs text-slate-400">({attempt.errorCode})</span>
              )}
            </p>
          )}
          <p className="mt-0.5 font-mono text-xs text-slate-400">{attempt.razorpayPaymentId}</p>
        </li>
      ))}
    </ul>
  )
}

/** @param {{ payment: PaymentView }} props */
export function PaymentRefunds({ payment }) {
  if (payment.refunds.length === 0) return <p className="text-sm text-slate-500">No refunds.</p>
  return (
    <ul aria-label="Refunds" className="divide-y divide-slate-100 text-sm">
      {payment.refunds.map((refund) => (
        <li key={refund.id} className="py-2 first:pt-0 last:pb-0">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="font-semibold text-rose-700 tabular-nums">
              {formatMoney(refund.amount, payment.currency)}
            </span>
            <Badge tone={REFUND_STATE_TONES[refund.status] ?? 'gray'} dot>
              {humanizeToken(refund.status)}
            </Badge>
          </div>
          <p className="text-slate-600">
            {REFUND_REASON_LABELS[refund.reason] ?? refund.reason}
            {refund.note && <> · “{refund.note}”</>}
          </p>
          {refund.failureReason && (
            <p className="flex items-start gap-1.5 text-red-700">
              <Icon name="alertCircle" className="mt-0.5 size-4" />
              {refund.failureReason}
            </p>
          )}
          <p className="text-xs text-slate-500">
            {formatDateTime(refund.createdAt)}
            {refund.processedAt && ` · processed ${formatDateTime(refund.processedAt)}`}
            {refund.requestedBy && (
              <>
                {' '}
                · by <span className="font-mono">{refund.requestedBy}</span>
              </>
            )}
          </p>
        </li>
      ))}
    </ul>
  )
}
