import { zodResolver } from '@hookform/resolvers/zod'
import { lazy, Suspense, useId, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useLocation, useSearchParams } from 'react-router'
import { ApiError, errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { redirectTarget } from '../../shared/auth/redirect'
import { useAuth } from '../../shared/auth/useAuth'
import { Alert, Button, Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { AuthShell } from './AuthShell'
import { IconField } from './IconField'
import { PasswordField } from './PasswordField'
import { loginSchema } from './schemas'
import { StakeholderShowcase, StakeholderTabs } from './StakeholderShowcase'
import { initialStakeholder, STAKEHOLDERS } from './stakeholders'

/**
 * Development sign-ins under the form: only on the Vite dev server (`VITE_DEV_ACCOUNTS=false` hides them).
 * A production build drops this branch, and with it the module that holds the credentials.
 */
const DevAccountsPanel =
  import.meta.env.DEV && import.meta.env.VITE_DEV_ACCOUNTS !== 'false' ? lazy(() => import('./DevAccountsPanel')) : null

/**
 * @param {unknown} error
 * @returns {string}
 */
function loginErrorMessage(error) {
  if (hasErrorCode(error, 'INVALID_CREDENTIALS')) {
    return 'Incorrect email or password. After 5 failed attempts sign-in is locked for a few minutes.'
  }
  if (error instanceof ApiError && error.status === 429) {
    return 'Too many attempts. Wait a minute and try again.'
  }
  return errorMessage(error)
}

/**
 * Sign-in for every stakeholder: one form with a "how do you use FreshCart" switch above it, and a brand panel that
 * shows the chosen kind of user what is waiting for them. After success the `GuestOnly` route guard sends the user back to the page
 * they were trying to open (router state `from`), or to their role's home page.
 */
export function LoginPage() {
  const { login, endReason } = useAuth()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const [formError, setFormError] = useState(null)
  // Ties the stakeholder tabs (above the form) to the pitch they control (brand panel).
  const baseId = useId()
  const [selected, setSelected] = useState(() =>
    initialStakeholder(searchParams.get('role'), redirectTarget(location.state)),
  )
  const stakeholder = STAKEHOLDERS[selected]
  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await login({ email: values.email.trim(), password: values.password })
    } catch (error) {
      // ACCOUNT_SUSPENDED is handled by the session (→ suspended screen); everything else is shown here.
      if (!hasErrorCode(error, 'ACCOUNT_SUSPENDED')) setFormError(loginErrorMessage(error))
    }
  })

  function fillDevAccount(account) {
    setFormError(null)
    setValue('email', account.email, { shouldDirty: true, shouldValidate: true })
    setValue('password', account.password, { shouldDirty: true, shouldValidate: true })
  }

  return (
    <AuthShell panel={<StakeholderShowcase baseId={baseId} selected={selected} />}>
      <p className="inline-flex items-center gap-1.5 text-sm font-semibold text-emerald-700">
        <Icon name="sparkle" className="size-4" />
        Welcome back
      </p>
      <h1 className="mt-2 text-3xl font-extrabold tracking-tight text-slate-900 sm:text-4xl">Sign in</h1>
      <p className="mt-2 text-sm text-slate-500 sm:text-base">
        Use your FreshCart email and password. We open the right workspace for your account.
      </p>

      <StakeholderTabs baseId={baseId} selected={selected} onSelect={setSelected} className="mt-7" />

      {endReason === 'expired' && !formError && (
        <Alert tone="warning" className="mt-6" role="status">
          Your session has expired. Please sign in again.
        </Alert>
      )}
      {formError && (
        <Alert tone="danger" className="mt-6" role="alert">
          {formError}
        </Alert>
      )}

      <form onSubmit={(event) => void onSubmit(event)} noValidate className="mt-6 space-y-5">
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
        <PasswordField
          label="Password"
          autoComplete="current-password"
          placeholder="Your password"
          error={errors.password?.message}
          {...register('password')}
        />
        <p className="-mt-2 text-right">
          <Link
            to="/forgot-password"
            className="text-sm font-semibold text-emerald-700 underline-offset-4 hover:text-emerald-800 hover:underline"
          >
            Forgot password?
          </Link>
        </p>
        <Button type="submit" className="group h-12! w-full text-base!" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {isSubmitting ? 'Signing in…' : 'Sign in'}
          {!isSubmitting && (
            <Icon name="arrowRight" className="size-5 transition-transform duration-200 group-hover:translate-x-0.5" />
          )}
        </Button>
        <p className="flex items-center justify-center gap-1.5 text-xs text-slate-400">
          <Icon name="lock" className="size-3.5" />
          Secure sign-in. Your account decides which workspace opens.
        </p>
      </form>

      {DevAccountsPanel && (
        <Suspense fallback={null}>
          <DevAccountsPanel area={selected} onUse={fillDevAccount} />
        </Suspense>
      )}

      <p className="mt-8 rounded-2xl bg-slate-50 px-4 py-3.5 text-center text-sm text-slate-600 ring-1 ring-slate-200/70">
        {stakeholder.registerRole ? (
          <>
            New to FreshCart?{' '}
            <Link
              to={`/register?role=${stakeholder.registerRole}`}
              state={location.state}
              className="font-semibold text-emerald-700 underline-offset-4 hover:text-emerald-800 hover:underline"
            >
              Create a {stakeholder.accountName} account
            </Link>
          </>
        ) : (
          'Admin accounts are created by a FreshCart super admin. Ask yours for access.'
        )}
      </p>
    </AuthShell>
  )
}
