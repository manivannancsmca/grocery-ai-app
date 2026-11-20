import { Link } from 'react-router'
import { hasErrorCode } from '../../shared/api/problemDetail'
import { COUNT_TILE_CLASS, FeatureCard, Spinner, TILE_LINK_CLASS } from '../../shared/components/ui'
import { VENDOR_NOT_ACTIVE } from './api'
import { useVendorProductCount } from './hooks'

/** @typedef {import('./api').ProductStatus} ProductStatus */

/** @param {{ status: ProductStatus, label: string }} props */
function Count({ status, label }) {
  const count = useVendorProductCount(status)
  return (
    <Link to={`/vendor/products?status=${status}`} className={COUNT_TILE_CLASS}>
      <span className="block text-2xl font-bold tracking-tight text-slate-900">
        {count.isPending ? (
          <Spinner size="sm" label={`Loading ${label.toLowerCase()} count`} />
        ) : count.isError ? (
          '—'
        ) : (
          count.data
        )}
      </span>
      <span className="text-xs font-medium text-slate-500">{label}</span>
    </Link>
  )
}

/** Vendor dashboard tile: product counts per status and shortcuts. */
export function ProductsSummaryCard() {
  const drafts = useVendorProductCount('DRAFT')
  const notActive = hasErrorCode(drafts.error, VENDOR_NOT_ACTIVE)

  return (
    <FeatureCard
      title="Products"
      icon="bag"
      accent="emerald"
      className="sm:col-span-2 lg:col-span-1"
      action={
        <Link to="/vendor/products/new" className="font-semibold text-emerald-700 hover:text-emerald-900">
          Add product
        </Link>
      }
    >
      {notActive ? (
        <p className="text-sm text-slate-500">
          Your catalog opens in a moment, once your approval reaches your session.
        </p>
      ) : (
        <div className="grid grid-cols-3 gap-2 text-center">
          <Count status="PUBLISHED" label="Published" />
          <Count status="DRAFT" label="Drafts" />
          <Count status="UNPUBLISHED" label="Unpublished" />
        </div>
      )}
      <Link to="/vendor/products" className={TILE_LINK_CLASS}>
        Manage products →
      </Link>
    </FeatureCard>
  )
}
