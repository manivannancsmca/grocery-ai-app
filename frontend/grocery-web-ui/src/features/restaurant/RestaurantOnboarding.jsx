import { Controller } from 'react-hook-form'
import { TagInput, TextareaField, TextField } from '../../shared/components/ui'
import { fieldErrorMessage } from '../../shared/forms/serverErrors'
import { humanizeToken } from '../../shared/utils/format'
import { BusinessAddressFields } from '../stakeholders/BusinessAddressFields'
import { useOnboardingForm } from '../stakeholders/hooks'
import { FormSection, OnboardingLayout } from '../stakeholders/OnboardingLayout'
import { isEditableStatus } from '../stakeholders/types'
import { MAX_CUISINES, restaurantApi, restaurantKeys } from './api'
import { OpeningHoursFields } from './OpeningHoursFields'
import {
  CUISINE_SUGGESTIONS,
  cuisineError,
  normalizeCuisine,
  restaurantDraftSchema,
  restaurantFormToRequest,
  restaurantSubmitSchema,
  restaurantToFormValues,
} from './schema'

/** @typedef {import('./api').RestaurantView} RestaurantView */

/**
 * Restaurant onboarding: status banner + restaurant details, cuisines and weekly opening hours.
 * @param {{ profile: RestaurantView }} props
 */
export function RestaurantOnboarding({ profile }) {
  return <RestaurantOnboardingForm key={profile.status} profile={profile} />
}

/** @param {{ profile: RestaurantView }} props */
function RestaurantOnboardingForm({ profile }) {
  const { form, formError, pendingAction, saveDraft, submitForApproval } = useOnboardingForm({
    draftSchema: restaurantDraftSchema,
    submitSchema: restaurantSubmitSchema,
    defaultValues: restaurantToFormValues(profile),
    toRequest: restaurantFormToRequest,
    api: restaurantApi,
    queryKey: restaurantKeys.mine,
    entityLabel: 'Restaurant profile',
  })
  const {
    register,
    setValue,
    control,
    formState: { errors },
  } = form
  const readOnly = !isEditableStatus(profile.status)

  return (
    <OnboardingLayout
      profile={profile}
      noun="restaurant"
      formError={formError}
      pendingAction={pendingAction}
      onSaveDraft={() => void saveDraft()}
      onSubmit={() => void submitForApproval()}
    >
      <FormSection title="Restaurant" description="What customers see. Fields marked * are needed to submit.">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            label="Restaurant name"
            required
            error={errors.name?.message}
            containerClassName="sm:col-span-2"
            {...register('name')}
          />
          <TextareaField
            label="Description"
            hint="Optional. A line or two about your kitchen."
            error={errors.description?.message}
            containerClassName="sm:col-span-2"
            {...register('description')}
          />
          <div className="sm:col-span-2">
            <Controller
              control={control}
              name="cuisines"
              render={({ field, fieldState }) => (
                <TagInput
                  label="Cuisines"
                  required
                  value={field.value}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                  normalize={normalizeCuisine}
                  validate={cuisineError}
                  formatTag={humanizeToken}
                  suggestions={CUISINE_SUGGESTIONS}
                  maxTags={MAX_CUISINES}
                  disabled={readOnly}
                  placeholder="Type a cuisine and press Enter"
                  hint={`Up to ${MAX_CUISINES}, e.g. North Indian, Biryani.`}
                  error={fieldErrorMessage(fieldState.error) ?? fieldErrorMessage(errors.cuisines)}
                />
              )}
            />
          </div>
        </div>
      </FormSection>

      <FormSection title="Licences and contact">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            label="FSSAI licence"
            inputMode="numeric"
            maxLength={14}
            required
            error={errors.fssaiLicense?.message}
            {...register('fssaiLicense')}
          />
          <TextField
            label="GSTIN"
            placeholder="29ABCDE1234F1Z5"
            maxLength={15}
            hint="Optional."
            error={errors.gstin?.message}
            {...register('gstin')}
          />
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

      <FormSection title="Address" description="Delivery partners pick up orders here.">
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

      <FormSection
        title="Opening hours"
        description="Times are local (IST). A closing time earlier than the opening time means past midnight."
      >
        <OpeningHoursFields form={form} disabled={readOnly} />
      </FormSection>
    </OnboardingLayout>
  )
}
