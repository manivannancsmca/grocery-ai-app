import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { ApiError, errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { changeOwnPassword } from '../../shared/auth/session'
import { Alert, Button, PageHeader, Spinner } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { Icon } from '../../shared/icons'
import { PasswordField } from './PasswordField'
import { type ChangePasswordFormValues, changePasswordSchema } from './schemas'

const EMPTY: ChangePasswordFormValues = { currentPassword: '', password: '', confirmPassword: '' }

/** The server's field names for this form's fields. */
const FIELD_ALIASES = { newPassword: 'password' }

/**
 * `/account/password`, for every signed-in role (the header's account menu links here). identity signs out every other
 * device; this one stays signed in (its session renews its token right after the change).
 */
export function ChangePasswordPage() {
  const [formError, setFormError] = useState<string | null>(null)
  const [changed, setChanged] = useState(false)
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordFormValues>({ resolver: zodResolver(changePasswordSchema), defaultValues: EMPTY })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    setChanged(false)
    try {
      await changeOwnPassword({ currentPassword: values.currentPassword, newPassword: values.password })
      reset(EMPTY)
      setChanged(true)
    } catch (error) {
      if (hasErrorCode(error, 'CURRENT_PASSWORD_INCORRECT')) {
        setError(
          'currentPassword',
          { type: 'server', message: 'This is not your current password. After 5 wrong attempts the account is locked for a few minutes.' },
          { shouldFocus: true },
        )
      } else if (hasErrorCode(error, 'PASSWORD_UNCHANGED')) {
        setError('password', { type: 'server', message: 'Choose a password different from your current one.' }, { shouldFocus: true })
      } else if (error instanceof ApiError && error.status === 429) {
        setFormError('Too many attempts. Wait a minute and try again.')
      } else {
        const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, EMPTY, FIELD_ALIASES)
        if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.length > 0 ? unmatched.join(' ') : errorMessage(error))
      }
    }
  })

  return (
    <div className="w-full">
      <PageHeader
        eyebrow="Your account"
        icon="lock"
        title="Change password"
        description="Choose a new password for signing in to FreshCart."
      />
      <section
        aria-labelledby="change-password-title"
        className="animate-fade-up max-w-2xl rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:p-6"
      >
        <header className="mb-5 flex items-start gap-3">
          <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-50">
            <Icon name="shield" className="size-[1.15rem]" />
          </span>
          <div className="min-w-0">
            <h2 id="change-password-title" className="text-base font-semibold text-slate-900">
              Your password
            </h2>
            <p className="mt-0.5 text-sm text-slate-500">
              Changing it signs you out on your other devices. You stay signed in here.
            </p>
          </div>
        </header>

        <form onSubmit={(event) => void onSubmit(event)} noValidate className="space-y-5">
          {changed && (
            <Alert tone="success" role="status" title="Password changed">
              Use your new password the next time you sign in. Your other devices were signed out.
            </Alert>
          )}
          {formError && (
            <Alert tone="danger" role="alert">
              {formError}
            </Alert>
          )}
          <PasswordField
            label="Current password"
            revealLabel="current password"
            autoComplete="current-password"
            required
            error={errors.currentPassword?.message}
            {...register('currentPassword')}
          />
          <div className="grid gap-5 sm:grid-cols-2">
            <PasswordField
              label="New password"
              revealLabel="new password"
              autoComplete="new-password"
              required
              hint="8–64 characters with at least one letter and one digit."
              error={errors.password?.message}
              {...register('password')}
            />
            <PasswordField
              label="Confirm new password"
              revealLabel="password confirmation"
              autoComplete="new-password"
              required
              error={errors.confirmPassword?.message}
              {...register('confirmPassword')}
            />
          </div>
          <div className="flex flex-col-reverse gap-3 border-t border-slate-100 pt-4 sm:flex-row sm:items-center sm:justify-between">
            <Link
              to="/forgot-password"
              className="text-center text-sm font-medium text-emerald-700 underline-offset-4 hover:text-emerald-800 hover:underline"
            >
              Forgot your current password?
            </Link>
            <Button type="submit" disabled={isSubmitting}>
              {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
              {isSubmitting ? 'Changing…' : 'Change password'}
            </Button>
          </div>
        </form>
      </section>
    </div>
  )
}
