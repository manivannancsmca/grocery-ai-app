import { hasErrorCode } from '../../shared/api/problemDetail'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { Badge, Spinner } from '../../shared/components/ui'
import { formatPrice } from '../../shared/utils/format'
import { useAdminRestaurantMenu } from './hooks'

/** Read-only menu for the admin restaurant drawer, so reviewers can check it before approving. */
export function AdminMenuPreview({ restaurantId }: { restaurantId: string }) {
  const menu = useAdminRestaurantMenu(restaurantId)

  if (menu.isPending) {
    return (
      <p className="flex items-center gap-2 text-sm text-slate-500">
        <Spinner size="sm" label="Loading menu" /> Loading menu…
      </p>
    )
  }
  if (menu.isError) {
    if (hasErrorCode(menu.error, 'RESTAURANT_NOT_FOUND')) return <p className="text-sm text-slate-500">No menu yet.</p>
    return <p className="text-sm text-red-600">Could not load the menu: {menu.error.message}</p>
  }
  if (menu.data.categories.length === 0) return <p className="text-sm text-slate-500">The owner has not added a menu yet.</p>

  return (
    <div className="space-y-4">
      <p className="text-xs text-slate-500">
        {menu.data.itemCount} dish{menu.data.itemCount === 1 ? '' : 'es'} · {menu.data.acceptingOrders ? 'accepting orders' : 'not accepting orders'}
      </p>
      {menu.data.categories.map((category) => (
        <div key={category.id}>
          <h4 className="text-sm font-semibold text-slate-800">{category.name}</h4>
          {category.items.length === 0 ? (
            <p className="text-xs text-slate-400">No dishes.</p>
          ) : (
            <ul className="mt-1 divide-y divide-slate-100 rounded-lg border border-slate-100">
              {category.items.map((item) => (
                <li key={item.id} className="flex items-center gap-2 px-3 py-1.5 text-sm">
                  <FoodTypeMark foodType={item.foodType} />
                  <span className="min-w-0 flex-1 truncate text-slate-700">{item.name}</span>
                  {!item.available && <Badge tone="red">Sold out</Badge>}
                  <span className="font-medium text-slate-900 tabular-nums">{formatPrice(item.price)}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
    </div>
  )
}
