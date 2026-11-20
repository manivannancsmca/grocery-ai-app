import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, Spinner, TextField } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { formatCount } from '../../shared/utils/format'
import { STOCK_LIMITS, type StockView } from './api'
import { vendorProductErrorMessage } from './errors'
import { useSetLowStockThreshold } from './hooks'
import { type ThresholdFormValues, thresholdSchema } from './schema'

interface ThresholdDialogProps {
  /** The row being edited; null closes the dialog. */
  stock: StockView | null
  onClose: () => void
}

/** Edit the level at which a product counts as "low stock" (0 turns the alert off). */
export function ThresholdDialog({ stock, onClose }: ThresholdDialogProps) {
  return (
    <Dialog
      open={stock !== null}
      onClose={onClose}
      title={stock ? `Low-stock alert level for ${stock.productName}` : 'Low-stock alert level'}
      description="The product is marked as low stock when the available units drop to this level or below."
      size="sm"
    >
      {stock && <ThresholdForm stock={stock} onClose={onClose} />}
    </Dialog>
  )
}

function ThresholdForm({ stock, onClose }: { stock: StockView; onClose: () => void }) {
  const toast = useToast()
  const save = useSetLowStockThreshold()
  const [formError, setFormError] = useState<string | null>(null)
  const [defaultValues] = useState<ThresholdFormValues>(() => ({ lowStockThreshold: String(stock.lowStockThreshold) }))
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ThresholdFormValues>({ resolver: zodResolver(thresholdSchema), defaultValues })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const saved = await save.mutateAsync({ productId: stock.productId, lowStockThreshold: Number(values.lowStockThreshold.trim()) })
      toast.success(
        saved.lowStockThreshold > 0
          ? `Low-stock alert for ${stock.productName} set to ${formatCount(saved.lowStockThreshold)}.`
          : `Low-stock alerts for ${stock.productName} are off.`,
      )
      onClose()
    } catch (error) {
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.join(' ') || vendorProductErrorMessage(error))
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <TextField
        label="Low-stock alert level"
        inputMode="numeric"
        required
        hint={`0 to ${formatCount(STOCK_LIMITS.threshold)} units. 0 turns low-stock alerts off.`}
        error={errors.lowStockThreshold?.message}
        {...register('lowStockThreshold')}
      />
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Save level
        </Button>
      </div>
    </form>
  )
}
