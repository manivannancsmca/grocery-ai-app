import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useSearchParams } from 'react-router'
import { ApiError, errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { resetPassword } from '../../shared/auth/authApi'
import { Alert, Button, Spinner } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { Icon } from '../../shared/icons'
import { AuthCard } from './AuthCard'
import { PasswordField } from './PasswordField'
import { type ResetPasswordFormValues, resetPasswordSchema } from './schemas'

const LINK_CLASS = 'font-semibold text-emerald-700 underline-offset-4 hover:text-emerald-800 hover:underline'
/** A link that looks like the screens' primary button. */
const PRIMARY_LINK_CLASS =
  'flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-b from-emerald-500 to-emerald-600 text-base font-semibold text-white shadow-md ring-1 shadow-emerald-600/25 ring-emerald-700/20 transition-all duration-200 ring-inset hover:to-emerald-700 hover:shadow-lg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/** The server's field names for this form's fields. */
const FIELD_ALIASES = { newPassword: 'password' }

/**
 * `/reset-password?token=…` (public), the page behind the emailed link: a new password twice, with the registration
 * rule. Success signs nobody in: the user signs in with the new password. An unknown, used or expired link says so and
 * offers a new one.
 */
export function ResetPasswordPage() {
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token')?.trim() ?? ''
  const [outcome, setOutcome] = useState<'form' | 'done' | 'invalid'>(token ? 'form' : 'invalid')
  const [formError, setFormError] = useState<string | null>(null)
  const defaultValues: ResetPasswordFormValues = { password: '', confirmPassword: '' }
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordFormValues>({ resolver: zodResolver(resetPasswordSchema), defaultValues })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await resetPassword({ token, newPassword: values.password })
      setOutcome('done')
    } catch (error) {
      if (hasErrorCode(error, 'RESET_TOKEN_INVALID')) {
        setOutcome('invalid')
        return
      }
      if (error instanceof ApiError && error.status === 429) {
        setFormError('Too many attempts. Wait a minute and try again.')
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues, FIELD_ALIASES)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.length > 0 ? unmatched.join(' ') : errorMessage(error))
    }
  })

  if (outcome === 'done') {
    return (
      <AuthCard title="Password changed" icon="checkCircle" description="Your new password is ready to use.">
        <div className="space-y-5">
          <Alert tone="success" role="status">
            Your password has been reset, and every device that was signed in has been signed out.
          </Alert>
          <Link to="/login" className={PRIMARY_LINK_CLASS}>
            Sign in with your new password
            <Icon name="arrowRight" className="size-5" />
          </Link>
        </div>
      </AuthCard>
    )
  }

  if (outcome === 'invalid') {
    return (
      <AuthCard title="This link doesn't work" icon="alertCircle" tone="danger" description="Reset links work once, for 30 minutes.">
        <div className="space-y-5">
          <Alert tone="danger" role="alert">
            This password reset link is invalid or has expired, or it was already used.
          </Alert>
          <Link to="/forgot-password" className={PRIMARY_LINK_CLASS}>
            Ask for a new link
          </Link>
          <p className="text-center text-sm">
            <Link to="/login" className={LINK_CLASS}>
              Back to sign in
            </Link>
          </p>
        </div>
      </AuthCard>
    )
  }

  return (
    <AuthCard title="Choose a new password" icon="lock" description="You'll sign in with it from now on, on every device.">
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="space-y-5">
        {formError && (
          <Alert tone="danger" role="alert">
            {formError}
          </Alert>
        )}
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
        <Button type="submit" className="h-12! w-full text-base!" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {isSubmitting ? 'Saving…' : 'Set new password'}
        </Button>
        <p className="text-center text-sm">
          <Link to="/login" className={LINK_CLASS}>
            Back to sign in
          </Link>
        </p>
      </form>
    </AuthCard>
  )
}
