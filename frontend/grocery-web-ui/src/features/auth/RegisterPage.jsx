import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useLocation, useSearchParams } from 'react-router'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { ROLE_LABELS, SELF_REGISTRATION_ROLES } from '../../shared/auth/roles'
import { useAuth } from '../../shared/auth/useAuth'
import { Alert, Button, Spinner } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { normalizePhone } from '../../shared/forms/validation'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { AuthShell, BrandPanel, BrandPitch } from './AuthShell'
import { IconField } from './IconField'
import { PasswordField } from './PasswordField'
import { StakeholderArt } from './StakeholderArt'
import { registerSchema } from './schemas'
import { STAKEHOLDERS } from './stakeholders'

/** @typedef {import('../../shared/auth/roles').SelfRegistrationRole} SelfRegistrationRole */

/** @type {Record<SelfRegistrationRole, string>} */
const ROLE_DESCRIPTIONS = {
  CUSTOMER: 'Order groceries and restaurant meals.',
  VENDOR: 'Sell groceries from your store.',
  RESTAURANT_OWNER: 'List your restaurant and menu.',
  DELIVERY_PARTNER: 'Deliver orders and earn.',
}

/**
 * @param {string | null} value
 * @returns {SelfRegistrationRole}
 */
function roleFromQuery(value) {
  return SELF_REGISTRATION_ROLES.includes(value ?? '') ? value : 'CUSTOMER'
}

/**
 * Brand panel of the registration screen: what joining as the chosen role involves.
 * @param {{ role: SelfRegistrationRole }} props
 */
function JoinPanel({ role }) {
  const stakeholder = STAKEHOLDERS[role]
  return (
    <BrandPanel
      label="What joining FreshCart involves"
      eyebrow="Join FreshCart"
      footnote={
        <>
          <Icon name="info" className="size-4" />
          {role === 'CUSTOMER'
            ? 'Customer accounts are ready to use as soon as you sign up.'
            : 'FreshCart reviews every store, restaurant and delivery partner before they go live.'}
        </>
      }
    >
      <div key={role} className="animate-fade-in">
        <BrandPitch
          headline={stakeholder.headline}
          summary={stakeholder.joinSummary}
          items={stakeholder.joinSteps}
          itemsTitle="How it works"
          ordered
          art={<StakeholderArt area={role} />}
        />
      </div>
    </BrandPanel>
  )
}

