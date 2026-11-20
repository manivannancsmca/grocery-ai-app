import { Link } from 'react-router'
import { hasErrorCode } from '../../shared/api/problemDetail'
import { COUNT_TILE_CLASS, FeatureCard, Spinner, TILE_LINK_CLASS } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { usePricingSummary } from './hooks'

function Count({ to, label, value, loading }: { to: string; label: string; value: number | undefined; loading: boolean }) {
  return (
    <Link to={to} className={COUNT_TILE_CLASS}>
      <span className={cn('block text-2xl font-bold tracking-tight', value !== undefined && value > 0 ? 'text-amber-700' : 'text-slate-900')}>
        {loading ? <Spinner size="sm" label={`Loading ${label.toLowerCase()} count`} /> : (value?.toLocaleString('en-IN') ?? '—')}
      </span>
      <span className="text-xs font-medium text-slate-500">{label}</span>
    </Link>
  )
}

/** Vendor dashboard tile: products without a price and price changes waiting for an admin. */
export function PricingSummaryCard() {
  const summary = usePricingSummary()
  const notActive = hasErrorCode(summary.error, 'VENDOR_NOT_ACTIVE')

  return (
    <FeatureCard title="Pricing" icon="tag" accent="violet">
      {notActive ? (
        <p className="text-sm text-slate-500">Prices open up once your approval reaches your session.</p>
      ) : (
        <div className="grid grid-cols-2 gap-2 text-center">
          <Count to="/vendor/pricing?filter=UNPRICED" label="Unpriced" value={summary.data?.unpriced} loading={summary.isPending} />
          <Count
            to="/vendor/pricing?filter=PENDING_APPROVAL"
            label="Pending approval"
            value={summary.data?.pendingApproval}
            loading={summary.isPending}
          />
        </div>
      )}
      <Link to="/vendor/pricing" className={TILE_LINK_CLASS}>
        Manage prices →
      </Link>
    </FeatureCard>
  )
}
