import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { errorMessage } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Spinner, TextField } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { emptyToNull, normalizePhone, phoneField, textField } from '../../shared/forms/validation'
import type { CustomerProfile } from './api'
import { useUpdateCustomerProfile } from './hooks'

const profileSchema = z.object({
  fullName: textField({ required: true, requiredMessage: 'Enter your full name.', max: 120 }),
  phone: phoneField(false),
})

type ProfileFormValues = z.infer<typeof profileSchema>

export function ProfileForm({ profile }: { profile: CustomerProfile }) {
  const toast = useToast()
  const updateProfile = useUpdateCustomerProfile()
  const [formError, setFormError] = useState<string | null>(null)
  const defaultValues: ProfileFormValues = { fullName: profile.fullName ?? '', phone: profile.phone ?? '' }
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<ProfileFormValues>({ resolver: zodResolver(profileSchema), defaultValues })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    const phone = emptyToNull(values.phone)
    try {
      const saved = await updateProfile.mutateAsync({
        fullName: values.fullName.trim(),
        phone: phone ? normalizePhone(phone) : null,
      })
      reset({ fullName: saved.fullName ?? '', phone: saved.phone ?? '' })
      toast.success('Profile saved.')
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
        <TextField label="Full name" autoComplete="name" required error={errors.fullName?.message} {...register('fullName')} />
        <TextField
          label="Mobile number"
          type="tel"
          autoComplete="tel"
          placeholder="+919876543210"
          hint="Optional. Used by delivery partners to reach you."
          error={errors.phone?.message}
          {...register('phone')}
        />
      </div>
      <div className="flex flex-col-reverse gap-2 border-t border-slate-100 pt-4 sm:flex-row sm:items-center sm:justify-end">
        {isDirty && <span className="text-sm text-slate-500 sm:mr-auto">You have unsaved changes.</span>}
        <Button variant="secondary" onClick={() => reset(defaultValues)} disabled={!isDirty || isSubmitting}>
          Discard changes
        </Button>
        <Button type="submit" disabled={!isDirty || isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Save profile
        </Button>
      </div>
    </form>
  )
}
