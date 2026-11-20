import { TextField } from '../../shared/components/ui'
import { BusinessAddressFields } from '../stakeholders/BusinessAddressFields'
import { useOnboardingForm } from '../stakeholders/hooks'
import { FormSection, OnboardingLayout } from '../stakeholders/OnboardingLayout'
import { vendorApi, vendorKeys, type VendorView } from './api'
import { vendorDraftSchema, vendorFormToRequest, vendorSubmitSchema, vendorToFormValues } from './schema'

/** Vendor onboarding: status banner + business details form (editable in DRAFT / REJECTED). */
export function VendorOnboarding({ profile }: { profile: VendorView }) {
  // Remount when the status changes so the form picks up the new (possibly read-only) state.
  return <VendorOnboardingForm key={profile.status} profile={profile} />
}

function VendorOnboardingForm({ profile }: { profile: VendorView }) {
  const { form, formError, pendingAction, saveDraft, submitForApproval } = useOnboardingForm({
    draftSchema: vendorDraftSchema,
    submitSchema: vendorSubmitSchema,
    defaultValues: vendorToFormValues(profile),
    toRequest: vendorFormToRequest,
    api: vendorApi,
    queryKey: vendorKeys.mine,
    entityLabel: 'Business profile',
  })
  const {
    register,
    setValue,
    formState: { errors },
  } = form

  return (
    <OnboardingLayout
      profile={profile}
      noun="business"
      formError={formError}
      pendingAction={pendingAction}
      onSaveDraft={() => void saveDraft()}
      onSubmit={() => void submitForApproval()}
    >
      <FormSection title="Business details" description="As registered with GST. Fields marked * are needed to submit.">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Business name" required error={errors.businessName?.message} {...register('businessName')} />
          <TextField label="Legal name" required error={errors.legalName?.message} {...register('legalName')} />
          <TextField
            label="GSTIN"
            required
            placeholder="29ABCDE1234F1Z5"
            autoCapitalize="characters"
            maxLength={15}
            error={errors.gstin?.message}
            {...register('gstin')}
          />
          <TextField
            label="FSSAI licence"
            inputMode="numeric"
            maxLength={14}
            hint="Optional. 14 digits."
            error={errors.fssaiLicense?.message}
            {...register('fssaiLicense')}
          />
        </div>
      </FormSection>

      <FormSection title="Contact" description="Where our team and customers' orders reach you.">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            label="Contact email"
            type="email"
            required
            error={errors.contactEmail?.message}
            {...register('contactEmail')}
          />
          <TextField
            label="Contact phone"
            type="tel"
            placeholder="+919876543210"
            required
            error={errors.contactPhone?.message}
            {...register('contactPhone')}
          />
        </div>
      </FormSection>

      <FormSection title="Store address" description="Used for pickups and to show your store to nearby customers.">
        <BusinessAddressFields
          field={(name) => register(`address.${name}`)}
          errors={errors.address}
          required
          onLocate={({ latitude, longitude }) => {
            setValue('address.latitude', latitude, { shouldDirty: true, shouldValidate: true })
            setValue('address.longitude', longitude, { shouldDirty: true, shouldValidate: true })
          }}
        />
      </FormSection>
    </OnboardingLayout>
  )
}
