import { Navigate } from 'react-router'
import { acknowledgeSuspension } from '../../shared/auth/session'
import { useAuth } from '../../shared/auth/useAuth'
import { Alert, Button } from '../../shared/components/ui'
import { AuthCard } from './AuthCard'

/** Shown after `403 ACCOUNT_SUSPENDED` on login, refresh or any request. */
export function AccountSuspendedPage() {
  const { status, suspendedMessage } = useAuth()

  // Not (or no longer) suspended: e.g. after "Back to sign in" or a direct visit.
  if (status !== 'suspended') return <Navigate to="/login" replace />

  return (
    <AuthCard
      title="Account suspended"
      description="You can't use FreshCart with this account right now."
      tone="danger"
      icon="lock"
    >
      <div className="space-y-5">
        <Alert tone="danger" role="alert" title="Your account has been suspended">
          {suspendedMessage ?? 'An administrator suspended this account.'}
        </Alert>
        <p className="text-center text-sm text-slate-600">
          If you think this is a mistake, contact FreshCart support and quote the email address you signed in with.
        </p>
        <Button variant="secondary" className="h-11! w-full text-base!" onClick={acknowledgeSuspension}>
          Back to sign in
        </Button>
      </div>
    </AuthCard>
  )
}
