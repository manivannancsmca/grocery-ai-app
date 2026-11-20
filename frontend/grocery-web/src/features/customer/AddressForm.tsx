import { zodResolver } from '@hookform/resolvers/zod'
import { type ReactNode, useId, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { CoordinatesFields } from '../../shared/components/CoordinatesFields'
import { Alert, Button, Checkbox, SelectField, Spinner, TextField } from '../../shared/components/ui'
import { INDIAN_STATES } from '../../shared/forms/indianStates'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { type AddressFormValues, addressSchema, formValuesToAddressRequest } from './addressSchema'
import { type AddressRequest, MAX_ADDRESSES } from './api'

/** One-tap names for the label field (it stays free text). */
const LABEL_CHIPS: ReadonlyArray<{ label: string; icon: IconName }> = [
  { label: 'Home', icon: 'home' },
  { label: 'Work', icon: 'briefcase' },
  { label: 'Other', icon: 'mapPin' },
]

function SectionHeading({ id, icon, children }: { id: string; icon: IconName; children: ReactNode }) {
  return (
    <h3 id={id} className="flex items-center gap-2 text-sm font-semibold text-slate-900">
      <span aria-hidden="true" className="flex size-7 items-center justify-center rounded-lg bg-emerald-50">
        <Icon name={icon} className="size-4" />
      </span>
      {children}
    </h3>
  )
}

interface AddressFormProps {
  defaultValues: AddressFormValues
  /** The first address is always the default, so the checkbox is forced on. */
  forceDefault?: boolean
  submitLabel: string
  onSubmit: (request: AddressRequest) => Promise<unknown>
  onCancel: () => void
}

export function AddressForm({ defaultValues, forceDefault = false, submitLabel, onSubmit, onCancel }: AddressFormProps) {
  const statesListId = useId()
  const labelsListId = useId()
  const contactId = useId()
  const addressId = useId()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    setValue,
    control,
    formState: { errors, isSubmitting },
  } = useForm<AddressFormValues>({
    resolver: zodResolver(addressSchema),
    defaultValues: forceDefault ? { ...defaultValues, isDefault: true } : defaultValues,
  })

  const label = useWatch({ control, name: 'label' }) ?? ''

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await onSubmit(formValuesToAddressRequest(values))
    } catch (error) {
      if (hasErrorCode(error, 'ADDRESS_LIMIT_REACHED')) {
        setFormError(`You already have ${MAX_ADDRESSES} saved addresses. Delete one before adding another.`)
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues)
      if (!placedOnFields || unmatched.length > 0) {
        setFormError(unmatched.length > 0 ? unmatched.join(' ') : errorMessage(error))
      }
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-5">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <section aria-labelledby={contactId} className="space-y-4">
        <SectionHeading id={contactId} icon="user">
          Name &amp; contact
        </SectionHeading>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-2 sm:col-span-2">
            <TextField
              label="Label"
              placeholder="Home, Work…"
              list={labelsListId}
              required
              error={errors.label?.message}
              {...register('label')}
            />
            <datalist id={labelsListId}>
              <option value="Home" />
              <option value="Work" />
              <option value="Other" />
            </datalist>
            <div role="group" aria-label="Suggested names" className="flex flex-wrap gap-2">
              {LABEL_CHIPS.map((chip) => {
                const selected = label.trim().toLowerCase() === chip.label.toLowerCase()
                return (
                  <button
                    key={chip.label}
                    type="button"
                    aria-pressed={selected}
                    onClick={() => setValue('label', chip.label, { shouldDirty: true, shouldValidate: true })}
                    className={cn(
                      'inline-flex h-9 items-center gap-1.5 rounded-full px-3 text-sm font-medium transition',
                      'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
                      selected
                        ? 'bg-emerald-600 text-white shadow-sm'
                        : 'bg-white text-slate-600 ring-1 ring-slate-200 ring-inset hover:bg-emerald-50 hover:text-emerald-800',
                    )}
                  >
                    <Icon name={chip.icon} className="size-4" />
                    {chip.label}
                  </button>
                )
              })}
            </div>
          </div>
          <TextField
            label="Recipient name"
            autoComplete="name"
            required
            error={errors.recipientName?.message}
            {...register('recipientName')}
          />
          <TextField
            label="Phone"
            type="tel"
            autoComplete="tel"
            placeholder="+919876543210"
            required
            error={errors.phone?.message}
            {...register('phone')}
          />
        </div>
      </section>

      <section aria-labelledby={addressId} className="space-y-4 border-t border-slate-100 pt-5">
        <SectionHeading id={addressId} icon="home">
          Address
        </SectionHeading>
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            label="House / flat, street"
            autoComplete="address-line1"
            required
            error={errors.line1?.message}
            containerClassName="sm:col-span-2"
            {...register('line1')}
          />
          <TextField
            label="Area / locality"
            autoComplete="address-line2"
            hint="Optional."
            error={errors.line2?.message}
            {...register('line2')}
          />
          <TextField label="Landmark" hint="Optional." error={errors.landmark?.message} {...register('landmark')} />
          <TextField
            label="City"
            autoComplete="address-level2"
            required
            error={errors.city?.message}
            {...register('city')}
          />
          <TextField
            label="State"
            autoComplete="address-level1"
            list={statesListId}
            required
            error={errors.state?.message}
            {...register('state')}
          />
          <datalist id={statesListId}>
            {INDIAN_STATES.map((state) => (
              <option key={state} value={state} />
            ))}
          </datalist>
          <TextField
            label="PIN code"
            inputMode="numeric"
            autoComplete="postal-code"
            maxLength={6}
            required
            error={errors.postalCode?.message}
            {...register('postalCode')}
          />
          <SelectField label="Country" error={errors.country?.message} {...register('country')}>
            <option value="IN">India</option>
          </SelectField>
        </div>
      </section>

      <div className="grid gap-4 rounded-xl bg-slate-50/80 p-4 ring-1 ring-slate-100 sm:grid-cols-2">
        <CoordinatesFields
          latitude={register('latitude')}
          longitude={register('longitude')}
          latitudeError={errors.latitude?.message}
          longitudeError={errors.longitude?.message}
          required
          onLocate={({ latitude, longitude }) => {
            setValue('latitude', latitude, { shouldValidate: true, shouldDirty: true })
            setValue('longitude', longitude, { shouldValidate: true, shouldDirty: true })
          }}
        />
      </div>

      {forceDefault ? (
        <p className="flex items-start gap-2 rounded-xl bg-emerald-50 px-3 py-2.5 text-sm text-emerald-800">
          <Icon name="checkCircle" className="mt-0.5 size-4" />
          This will be your default delivery address — the first address always is.
        </p>
      ) : (
        <label className="flex cursor-pointer items-start gap-3 rounded-xl p-3 text-sm text-slate-700 ring-1 ring-slate-200 ring-inset hover:bg-slate-50">
          <Checkbox className="mt-0.5" {...register('isDefault')} />
          <span>
            <span className="font-medium text-slate-900">Use as my default delivery address</span>
            <span className="block text-xs text-slate-500">Checkout picks it for you automatically.</span>
          </span>
        </label>
      )}

      <div className="flex flex-col-reverse gap-2 border-t border-slate-100 pt-4 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {submitLabel}
        </Button>
      </div>
    </form>
  )
}
