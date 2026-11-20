import { useId } from 'react'
import type { UseFormRegisterReturn } from 'react-hook-form'
import { CoordinatesFields } from '../../shared/components/CoordinatesFields'
import { SelectField, TextField } from '../../shared/components/ui'
import { INDIAN_STATES } from '../../shared/forms/indianStates'
import type { BusinessAddressFormValues } from './businessAddress'

type AddressField = keyof BusinessAddressFormValues

interface BusinessAddressFieldsProps {
  /** Registers one address sub-field, e.g. `(name) => register(\`address.${name}\`)`. */
  field: (name: AddressField) => UseFormRegisterReturn
  errors?: Partial<Record<AddressField, { message?: string } | undefined>>
  /** Mark the fields needed for submission. */
  required?: boolean
  disabled?: boolean
  onLocate: (coords: { latitude: string; longitude: string }) => void
}

export function BusinessAddressFields({ field, errors, required, disabled, onLocate }: BusinessAddressFieldsProps) {
  const statesListId = useId()
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <TextField
        label="Address line 1"
        autoComplete="address-line1"
        required={required}
        disabled={disabled}
        error={errors?.line1?.message}
        containerClassName="sm:col-span-2"
        {...field('line1')}
      />
      <TextField
        label="Address line 2"
        autoComplete="address-line2"
        hint="Optional."
        disabled={disabled}
        error={errors?.line2?.message}
        containerClassName="sm:col-span-2"
        {...field('line2')}
      />
      <TextField
        label="City"
        autoComplete="address-level2"
        required={required}
        disabled={disabled}
        error={errors?.city?.message}
        {...field('city')}
      />
      <TextField
        label="State"
        autoComplete="address-level1"
        list={statesListId}
        required={required}
        disabled={disabled}
        error={errors?.state?.message}
        {...field('state')}
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
        required={required}
        disabled={disabled}
        error={errors?.postalCode?.message}
        {...field('postalCode')}
      />
      <SelectField label="Country" disabled={disabled} error={errors?.country?.message} {...field('country')}>
        <option value="IN">India</option>
      </SelectField>
      <CoordinatesFields
        latitude={field('latitude')}
        longitude={field('longitude')}
        latitudeError={errors?.latitude?.message}
        longitudeError={errors?.longitude?.message}
        required={required}
        disabled={disabled}
        onLocate={onLocate}
      />
    </div>
  )
}
