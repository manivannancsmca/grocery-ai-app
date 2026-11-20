import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, SelectField, Spinner, TextareaField, TextField } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { cn } from '../../shared/utils/cn'
import { formatCount } from '../../shared/utils/format'
import {
  ADJUSTMENT_REASON_LABELS,
  ADJUSTMENT_REASONS,
  ADJUSTMENT_TYPE_LABELS,
  ADJUSTMENT_TYPES,
  type AdjustmentType,
  STOCK_LIMITS,
  type StockView,
} from './api'
import { stockRuleMessage, units, vendorProductErrorMessage } from './errors'
import { useAdjustStock } from './hooks'
import {
  type AdjustmentFormValues,
  adjustmentFormToRequest,
  adjustmentSchema,
  DEFAULT_REASON,
  EMPTY_ADJUSTMENT_FORM,
  previewAdjustment,
} from './schema'

interface AdjustStockDialogProps {
  /** The row being adjusted; null closes the dialog. */
  stock: StockView | null
  onClose: () => void
}

/** Increase, decrease or recount the stock of one product, with a preview of the result. */
export function AdjustStockDialog({ stock, onClose }: AdjustStockDialogProps) {
  return (
    <Dialog
      open={stock !== null}
      onClose={onClose}
      title={stock ? `Adjust stock of ${stock.productName}` : 'Adjust stock'}
      description={stock ? `${stock.unitLabel} · stock is counted in sellable units` : undefined}
    >
      {stock && <AdjustStockForm stock={stock} onClose={onClose} />}
    </Dialog>
  )
}

const QUANTITY_LABELS: Record<AdjustmentType, string> = {
  INCREASE: 'Units to add',
  DECREASE: 'Units to remove',
  SET: 'Counted stock on hand',
}

function AdjustStockForm({ stock, onClose }: { stock: StockView; onClose: () => void }) {
  const toast = useToast()
  const adjust = useAdjustStock()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    control,
    handleSubmit,
    setError,
    setValue,
    formState: { errors, isSubmitting, dirtyFields },
  } = useForm<AdjustmentFormValues>({ resolver: zodResolver(adjustmentSchema), defaultValues: EMPTY_ADJUSTMENT_FORM })
  const type = useWatch({ control, name: 'type' })
  const quantity = useWatch({ control, name: 'quantity' })
  const preview = previewAdjustment(stock, type, quantity)

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const saved = await adjust.mutateAsync({ productId: stock.productId, body: adjustmentFormToRequest(values) })
      toast.success(`${stock.productName} now has ${units(saved.onHand)} on hand.`)
      onClose()
    } catch (error) {
      // The stock may have changed since the list loaded, so the server's numbers win over the row on screen.
      const ruleMessage = stockRuleMessage(error, stock)
      if (ruleMessage) {
        setError('quantity', { type: 'server', message: ruleMessage }, { shouldFocus: true })
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, EMPTY_ADJUSTMENT_FORM)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.join(' ') || vendorProductErrorMessage(error))
    }
  })

  const typeField = register('type', {
    // Suggest the usual reason for the chosen type until the vendor picks one.
    onChange: (event: { target: { value: AdjustmentType } }) => {
      if (!dirtyFields.reason) setValue('reason', DEFAULT_REASON[event.target.value])
    },
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <dl className="grid grid-cols-3 gap-2 rounded-lg bg-slate-50 px-3 py-2 text-center text-sm">
        <div>
          <dt className="text-xs text-slate-500">On hand</dt>
          <dd className="font-semibold text-slate-900 tabular-nums">{formatCount(stock.onHand)}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Reserved</dt>
          <dd className="font-semibold text-slate-900 tabular-nums">{formatCount(stock.reserved)}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Available</dt>
          <dd className="font-semibold text-slate-900 tabular-nums">{formatCount(stock.available)}</dd>
        </div>
      </dl>

      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField label="Adjustment type" required error={errors.type?.message} {...typeField}>
          {ADJUSTMENT_TYPES.map((value) => (
            <option key={value} value={value}>
              {ADJUSTMENT_TYPE_LABELS[value]}
            </option>
          ))}
        </SelectField>
        <TextField
          label={QUANTITY_LABELS[type]}
          inputMode="numeric"
          required
          error={errors.quantity?.message}
          hint={type === 'SET' ? 'The number of units you counted on the shelf.' : undefined}
          {...register('quantity')}
        />
        <SelectField label="Reason" required error={errors.reason?.message} containerClassName="sm:col-span-2" {...register('reason')}>
          {ADJUSTMENT_REASONS.map((value) => (
            <option key={value} value={value}>
              {ADJUSTMENT_REASON_LABELS[value]}
            </option>
          ))}
        </SelectField>
        <TextareaField
          label="Note"
          rows={2}
          maxLength={STOCK_LIMITS.note}
          hint="Optional. Shown in the stock history."
          error={errors.note?.message}
          containerClassName="sm:col-span-2"
          {...register('note')}
        />
      </div>

      <p
        role="status"
        className={cn(
          'rounded-lg px-3 py-2 text-sm',
          preview?.problem ? 'bg-amber-50 text-amber-800' : 'bg-emerald-50 text-emerald-800',
          !preview && 'bg-slate-50 text-slate-500',
        )}
      >
        {!preview ? (
          'Enter a quantity to see the stock after saving.'
        ) : preview.problem === 'BELOW_RESERVED' ? (
          stock.reserved > 0 ? (
            <>
              This would leave <strong>{formatCount(preview.onHand)} on hand</strong>, but {units(stock.reserved)}{' '}
              {stock.reserved === 1 ? 'is' : 'are'} reserved for orders. Stock cannot go below what is reserved.
            </>
          ) : (
            <>
              Only {units(stock.onHand)} {stock.onHand === 1 ? 'is' : 'are'} on hand, so this many cannot be removed.
            </>
          )
        ) : preview.problem === 'ABOVE_LIMIT' ? (
          <>
            Stock after saving would be <strong>{formatCount(preview.onHand)}</strong>, above the limit of{' '}
            {formatCount(STOCK_LIMITS.onHand)} units.
          </>
        ) : (
          <>
            Stock after saving: <strong>{formatCount(preview.onHand)} on hand</strong>, {formatCount(preview.available)} available.
          </>
        )}
      </p>

      <div className="flex flex-col-reverse gap-2 border-t border-slate-100 pt-4 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Save adjustment
        </Button>
      </div>
    </form>
  )
}
