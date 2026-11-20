import { useState } from 'react'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { ConfirmDialog, TextareaField } from '../../shared/components/ui'
import { emptyToNull } from '../../shared/forms/validation'
import { formatPrice } from '../../shared/utils/format'
import { ORDER_ERRORS, ORDER_LIMITS, type OrderView } from './api'
import { useCancelOrder } from './hooks'

interface CancelOrderDialogProps {
  order: OrderView
  open: boolean
  onClose: () => void
}

/** "Cancel order?" with an optional reason. A paid order is refunded in full. */
export function CancelOrderDialog({ order, open, onClose }: CancelOrderDialogProps) {
  const toast = useToast()
  const cancel = useCancelOrder(order.id)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  // A paid order (also one already partly refunded) gets back whatever has not been refunded yet.
  const paid = order.payment.status === 'PAID' || order.payment.status === 'PARTIALLY_REFUNDED'
  const refundDue = Math.max(0, Math.round((order.total - order.payment.refundedAmount) * 100) / 100)
  const tooLong = reason.trim().length > ORDER_LIMITS.cancelReason

  function close() {
    setReason('')
    setError(null)
    onClose()
  }

  function confirm() {
    if (tooLong) return
    setError(null)
    cancel.mutate(emptyToNull(reason), {
      onSuccess: () => {
        close()
        toast.success(paid ? `Order cancelled. Your refund of ${formatPrice(refundDue)} has started.` : 'Order cancelled.')
      },
      onError: (failure) => {
        if (hasErrorCode(failure, ORDER_ERRORS.notCancellable)) {
          close()
          toast.error('This order can no longer be cancelled. It has been refreshed.')
          return
        }
        setError(`Could not cancel the order: ${errorMessage(failure)}`)
      },
    })
  }

  return (
    <ConfirmDialog
      open={open}
      title={`Cancel order ${order.orderNumber}?`}
      confirmLabel="Cancel order"
      cancelLabel="Keep order"
      tone="danger"
      pending={cancel.isPending}
      error={error}
      onConfirm={confirm}
      onClose={close}
    >
      <p>
        {paid
          ? `${formatPrice(refundDue)} is refunded to your original payment method, usually within 5–7 working days.`
          : 'You have not been charged for this order.'}
      </p>
      <TextareaField
        label={
          <>
            Reason <span className="font-normal text-slate-500">(optional)</span>
          </>
        }
        rows={3}
        maxLength={ORDER_LIMITS.cancelReason}
        placeholder="e.g. Ordered by mistake"
        value={reason}
        error={tooLong ? `Use at most ${ORDER_LIMITS.cancelReason} characters.` : undefined}
        onChange={(event) => setReason(event.target.value)}
      />
    </ConfirmDialog>
  )
}
