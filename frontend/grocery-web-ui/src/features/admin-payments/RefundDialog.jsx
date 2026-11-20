import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { newCorrelationId } from '../../shared/api/correlation'
import { ApiError, errorMessage, hasErrorCode, problemNumber } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, Spinner, TextareaField, TextField } from '../../shared/components/ui'
import { decimalField, textField } from '../../shared/forms/validation'
import { formatMoney } from '../../shared/utils/format'
import { REFUND_ERRORS, REFUND_LIMITS, refundableAmount } from './api'
import { useCreateRefund } from './hooks'

/** @typedef {import('../payments/api').PaymentView} PaymentView */

/** Largest amount the form accepts before the refundable check (keeps typos like 1e9 out). */
const MAX_AMOUNT = 10_000_000

/** Shape checks; "not more than refundable" is checked on submit, since the refundable amount can change. */
const refundSchema = z.object({
  amount: decimalField({ min: 0, exclusiveMin: true, max: MAX_AMOUNT, decimals: 2 }),
  note: textField({
    required: true,
    requiredMessage: 'Enter a note. It is stored with the refund.',
    max: REFUND_LIMITS.note,
  }),
})

/** @typedef {z.infer<typeof refundSchema>} RefundValues */

/**
 * @typedef {object} RefundDialogProps
 * @property {PaymentView | null} payment The captured payment to refund; null = closed.
 * @property {() => void} onClose
 */

/**
 * Admin refund (SUPER_ADMIN / FINANCE_ADMIN): optional amount (empty = everything left), required note.
 * @param {RefundDialogProps} props
 */
export function RefundDialog({ payment, onClose }) {
  return (
    <Dialog
      open={payment !== null}
      onClose={onClose}
      size="sm"
      title={payment ? `Refund order ${payment.orderNumber}` : 'Refund'}
      description="The money goes back to the customer's original payment method. The order status does not change."
    >
      {payment && <RefundForm key={payment.id} payment={payment} onClose={onClose} />}
    </Dialog>
  )
}

/** @param {{ payment: PaymentView, onClose: () => void }} props */
function RefundForm({ payment, onClose }) {
  const toast = useToast()
  const refund = useCreateRefund()
  const [refundable, setRefundable] = useState(() => refundableAmount(payment))
  const [submitError, setSubmitError] = useState(null)
  // One Idempotency-Key per submission; re-submitting the same values after a failure that may have
  // reached payment-service (network error, 5xx) reuses it, so the refund cannot happen twice.
  const [attempt, setAttempt] = useState(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(refundSchema), defaultValues: { amount: '', note: '' } })

  const submit = handleSubmit(async ({ amount, note }) => {
    setSubmitError(null)
    const trimmedAmount = amount.trim()
    if (trimmedAmount && Number(trimmedAmount) > refundable) {
      setError('amount', { message: `At most ${formatMoney(refundable, payment.currency)} can be refunded.` })
      return
    }
    const body = {
      orderId: payment.orderId,
      ...(trimmedAmount ? { amount: Number(trimmedAmount) } : {}),
      note: note.trim(),
    }
    const fingerprint = `${body.amount ?? 'all'}|${body.note}`
    const current = attempt && attempt.fingerprint === fingerprint ? attempt : { key: newCorrelationId(), fingerprint }
    setAttempt(current)

    try {
      const created = await refund.mutateAsync({ body, idempotencyKey: current.key })
      setAttempt(null)
      onClose()
      toast.success(
        `Refund of ${formatMoney(created.amount, payment.currency)} started for order ${payment.orderNumber}.`,
      )
    } catch (error) {
      const retryable =
        !(error instanceof ApiError) || error.kind !== 'http' || (error.status !== null && error.status >= 500)
      // A definitive refusal stored nothing: the next submission is a new attempt with a new key.
      if (!retryable) setAttempt(null)
      if (hasErrorCode(error, REFUND_ERRORS.exceedsCaptured)) {
        const left = problemNumber(error, 'refundable')
        if (left !== null) setRefundable(left)
        setError('amount', {
          message:
            left !== null
              ? `At most ${formatMoney(left, payment.currency)} can be refunded.`
              : 'This is more than can still be refunded.',
        })
        setSubmitError(
          left !== null
            ? `The amount is more than what is left to refund. Refundable now: ${formatMoney(left, payment.currency)}.`
            : 'The amount is more than what is left to refund.',
        )
      } else if (hasErrorCode(error, REFUND_ERRORS.notCaptured)) {
        setSubmitError('This payment has not been captured, so there is nothing to refund.')
      } else if (hasErrorCode(error, REFUND_ERRORS.notFound)) {
        setSubmitError('No payment was found for this order.')
      } else if (retryable) {
        setSubmitError(
          `The refund could not be confirmed: ${errorMessage(error)} Submitting the same values again is safe — it will not refund twice.`,
        )
      } else {
        setSubmitError(errorMessage(error))
      }
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      <dl className="grid grid-cols-3 gap-2 rounded-lg bg-slate-50 p-3 text-sm">
        <div>
          <dt className="text-xs text-slate-500">Captured</dt>
          <dd className="font-semibold text-slate-900 tabular-nums">{formatMoney(payment.amount, payment.currency)}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Refunded</dt>
          <dd className="font-semibold text-slate-900 tabular-nums">
            {formatMoney(payment.refundedAmount, payment.currency)}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Refundable</dt>
          <dd className="font-semibold text-emerald-700 tabular-nums">{formatMoney(refundable, payment.currency)}</dd>
        </div>
      </dl>
      <TextField
        label={
          <>
            Amount (₹) <span className="font-normal text-slate-500">(optional)</span>
          </>
        }
        inputMode="decimal"
        autoComplete="off"
        placeholder={refundable.toFixed(2)}
        hint={`Leave empty to refund everything not yet refunded (${formatMoney(refundable, payment.currency)}).`}
        error={errors.amount?.message}
        {...register('amount')}
      />
      <TextareaField
        label="Note"
        required
        rows={3}
        maxLength={REFUND_LIMITS.note}
        placeholder="e.g. Damaged item"
        error={errors.note?.message}
        {...register('note')}
      />
      {submitError && (
        <Alert tone="danger" role="alert">
          {submitError}
        </Alert>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting || refundable <= 0}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Refund
        </Button>
      </div>
    </form>
  )
}
