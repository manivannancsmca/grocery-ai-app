import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, SelectField, Spinner, TextareaField } from '../../shared/components/ui'
import { emptyToNull } from '../../shared/forms/validation'
import { DELIVERY_ERRORS, DELIVERY_FAIL_REASONS, DELIVERY_LIMITS, type DeliveryFailReason, type DeliveryView } from './api'
import { deliveryActionError } from './errors'
import { useDeliveryAction } from './hooks'
import { FAIL_REASON_LABELS } from './labels'

const failSchema = z
  .object({
    reason: z.string(),
    note: z.string().trim().max(DELIVERY_LIMITS.failNote, `Use at most ${DELIVERY_LIMITS.failNote} characters.`),
  })
  .superRefine((values, ctx) => {
    if (!(DELIVERY_FAIL_REASONS as readonly string[]).includes(values.reason)) {
      ctx.addIssue({ code: 'custom', path: ['reason'], message: 'Choose a reason.' })
    }
    if (values.reason === 'OTHER' && values.note.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['note'], message: 'Add a note: say what happened when the reason is “Other”.' })
    }
  })

type FailFormValues = z.infer<typeof failSchema>

interface FailDeliveryDialogProps {
  /** The delivery that could not be completed; null closes the dialog. */
  delivery: DeliveryView | null
  onClose: () => void
}

/** "Couldn’t deliver": a reason (required) and a note (required for OTHER, ≤ 300) → `POST …/fail`. */
export function FailDeliveryDialog({ delivery, onClose }: FailDeliveryDialogProps) {
  return (
    <Dialog
      open={delivery !== null}
      onClose={onClose}
      size="sm"
      title={delivery ? `Couldn’t deliver order ${delivery.orderNumber}?` : 'Couldn’t deliver'}
      description="Support contacts the customer about a refund. Keep the order with you until support tells you what to do with it."
    >
      {delivery && <FailForm delivery={delivery} onClose={onClose} />}
    </Dialog>
  )
}

function FailForm({ delivery, onClose }: { delivery: DeliveryView; onClose: () => void }) {
  const toast = useToast()
  const action = useDeliveryAction()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FailFormValues>({ resolver: zodResolver(failSchema), defaultValues: { reason: '', note: '' } })
  const reason = useWatch({ control, name: 'reason' })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    const note = emptyToNull(values.note)
    try {
      await action.mutateAsync({
        deliveryId: delivery.deliveryId,
        action: { type: 'fail', body: { reason: values.reason as DeliveryFailReason, ...(note ? { note } : {}) } },
      })
      toast.warning(`Order ${delivery.orderNumber} marked as not delivered`, {
        description: 'Our support team will contact the customer about a refund.',
      })
      onClose()
    } catch (error) {
      if (hasErrorCode(error, DELIVERY_ERRORS.invalidTransition, DELIVERY_ERRORS.deliveryNotFound)) {
        const failure = deliveryActionError(delivery.orderNumber, error)
        onClose()
        toast.show(failure.tone, failure.message, { description: failure.description })
        return
      }
      setFormError(`Could not save this: ${errorMessage(error)}`)
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      <SelectField label="What happened?" required error={errors.reason?.message} {...register('reason')}>
        <option value="">Choose a reason…</option>
        {DELIVERY_FAIL_REASONS.map((value) => (
          <option key={value} value={value}>
            {FAIL_REASON_LABELS[value]}
          </option>
        ))}
      </SelectField>
      <TextareaField
        label={
          reason === 'OTHER' ? (
            'Note'
          ) : (
            <>
              Note <span className="font-normal text-slate-500">(optional)</span>
            </>
          )
        }
        required={reason === 'OTHER'}
        rows={3}
        maxLength={DELIVERY_LIMITS.failNote}
        placeholder="e.g. Gate locked, nobody answered the bell or the phone"
        error={errors.note?.message}
        {...register('note')}
      />
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" className="h-12 sm:h-10" onClick={onClose} disabled={isSubmitting}>
          Back
        </Button>
        <Button type="submit" variant="danger" className="h-12 sm:h-10" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Mark as not delivered
        </Button>
      </div>
    </form>
  )
}
