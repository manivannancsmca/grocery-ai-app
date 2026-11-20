import { useWatch } from 'react-hook-form'
import { useAuth } from '../../shared/auth/useAuth'
import { TextField } from '../../shared/components/ui'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { useOnboardingForm } from '../stakeholders/hooks'
import { FormSection, OnboardingLayout } from '../stakeholders/OnboardingLayout'
import { partnerApi, partnerKeys, type PartnerView, VEHICLE_TYPES, type VehicleType } from './api'
import {
  partnerDraftSchema,
  partnerFormToRequest,
  partnerSubmitSchema,
  partnerToFormValues,
  VEHICLE_LABELS,
} from './schema'

const VEHICLE_ICONS: Record<VehicleType, IconName> = {
  BICYCLE: 'bicycle',
  SCOOTER: 'scooter',
  MOTORCYCLE: 'motorcycle',
  CAR: 'car',
}

/** Delivery-partner onboarding, laid out for phones first. */
export function PartnerOnboarding({ profile }: { profile: PartnerView }) {
  return <PartnerOnboardingForm key={profile.status} profile={profile} />
}

function PartnerOnboardingForm({ profile }: { profile: PartnerView }) {
  const { user } = useAuth()
  const { form, formError, pendingAction, saveDraft, submitForApproval } = useOnboardingForm({
    draftSchema: partnerDraftSchema,
    submitSchema: partnerSubmitSchema,
    defaultValues: partnerToFormValues(profile, { fullName: user?.fullName, phone: user?.phone }),
    toRequest: partnerFormToRequest,
    api: partnerApi,
    queryKey: partnerKeys.mine,
    entityLabel: 'Partner profile',
  })
  const {
    register,
    control,
    formState: { errors },
  } = form
  const vehicleType = useWatch({ control, name: 'vehicleType' })
  const needsLicence = vehicleType !== 'BICYCLE'

  return (
    <OnboardingLayout
      profile={profile}
      noun="partner profile"
      formError={formError}
      pendingAction={pendingAction}
      onSaveDraft={() => void saveDraft()}
      onSubmit={() => void submitForApproval()}
    >
      <FormSection title="About you" description={`Signed in as ${profile.email}.`}>
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Full name" autoComplete="name" required error={errors.fullName?.message} {...register('fullName')} />
          <TextField
            label="Mobile number"
            type="tel"
            autoComplete="tel"
            placeholder="+919876543210"
            required
            error={errors.phone?.message}
            {...register('phone')}
          />
          <TextField
            label="City you deliver in"
            autoComplete="address-level2"
            placeholder="Bengaluru"
            required
            error={errors.city?.message}
            {...register('city')}
          />
        </div>
      </FormSection>

      <FormSection title="Vehicle">
        <fieldset>
          <legend className="text-sm font-medium text-slate-700">
            Vehicle type<span className="ml-0.5 text-red-600" aria-hidden="true">*</span>
          </legend>
          <div className="mt-2 grid grid-cols-2 gap-3 sm:grid-cols-4">
            {VEHICLE_TYPES.map((type) => {
              const checked = vehicleType === type
              return (
                <label
                  key={type}
                  className={cn(
                    'flex cursor-pointer flex-col items-center gap-2 rounded-xl border p-4 text-sm font-medium transition-colors',
                    'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-emerald-500/40 has-[:disabled]:cursor-not-allowed',
                    checked ? 'border-emerald-500 bg-emerald-50 text-emerald-800' : 'border-slate-200 text-slate-700 hover:border-slate-300',
                  )}
                >
                  <input type="radio" value={type} className="sr-only" {...register('vehicleType')} />
                  <Icon name={VEHICLE_ICONS[type]} className="size-8" />
                  {VEHICLE_LABELS[type]}
                </label>
              )
            })}
          </div>
          {errors.vehicleType?.message && (
            <p className="mt-2 text-xs font-medium text-red-600">{errors.vehicleType.message}</p>
          )}
        </fieldset>

        {needsLicence ? (
          <div className="mt-4 grid gap-4 sm:grid-cols-2">
            <TextField
              label="Vehicle registration number"
              placeholder="KA01AB1234"
              autoCapitalize="characters"
              required
              error={errors.vehicleNumber?.message}
              {...register('vehicleNumber')}
            />
            <TextField
              label="Driving licence number"
              placeholder="KA0120201234567"
              autoCapitalize="characters"
              required
              error={errors.drivingLicenseNumber?.message}
              {...register('drivingLicenseNumber')}
            />
          </div>
        ) : (
          <p className="mt-4 text-sm text-slate-500">No registration or driving licence is needed for a bicycle.</p>
        )}
      </FormSection>
    </OnboardingLayout>
  )
}
