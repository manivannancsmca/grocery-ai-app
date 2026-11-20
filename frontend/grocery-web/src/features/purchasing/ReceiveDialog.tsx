import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useFieldArray, useForm } from 'react-hook-form'
import { errorMessage, hasErrorCode, problemString } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, Input, Spinner, TABLE_HEAD_CLASS, TABLE_HEAD_ROW_CLASS, TextareaField } from '../../shared/components/ui'
import { fieldErrorMessage } from '../../shared/forms/serverErrors'
import { formatCount } from '../../shared/utils/format'
import { openQuantity, PURCHASING_ERRORS, PURCHASING_LIMITS, type PurchaseOrderView, purchasingKeys } from './api'
import { useReceivePurchaseOrder } from './hooks'
import { newIdempotencyKey, transitionConflictMessage } from './labels'
import { receiveSchema, type ReceiveFormValues } from './schema'

interface ReceiveDialogProps {
  open: boolean
  order: PurchaseOrderView
  onClose: () => void
}

/** A version of the order's receiving state: a new receipt (here or elsewhere) starts a fresh form and key. */
function receiptVersion(order: PurchaseOrderView): string {
  return `${order.id}:${order.status}:${order.lines.map((line) => line.receivedQuantity).join(',')}`
}

/**
 * Record goods received against a SENT or PARTIALLY_RECEIVED purchase order: per open line the units that came
 * now (partial receipts are fine). One `Idempotency-Key` per submission, so a retried request never adds the stock
 * twice. 422 RECEIPT_EXCEEDS_ORDERED (more than is open — e.g. received elsewhere meanwhile) refreshes the order.
 */
export function ReceiveDialog({ open, order, onClose }: ReceiveDialogProps) {
  const [serverError, setServerError] = useState<string | null>(null)
  const close = () => {
    setServerError(null)
    onClose()
  }
  return (
    <Dialog open={open} onClose={close} title={`Receive goods — ${order.number}`} description="Enter what arrived now. Stock goes up by these quantities." size="lg">
      <ReceiveForm key={receiptVersion(order)} order={order} serverError={serverError} onServerError={setServerError} onClose={close} />
    </Dialog>
  )
}

interface ReceiveFormProps {
  order: PurchaseOrderView
  serverError: string | null
  onServerError: (message: string | null) => void
  onClose: () => void
}

function ReceiveForm({ order, serverError, onServerError, onClose }: ReceiveFormProps) {
  const toast = useToast()
  const queryClient = useQueryClient()
  const receive = useReceivePurchaseOrder()
  const [idempotencyKey] = useState(newIdempotencyKey)
  const openLines = order.lines.filter((line) => openQuantity(line) > 0)
  const [openByProduct] = useState(() => Object.fromEntries(openLines.map((line) => [line.productId, openQuantity(line)])))
  const [schema] = useState(() => receiveSchema(openByProduct))
  const {
    control,
    register,
    handleSubmit,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<ReceiveFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { note: '', lines: openLines.map((line) => ({ productId: line.productId, quantity: '' })) },
  })
  const { fields } = useFieldArray({ control, name: 'lines' })

  function receiveEverything() {
    fields.forEach((field, index) => setValue(`lines.${index}.quantity`, String(openByProduct[field.productId] ?? 0), { shouldValidate: true }))
  }

  const submit = handleSubmit(async (values) => {
    onServerError(null)
    const lines = values.lines
      .filter((line) => line.quantity.trim() !== '' && Number(line.quantity) > 0)
      .map((line) => ({ productId: line.productId, quantity: Number(line.quantity) }))
    const note = values.note.trim()
    try {
      const saved = await receive.mutateAsync({ id: order.id, body: { lines, ...(note ? { note } : {}) }, idempotencyKey })
      const units = lines.reduce((sum, line) => sum + line.quantity, 0)
      toast.success('Goods received', {
        description: `${formatCount(units)} units added to stock. ${saved.status === 'RECEIVED' ? 'The order is complete.' : ''}`.trim(),
      })
      onClose()
    } catch (error) {
      if (hasErrorCode(error, PURCHASING_ERRORS.receiptExceedsOrdered)) {
        const productId = problemString(error, 'productId')
        const product = productId ? order.lines.find((line) => line.productId === productId)?.productName : null
        onServerError(
          `${product ? `${product}: m` : 'M'}ore than what is still open on this order — goods may have been received in the meantime. ` +
            'The open quantities have been refreshed; check them and try again.',
        )
        await queryClient.invalidateQueries({ queryKey: purchasingKeys.order(order.id) })
        return
      }
      if (hasErrorCode(error, PURCHASING_ERRORS.invalidTransition)) {
        onServerError(transitionConflictMessage(error))
        await queryClient.invalidateQueries({ queryKey: purchasingKeys.order(order.id) })
        return
      }
      onServerError(errorMessage(error))
    }
  })

  const linesError = fieldErrorMessage(errors.lines?.root) ?? (typeof errors.lines?.message === 'string' ? errors.lines.message : undefined)

  if (openLines.length === 0) {
    return (
      <div className="space-y-4">
        {serverError && (
          <Alert tone="warning" role="alert">
            {serverError}
          </Alert>
        )}
        <p className="text-sm text-slate-600">Everything on this order has been received.</p>
        <div className="flex justify-end">
          <Button variant="secondary" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    )
  }

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      {serverError && (
        <Alert tone="danger" role="alert">
          {serverError}
        </Alert>
      )}
      <div className="flex justify-end">
        <Button size="sm" variant="secondary" onClick={receiveEverything}>
          Fill in everything open
        </Button>
      </div>
      <div className="overflow-x-auto rounded-xl border border-slate-200">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <caption className="sr-only">Quantities received</caption>
          <thead className={TABLE_HEAD_CLASS}>
            <tr className={TABLE_HEAD_ROW_CLASS}>
              <th scope="col" className="px-3 py-2">
                Product
              </th>
              <th scope="col" className="px-3 py-2 text-right">
                Open
              </th>
              <th scope="col" className="px-3 py-2">
                Received now
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {fields.map((field, index) => {
              const line = openLines[index]
              const error = errors.lines?.[index]?.quantity?.message
              const inputId = `receive-${field.id}`
              return (
                <tr key={field.id}>
                  <th scope="row" className="px-3 py-2 text-left font-medium text-slate-900">
                    <label htmlFor={inputId}>{line?.productName}</label>
                  </th>
                  <td className="px-3 py-2 text-right tabular-nums">{formatCount(openByProduct[field.productId] ?? 0)}</td>
                  <td className="px-3 py-2">
                    <Input
                      id={inputId}
                      inputMode="numeric"
                      placeholder="0"
                      className="w-28"
                      invalid={Boolean(error)}
                      aria-describedby={error ? `${inputId}-error` : undefined}
                      {...register(`lines.${index}.quantity`)}
                    />
                    {error && (
                      <p id={`${inputId}-error`} className="mt-1 text-xs font-medium text-red-600">
                        {error}
                      </p>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      {linesError && (
        <p role="alert" className="text-sm font-medium text-red-600">
          {linesError}
        </p>
      )}
      <TextareaField label="Note (optional)" rows={2} maxLength={PURCHASING_LIMITS.receiptNote} error={errors.note?.message} {...register('note')} />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Record receipt
        </Button>
      </div>
    </form>
  )
}
