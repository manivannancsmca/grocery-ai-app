import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { errorMessage } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, SelectField, Spinner, TextareaField } from '../../shared/components/ui'
import { emptyToNull } from '../../shared/forms/validation'
import { STORE_ORDER_LIMITS, STORE_REJECT_REASONS, type StoreKind, type StoreOrderView, type StoreRejectReason } from './api'
import { storeOrderActionError } from './errors'
import { useStoreOrderAction } from './hooks'
import { STORE_REJECT_REASON_LABELS } from './labels'

const rejectSchema = z
  .object({
    reason: z.string(),
    note: z.string().trim().max(STORE_ORDER_LIMITS.rejectNote, `Use at most ${STORE_ORDER_LIMITS.rejectNote} characters.`),
  })
  .superRefine((values, ctx) => {
    if (!(STORE_REJECT_REASONS as readonly string[]).includes(values.reason)) {
      ctx.addIssue({ code: 'custom', path: ['reason'], message: 'Choose a reason.' })
    }
    if (values.reason === 'OTHER' && values.note.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['note'], message: 'Add a note: the customer is told why when the reason is “Other”.' })
    }
  })

type RejectFormValues = z.infer<typeof rejectSchema>

const EMPTY_FORM: RejectFormValues = { reason: '', note: '' }

interface RejectStoreOrderDialogProps {
  kind: StoreKind
  /** The order being rejected; null closes the dialog. */
  order: StoreOrderView | null
  onClose: () => void
}

/** "Reject order?" with a reason (required) and a note for the customer (required for OTHER, ≤ 300). */
export function RejectStoreOrderDialog({ kind, order, onClose }: RejectStoreOrderDialogProps) {
  return (
    <Dialog
      open={order !== null}
      onClose={onClose}
      size="sm"
      title={order ? `Reject order ${order.orderNumber}?` : 'Reject order'}
      description="The customer is told why and refunded in full. This cannot be undone."
    >
      {order && <RejectForm kind={kind} order={order} onClose={onClose} />}
    </Dialog>
  )
}

function RejectForm({ kind, order, onClose }: { kind: StoreKind; order: StoreOrderView; onClose: () => void }) {
  const toast = useToast()
  const action = useStoreOrderAction(kind)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<RejectFormValues>({ resolver: zodResolver(rejectSchema), defaultValues: EMPTY_FORM })
  const reason = useWatch({ control, name: 'reason' })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    const note = emptyToNull(values.note)
    try {
      await action.mutateAsync({
        orderId: order.orderId,
        action: { type: 'reject', body: { reason: values.reason as StoreRejectReason, ...(note ? { note } : {}) } },
      })
      toast.success(`Order ${order.orderNumber} rejected. The customer is refunded in full.`)
      onClose()
    } catch (error) {
      const failure = storeOrderActionError(kind, order, error)
      if (failure.conflict) {
        onClose()
        toast.show(failure.tone, failure.message, { description: failure.description })
        return
      }
      setFormError(`Could not reject the order: ${errorMessage(error)}`)
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      <SelectField label="Reason" required error={errors.reason?.message} {...register('reason')}>
        <option value="">Choose a reason…</option>
        {STORE_REJECT_REASONS.map((value) => (
          <option key={value} value={value}>
            {STORE_REJECT_REASON_LABELS[value]}
          </option>
        ))}
      </SelectField>
      <TextareaField
        label={
          reason === 'OTHER' ? (
            'Note for the customer'
          ) : (
            <>
              Note for the customer <span className="font-normal text-slate-500">(optional)</span>
            </>
          )
        }
        required={reason === 'OTHER'}
        rows={3}
        maxLength={STORE_ORDER_LIMITS.rejectNote}
        placeholder="e.g. Paneer is finished for today"
        error={errors.note?.message}
        {...register('note')}
      />
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Keep order
        </Button>
        <Button type="submit" variant="danger" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Reject order
        </Button>
      </div>
    </form>
  )
}
