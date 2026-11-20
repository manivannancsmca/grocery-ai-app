import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, ErrorState, PageHeader, Skeleton, Spinner, Switch } from '../../shared/components/ui'
import { Icon, type IconName } from '../../shared/icons'
import { EmailIllustration } from '../../shared/illustrations'
import { cn } from '../../shared/utils/cn'
import {
  EMAIL_CATEGORIES,
  type EmailCategory,
  type EmailPreferences,
  fetchNotificationPreferences,
  notificationPreferenceKeys,
  saveNotificationPreferences,
} from './preferencesApi'

/** A picture for each category (decorative; the label says the same). */
const CATEGORY_ICONS: Record<EmailCategory, IconName> = {
  orders: 'bag',
  account: 'user',
  approvals: 'shield',
}

const CATEGORY_TEXT: Record<EmailCategory, { label: string; description: string }> = {
  orders: {
    label: 'Orders, refunds and returns',
    description: 'Order confirmations, cancellations and deliveries, refunds, and decisions on your returns.',
  },
  account: {
    label: 'Account',
    description: 'When your business or partner account is approved, rejected or suspended.',
  },
  approvals: {
    label: 'Assistant approvals',
    description: 'When an action an assistant prepared waits for your decision.',
  },
}

function samePreferences(a: EmailPreferences, b: EmailPreferences): boolean {
  return EMAIL_CATEGORIES.every((category) => a[category] === b[category])
}

function PreferencesForm({ saved }: { saved: EmailPreferences }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [draft, setDraft] = useState<EmailPreferences>(saved)
  const save = useMutation({
    mutationFn: (email: EmailPreferences) => saveNotificationPreferences({ email }),
    onSuccess: (preferences) => {
      queryClient.setQueryData(notificationPreferenceKeys.all, preferences)
      setDraft(preferences.email)
      toast.success('Your email preferences were saved.')
    },
  })
  const dirty = !samePreferences(draft, saved)

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        if (dirty && !save.isPending) save.mutate(draft)
      }}
      className="space-y-5"
    >
      <fieldset className="space-y-3">
        <legend className="sr-only">Email me about</legend>
        {EMAIL_CATEGORIES.map((category) => (
          <div
            key={category}
            className={cn(
              'flex items-start gap-3.5 rounded-xl border p-4 transition-colors',
              draft[category] ? 'border-emerald-200 bg-emerald-50/40' : 'border-slate-200 bg-white',
            )}
          >
            <span
              aria-hidden="true"
              className={cn(
                'flex size-10 shrink-0 items-center justify-center rounded-xl transition-colors',
                draft[category] ? 'bg-emerald-100' : 'bg-slate-100',
              )}
            >
              <Icon name={CATEGORY_ICONS[category]} className={cn('size-5 transition', !draft[category] && 'opacity-50 grayscale')} />
            </span>
            <Switch
              className="min-w-0 flex-1 flex-row-reverse justify-between gap-4 [&_label]:text-slate-900"
              label={CATEGORY_TEXT[category].label}
              description={CATEGORY_TEXT[category].description}
              checked={draft[category]}
              disabled={save.isPending}
              onChange={(checked) => setDraft((current) => ({ ...current, [category]: checked }))}
            />
          </div>
        ))}
      </fieldset>
      {save.isError && (
        <Alert tone="danger" role="alert">
          Could not save your preferences: {save.error.message}
        </Alert>
      )}
      <div className="flex flex-wrap items-center justify-end gap-3 border-t border-slate-100 pt-4">
        {dirty && (
          <span className="mr-auto inline-flex items-center gap-1.5 text-sm font-medium text-amber-700">
            <span aria-hidden="true" className="size-2 rounded-full bg-amber-500" />
            You have unsaved changes.
          </span>
        )}
        <Button variant="secondary" disabled={!dirty || save.isPending} onClick={() => setDraft(saved)}>
          Reset
        </Button>
        <Button type="submit" disabled={!dirty || save.isPending}>
          {save.isPending && <Spinner size="sm" className="[&_svg]:text-white" />}
          Save preferences
        </Button>
      </div>
    </form>
  )
}

/** `/notifications/preferences` — which notifications are also emailed (every signed-in role; all on by default). */
export function NotificationPreferencesPage() {
  const preferences = useQuery({ queryKey: notificationPreferenceKeys.all, queryFn: fetchNotificationPreferences })

  let content
  if (preferences.isPending) {
    content = (
      <div role="status" aria-label="Loading your preferences" className="space-y-3">
        <Skeleton className="h-20 w-full rounded-xl" />
        <Skeleton className="h-20 w-full rounded-xl" />
        <Skeleton className="h-20 w-full rounded-xl" />
      </div>
    )
  } else if (preferences.isError) {
    content = (
      <ErrorState
        title="Could not load your preferences"
        message={preferences.error.message}
        correlationId={preferences.error instanceof ApiError ? preferences.error.correlationId : null}
        onRetry={() => void preferences.refetch()}
      />
    )
  } else {
    const email = preferences.data.email
    // A save (or another tab) that changes the saved values starts the form from them again.
    content = <PreferencesForm key={EMAIL_CATEGORIES.map((category) => String(email[category])).join()} saved={email} />
  }

  return (
    <div className="w-full">
      <Link
        to="/notifications"
        className="mb-4 inline-flex items-center gap-1.5 rounded-lg text-sm font-semibold text-slate-500 transition-colors hover:text-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
      >
        <Icon name="arrowLeft" className="size-4" />
        Notifications
      </Link>
      <PageHeader
        title="Email preferences"
        description="Choose which notifications we also send to your email address."
        illustration={<EmailIllustration />}
      />
      <section aria-labelledby="email-preferences-title" className="animate-fade-up rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:p-6">
        <header className="mb-5 flex items-start gap-3">
          <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-50">
            <Icon name="mail" className="size-[1.15rem]" />
          </span>
          <div className="min-w-0">
            <h2 id="email-preferences-title" className="text-base font-semibold text-slate-900">
              Email me about
            </h2>
            <p className="mt-0.5 flex items-center gap-1.5 text-sm text-slate-500">
              <Icon name="bell" className="size-4" />
              In-app notifications are always on; they appear under the bell.
            </p>
          </div>
        </header>
        {content}
      </section>
    </div>
  )
}
