import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { ApiError, errorMessage } from '../../shared/api/problemDetail'
import { requestPasswordReset } from '../../shared/auth/authApi'
import { Alert, Button, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { AuthCard } from './AuthCard'
import { IconField } from './IconField'
import { type ForgotPasswordFormValues, forgotPasswordSchema } from './schemas'

const LINK_CLASS = 'font-semibold text-emerald-700 underline-offset-4 hover:text-emerald-800 hover:underline'

/**
 * `/forgot-password` (public): asks for a reset link. identity answers the same for every address, so this page says
 * the same thing whether or not the address has an account.
 */
export function ForgotPasswordPage() {
  const [sent, setSent] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ForgotPasswordFormValues>({ resolver: zodResolver(forgotPasswordSchema), defaultValues: { email: '' } })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await requestPasswordReset(values.email.trim())
      setSent(true)
    } catch (error) {
      setFormError(
        error instanceof ApiError && error.status === 429 ? 'Too many attempts. Wait a minute and try again.' : errorMessage(error),
      )
    }
  })

  if (sent) {
    return (
      <AuthCard title="Check your email" icon="mail" description="The link works once and expires in 30 minutes.">
        <div className="space-y-5">
          <Alert tone="success" role="status">
            If an account exists for that address, we&apos;ve emailed a link to reset your password.
          </Alert>
          <p className="text-center text-sm text-slate-600">
            No email after a few minutes? Check your spam folder, or{' '}
            <button type="button" onClick={() => setSent(false)} className={LINK_CLASS}>
              ask for another link
            </button>
            .
          </p>
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
    <AuthCard
      title="Forgot your password?"
      icon="lock"
      description="Enter the email address you sign in with. We'll email you a link to choose a new password."
    >
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="space-y-5">
        {formError && (
          <Alert tone="danger" role="alert">
            {formError}
          </Alert>
        )}
        <IconField
          label="Email"
          icon="mail"
          type="email"
          inputMode="email"
          autoComplete="email"
          autoCapitalize="none"
          spellCheck={false}
          placeholder="you@example.com"
          error={errors.email?.message}
          {...register('email')}
        />
        <Button type="submit" className="group h-12! w-full text-base!" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {isSubmitting ? 'Sending…' : 'Email me a reset link'}
          {!isSubmitting && <Icon name="arrowRight" className="size-5 transition-transform duration-200 group-hover:translate-x-0.5" />}
        </Button>
        <p className="text-center text-sm text-slate-600">
          Remembered it?{' '}
          <Link to="/login" className={LINK_CLASS}>
            Back to sign in
          </Link>
        </p>
      </form>
    </AuthCard>
  )
}
