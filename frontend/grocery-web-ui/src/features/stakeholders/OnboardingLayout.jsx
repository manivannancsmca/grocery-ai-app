import { Alert, Button, Spinner } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { StatusBanner } from './StatusBanner'
import { isEditableStatus } from './types'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('./types').StakeholderLifecycle} StakeholderLifecycle */

/**
 * @typedef {object} FormSectionProps
 * @property {string} title
 * @property {string} [description]
 * @property {ReactNode} children
 */

/**
 * A titled block of an onboarding form.
 * @param {FormSectionProps} props
 */
export function FormSection({ title, description, children }) {
  return (
    <section className="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
      <header className="mb-4">
        <h2 className="text-base font-semibold text-slate-900">{title}</h2>
        {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
      </header>
      {children}
    </section>
  )
}

/**
 * @typedef {object} OnboardingLayoutProps
 * @property {StakeholderLifecycle} profile
 * @property {string} noun
 * @property {string | null} formError
 * @property {'draft' | 'submit' | null} pendingAction
 * @property {() => void} onSaveDraft
 * @property {() => void} onSubmit
 * @property {ReactNode} children Form sections. They are disabled (read-only) unless the status allows editing.
 */

/**
 * Status banner + form + action bar shared by the vendor, restaurant and delivery-partner onboarding.
 * Editing is possible only in DRAFT and REJECTED; otherwise the form is shown read-only.
 * @param {OnboardingLayoutProps} props
 */
export function OnboardingLayout({ profile, noun, formError, pendingAction, onSaveDraft, onSubmit, children }) {
  const editable = isEditableStatus(profile.status)
  const busy = pendingAction !== null
  // On phones the actions stick to the bottom of the screen: the "Ask AI" button moves above them.

  return (
    <div className="space-y-5">
      <StatusBanner profile={profile} noun={noun} />
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <form
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          onSubmit()
        }}
        className="space-y-5"
      >
        <fieldset disabled={!editable || busy} className={cn('space-y-5', !editable && 'opacity-90')}>
          <legend className="sr-only">{noun} details</legend>
          {children}
        </fieldset>
        {editable && (
          <div className="sticky bottom-0 z-10 -mx-4 flex flex-col-reverse gap-2 border-t border-slate-200 bg-white/95 px-4 py-3 backdrop-blur sm:static sm:mx-0 sm:flex-row sm:justify-end sm:rounded-xl sm:border sm:px-5">
            <Button variant="secondary" onClick={onSaveDraft} disabled={busy}>
              {pendingAction === 'draft' && <Spinner size="sm" />}
              Save draft
            </Button>
            <Button type="submit" disabled={busy}>
              {pendingAction === 'submit' && <Spinner size="sm" className="[&_svg]:text-white" />}
              Submit for approval
            </Button>
          </div>
        )}
      </form>
    </div>
  )
}