/** Self-registration with a role picker. Success signs the user in; the guard sends them to their area. */
export function RegisterPage() {
  const { register: registerAccount } = useAuth()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const [formError, setFormError] = useState(null)
  const defaultValues = {
    role: roleFromQuery(searchParams.get('role')),
    fullName: '',
    email: '',
    phone: '',
    password: '',
    confirmPassword: '',
  }
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(registerSchema), defaultValues })

  const selectedRole = useWatch({ control, name: 'role' })

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    const phone = normalizePhone(values.phone.trim())
    try {
      await registerAccount({
        role: values.role,
        fullName: values.fullName.trim(),
        email: values.email.trim(),
        password: values.password,
        ...(phone ? { phone } : {}),
      })
    } catch (error) {
      if (hasErrorCode(error, 'EMAIL_ALREADY_REGISTERED')) {
        setError(
          'email',
          { type: 'server', message: 'This email is already registered. Sign in instead.' },
          { shouldFocus: true },
        )
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues)
      if (!placedOnFields || unmatched.length > 0) {
        setFormError(unmatched.length > 0 ? unmatched.join(' ') : errorMessage(error))
      }
    }
  })

  return (
    <AuthShell wide panel={<JoinPanel role={selectedRole} />}>
      <p className="inline-flex items-center gap-1.5 text-sm font-semibold text-emerald-700">
        <Icon name="sparkle" className="size-4" />
        Get started in minutes
      </p>
      <h1 className="mt-2 text-3xl font-extrabold tracking-tight text-slate-900 sm:text-4xl">Create your account</h1>
      <p className="mt-2 text-sm text-slate-500 sm:text-base">
        Pick how you will use FreshCart. Businesses and delivery partners complete a short onboarding next.
      </p>

      {formError && (
        <Alert tone="danger" className="mt-6" role="alert">
          {formError}
        </Alert>
      )}

      <form onSubmit={(event) => void onSubmit(event)} noValidate className="mt-6 space-y-6">
        <fieldset>
          <legend className="text-xs font-semibold tracking-wide text-slate-500 uppercase">I want to join as</legend>
          <div className="mt-2 grid gap-3 sm:grid-cols-2">
            {SELF_REGISTRATION_ROLES.map((role) => {
              const checked = selectedRole === role
              return (
                <label
                  key={role}
                  className={cn(
                    'group relative flex cursor-pointer items-center gap-3 rounded-2xl border p-3.5 transition-all duration-200',
                    'has-[:focus-visible]:ring-4 has-[:focus-visible]:ring-emerald-500/20',
                    checked
                      ? 'border-emerald-500 bg-emerald-50/50 shadow-md ring-1 shadow-emerald-600/10 ring-emerald-500'
                      : 'border-slate-200 bg-white hover:-translate-y-px hover:border-emerald-300 hover:shadow-sm',
                  )}
                >
                  <input type="radio" value={role} className="sr-only" {...register('role')} />
                  <span
                    className={cn(
                      'flex size-11 shrink-0 items-center justify-center rounded-xl transition-colors',
                      checked ? 'bg-white shadow-sm ring-1 ring-emerald-200' : 'bg-slate-100 group-hover:bg-emerald-50',
                    )}
                    aria-hidden="true"
                  >
                    <Icon name={STAKEHOLDERS[role].icon} className="size-6" />
                  </span>
                  <span className="min-w-0 pr-6">
                    <span className="block text-sm font-semibold text-slate-900">{ROLE_LABELS[role]}</span>
                    <span className="block text-xs text-slate-500">{ROLE_DESCRIPTIONS[role]}</span>
                  </span>
                  {/* A radio dot that fills with a check when chosen. */}
                  <span
                    aria-hidden="true"
                    className={cn(
                      'absolute top-3 right-3 flex size-5 items-center justify-center rounded-full border-2 transition-colors',
                      checked ? 'border-emerald-500 bg-emerald-500 text-white' : 'border-slate-300 bg-white',
                    )}
                  >
                    {checked && <Icon name="check" className="size-3" />}
                  </span>
                </label>
              )
            })}
          </div>
          {errors.role?.message && <p className="mt-2 text-xs font-medium text-red-600">{errors.role.message}</p>}
        </fieldset>

        <div className="grid gap-x-4 gap-y-5 sm:grid-cols-2">
          <IconField
            label="Full name"
            icon="user"
            autoComplete="name"
            required
            error={errors.fullName?.message}
            containerClassName="sm:col-span-2"
            {...register('fullName')}
          />
          <IconField
            label="Email"
            icon="mail"
            type="email"
            inputMode="email"
            autoComplete="email"
            autoCapitalize="none"
            spellCheck={false}
            placeholder="you@example.com"
            required
            error={errors.email?.message}
            {...register('email')}
          />
          <IconField
            label="Mobile number"
            icon="mobile"
            type="tel"
            autoComplete="tel"
            placeholder="+919876543210"
            hint="Optional."
            error={errors.phone?.message}
            {...register('phone')}
          />
          <PasswordField
            label="Password"
            autoComplete="new-password"
            required
            hint="8–64 characters with at least one letter and one digit."
            error={errors.password?.message}
            {...register('password')}
          />
          <PasswordField
            label="Confirm password"
            revealLabel="password confirmation"
            autoComplete="new-password"
            required
            error={errors.confirmPassword?.message}
            {...register('confirmPassword')}
          />
        </div>

        <Button type="submit" className="group h-12! w-full text-base!" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {isSubmitting ? 'Creating account…' : `Create ${ROLE_LABELS[selectedRole].toLowerCase()} account`}
          {!isSubmitting && (
            <Icon name="arrowRight" className="size-5 transition-transform duration-200 group-hover:translate-x-0.5" />
          )}
        </Button>
      </form>

      <p className="mt-8 rounded-2xl bg-slate-50 px-4 py-3.5 text-center text-sm text-slate-600 ring-1 ring-slate-200/70">
        Already have an account?{' '}
        <Link
          to={`/login?role=${selectedRole}`}
          state={location.state}
          className="font-semibold text-emerald-700 underline-offset-4 hover:text-emerald-800 hover:underline"
        >
          Sign in
        </Link>
      </p>
    </AuthShell>
  )
}
