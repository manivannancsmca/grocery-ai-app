import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { ADMIN_ROLES, ROLE_LABELS } from '../../shared/auth/roles'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Checkbox, Dialog, Spinner, TextField } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { emailField, normalizePhone, passwordField, phoneField, textField } from '../../shared/forms/validation'
import { useCreateAdminUser } from './hooks'

const ROLE_DESCRIPTIONS: Record<(typeof ADMIN_ROLES)[number], string> = {
  SUPER_ADMIN: 'Everything, including creating admins.',
  OPS_ADMIN: 'Approvals, suspensions and user management.',
  FINANCE_ADMIN: 'Payments and refunds (later phases); read-only here.',
  SUPPORT: 'Look up users and customers; read-only here.',
  DELIVERY_MANAGER: 'Delivery partner approvals and the delivery fleet.',
}

const createAdminSchema = z.object({
  fullName: textField({ required: true, requiredMessage: 'Enter the full name.', max: 120 }),
  email: emailField(true),
  phone: phoneField(false),
  password: passwordField,
  roles: z.array(z.enum(ADMIN_ROLES)).min(1, 'Choose at least one admin role.'),
})

type CreateAdminValues = z.infer<typeof createAdminSchema>

const DEFAULT_VALUES: CreateAdminValues = { fullName: '', email: '', phone: '', password: '', roles: [] }

interface CreateAdminDialogProps {
  open: boolean
  onClose: () => void
}

/** SUPER_ADMIN only: create an admin account with one or more admin roles. */
export function CreateAdminDialog({ open, onClose }: CreateAdminDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} title="Create admin user" description="The new admin signs in with this email and password." size="lg">
      <CreateAdminForm onClose={onClose} />
    </Dialog>
  )
}

function CreateAdminForm({ onClose }: { onClose: () => void }) {
  const toast = useToast()
  const createAdmin = useCreateAdminUser()
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<CreateAdminValues>({ resolver: zodResolver(createAdminSchema), defaultValues: DEFAULT_VALUES })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    const phone = normalizePhone(values.phone.trim())
    try {
      const created = await createAdmin.mutateAsync({
        fullName: values.fullName.trim(),
        email: values.email.trim(),
        password: values.password,
        roles: values.roles,
        ...(phone ? { phone } : {}),
      })
      toast.success(`Admin account created for ${created.email}.`)
      onClose()
    } catch (error) {
      if (hasErrorCode(error, 'EMAIL_ALREADY_REGISTERED')) {
        setError('email', { type: 'server', message: 'This email is already registered.' }, { shouldFocus: true })
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, DEFAULT_VALUES)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.join(' ') || errorMessage(error))
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-5">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField label="Full name" required error={errors.fullName?.message} {...register('fullName')} />
        <TextField label="Email" type="email" autoComplete="off" required error={errors.email?.message} {...register('email')} />
        <TextField label="Phone" type="tel" hint="Optional." error={errors.phone?.message} {...register('phone')} />
        <TextField
          label="Initial password"
          type="password"
          autoComplete="new-password"
          required
          hint="8–64 characters with a letter and a digit."
          error={errors.password?.message}
          {...register('password')}
        />
      </div>
      <fieldset>
        <legend className="text-sm font-medium text-slate-700">
          Admin roles<span className="ml-0.5 text-red-600" aria-hidden="true">*</span>
        </legend>
        <div className="mt-2 grid gap-2 sm:grid-cols-2">
          {ADMIN_ROLES.map((role) => (
            <label key={role} className="flex items-start gap-3 rounded-lg border border-slate-200 p-3 text-sm hover:bg-slate-50">
              <Checkbox className="mt-0.5" value={role} {...register('roles')} />
              <span>
                <span className="block font-medium text-slate-900">{ROLE_LABELS[role]}</span>
                <span className="block text-xs text-slate-500">{ROLE_DESCRIPTIONS[role]}</span>
              </span>
            </label>
          ))}
        </div>
        {errors.roles?.message && <p className="mt-2 text-xs font-medium text-red-600">{errors.roles.message}</p>}
      </fieldset>
      <div className="flex flex-col-reverse gap-2 border-t border-slate-100 pt-4 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Create admin
        </Button>
      </div>
    </form>
  )
}
