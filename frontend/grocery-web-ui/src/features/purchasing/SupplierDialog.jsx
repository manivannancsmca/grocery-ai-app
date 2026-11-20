import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { errorMessage } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, Spinner, TextareaField, TextField } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { PURCHASING_LIMITS } from './api'
import { useSaveSupplier } from './hooks'
import { supplierFormToRequest, supplierSchema, supplierToForm } from './schema'

/** @typedef {import('./api').SupplierView} SupplierView */

/**
 * @typedef {object} SupplierDialogProps
 * @property {boolean} open
 * @property {SupplierView | null} supplier null = a new supplier.
 * @property {() => void} onClose
 */

/**
 * Create or edit a supplier (name required; contact details, GSTIN, lead time and notes optional).
 * @param {SupplierDialogProps} props
 */
export function SupplierDialog({ open, supplier, onClose }) {
  return (
    <Dialog open={open} onClose={onClose} title={supplier ? `Edit ${supplier.name}` : 'New supplier'} size="lg">
      <SupplierForm key={supplier?.id ?? 'new'} supplier={supplier} onClose={onClose} />
    </Dialog>
  )
}

/** @param {{ supplier: SupplierView | null, onClose: () => void }} props */
function SupplierForm({ supplier, onClose }) {
  const toast = useToast()
  const save = useSaveSupplier()
  const [defaultValues] = useState(() => supplierToForm(supplier))
  const [formError, setFormError] = useState(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(supplierSchema), defaultValues })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const saved = await save.mutateAsync({ id: supplier?.id ?? null, body: supplierFormToRequest(values) })
      toast.success(supplier ? 'Supplier updated' : 'Supplier added', { description: saved.name })
      onClose()
    } catch (error) {
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.join(' ') || errorMessage(error))
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Name"
          required
          maxLength={PURCHASING_LIMITS.supplierName}
          containerClassName="sm:col-span-2"
          error={errors.name?.message}
          {...register('name')}
        />
        <TextField label="Contact person" error={errors.contactName?.message} {...register('contactName')} />
        <TextField label="Phone" type="tel" inputMode="tel" error={errors.phone?.message} {...register('phone')} />
        <TextField label="Email" type="email" error={errors.email?.message} {...register('email')} />
        <TextField label="GSTIN" error={errors.gstin?.message} {...register('gstin')} />
        <TextareaField
          label="Address"
          rows={2}
          maxLength={PURCHASING_LIMITS.supplierAddress}
          containerClassName="sm:col-span-2"
          error={errors.address?.message}
          {...register('address')}
        />
        <TextField
          label="Lead time (days)"
          inputMode="numeric"
          hint={`0–${PURCHASING_LIMITS.leadTimeDaysMax} days from order to delivery.`}
          error={errors.leadTimeDays?.message}
          {...register('leadTimeDays')}
        />
        <TextareaField
          label="Notes"
          rows={2}
          maxLength={PURCHASING_LIMITS.supplierNotes}
          containerClassName="sm:col-span-2"
          error={errors.notes?.message}
          {...register('notes')}
        />
      </div>
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {supplier ? 'Save supplier' : 'Add supplier'}
        </Button>
      </div>
    </form>
  )
}
