import { Link } from 'react-router'
import { FeatureCard, Spinner } from '../../shared/components/ui'
import { useMyMenu } from './hooks'

/** Restaurant dashboard tile: menu size, sold-out dishes and a shortcut to the editor. */
export function MenuSummaryCard() {
  const menu = useMyMenu()
  const soldOut = menu.data?.categories.reduce((count, category) => count + category.items.filter((item) => !item.available).length, 0) ?? 0

  return (
    <FeatureCard
      title="Menu"
      icon="menuBook"
      accent="amber"
      action={
        <Link to="/restaurant/menu" className="font-semibold text-emerald-700 hover:text-emerald-900">
          Edit menu
        </Link>
      }
    >
      {menu.isPending ? (
        <p className="flex items-center gap-2 text-sm text-slate-500">
          <Spinner size="sm" label="Loading menu" /> Loading…
        </p>
      ) : menu.isError ? (
        <p className="text-sm text-slate-500">The menu summary is unavailable right now.</p>
      ) : (
        <p className="text-sm text-slate-600">
          <span className="text-2xl font-bold tracking-tight text-slate-900">{menu.data.itemCount}</span> dish
          {menu.data.itemCount === 1 ? '' : 'es'} in {menu.data.categories.length} categor
          {menu.data.categories.length === 1 ? 'y' : 'ies'}
          {soldOut > 0 && <span className="block text-xs text-amber-700">{soldOut} sold out right now</span>}
        </p>
      )}
    </FeatureCard>
  )
}
