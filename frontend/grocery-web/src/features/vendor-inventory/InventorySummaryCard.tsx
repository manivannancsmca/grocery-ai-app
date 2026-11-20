import { Link } from 'react-router'
import { hasErrorCode } from '../../shared/api/problemDetail'
import { COUNT_TILE_CLASS, FeatureCard, Spinner, TILE_LINK_CLASS } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { useInventorySummary } from './hooks'

function Count({ to, label, value, loading, tone }: { to: string; label: string; value: number | undefined; loading: boolean; tone: string }) {
  return (
    <Link to={to} className={COUNT_TILE_CLASS}>
      <span className={cn('block text-2xl font-bold tracking-tight', value !== undefined && value > 0 ? tone : 'text-slate-900')}>
        {loading ? <Spinner size="sm" label={`Loading ${label.toLowerCase()} count`} /> : (value?.toLocaleString('en-IN') ?? '—')}
      </span>
      <span className="text-xs font-medium text-slate-500">{label}</span>
    </Link>
  )
}

/** Vendor dashboard tile: products that are low on stock or out of stock, linking to the filtered inventory. */
export function InventorySummaryCard() {
  const summary = useInventorySummary()
  const notActive = hasErrorCode(summary.error, 'VENDOR_NOT_ACTIVE')

  return (
    <FeatureCard title="Inventory" icon="box" accent="amber">
      {notActive ? (
        <p className="text-sm text-slate-500">Stock opens up once your approval reaches your session.</p>
      ) : (
        <div className="grid grid-cols-2 gap-2 text-center">
          <Count
            to="/vendor/inventory?filter=LOW_STOCK"
            label="Low stock"
            value={summary.data?.lowStock}
            loading={summary.isPending}
            tone="text-amber-700"
          />
          <Count
            to="/vendor/inventory?filter=OUT_OF_STOCK"
            label="Out of stock"
            value={summary.data?.outOfStock}
            loading={summary.isPending}
            tone="text-red-700"
          />
        </div>
      )}
      <Link to="/vendor/inventory" className={TILE_LINK_CLASS}>
        Manage stock →
      </Link>
    </FeatureCard>
  )
}
