import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { Badge, Switch } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatPrice } from '../../shared/utils/format'
import { ProductImage } from '../catalog/ProductImage'
import { MenuIconButton } from './MenuIconButton'
import { menuIconFor } from './menuView'

/** @typedef {import('./api').MenuItemView} MenuItemView */

/**
 * @typedef {object} MenuDishCardProps
 * @property {MenuItemView} item
 * @property {boolean} readOnly Suspended restaurants: no edit / delete, and the switch is off-limits.
 * @property {boolean} switching The availability request for this dish is running.
 * @property {(available: boolean) => void} onAvailabilityChange
 * @property {() => void} onEdit
 * @property {() => void} onDelete
 */

/**
 * One dish in the owner's menu grid: picture, food mark, name, price, description, the sold-out switch and edit / delete.
 * @param {MenuDishCardProps} props
 */
export function MenuDishCard({ item, readOnly, switching, onAvailabilityChange, onEdit, onDelete }) {
  const soldOut = !item.available
  return (
    <li
      className={cn(
        'group flex flex-col gap-2 rounded-xl border p-3 transition-all duration-300 @xl:gap-2.5 @xl:p-3.5',
        soldOut
          ? 'border-dashed border-slate-300 bg-slate-50/80'
          : 'border-slate-200/80 bg-gradient-to-b from-white to-slate-50/50 hover:-translate-y-0.5 hover:border-emerald-200 hover:shadow-lg hover:shadow-slate-900/[0.06]',
      )}
    >
      <div className="flex items-start gap-3">
        {item.imageUrl ? (
          <ProductImage
            src={item.imageUrl}
            alt=""
            className={cn('size-12 shrink-0 rounded-xl', soldOut && 'opacity-60 grayscale')}
            iconClassName="size-6"
          />
        ) : (
          <span
            aria-hidden="true"
            className={cn(
              'flex size-12 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-amber-50 to-orange-50 ring-1 ring-amber-100 transition-transform duration-300 group-hover:scale-105 group-hover:-rotate-3',
              soldOut && 'opacity-60 grayscale',
            )}
          >
            <Icon name={menuIconFor(item.name)} className="size-7" />
          </span>
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-start gap-1.5">
            <FoodTypeMark foodType={item.foodType} className="mt-0.5" />
            <h3
              className={cn(
                'min-w-0 text-sm leading-snug font-semibold break-words',
                soldOut ? 'text-slate-500' : 'text-slate-900',
              )}
            >
              {item.name}
            </h3>
          </div>
          <p className="mt-1 flex flex-wrap items-center gap-2">
            <span className={cn('text-base font-bold tabular-nums', soldOut ? 'text-slate-400' : 'text-slate-900')}>
              {formatPrice(item.price)}
            </span>
            {soldOut && <Badge tone="red">Sold out</Badge>}
          </p>
          {item.description && (
            <p title={item.description} className="mt-1 line-clamp-2 text-xs leading-relaxed text-slate-500">
              {item.description}
            </p>
          )}
        </div>
      </div>

      <div className="mt-auto flex items-center justify-between gap-2 border-t border-slate-100 pt-2">
        <Switch
          size="sm"
          className="items-center gap-2"
          label={item.available ? 'Available' : 'Sold out'}
          ariaLabel={`${item.name} available`}
          checked={item.available}
          pending={switching}
          disabled={readOnly}
          onChange={onAvailabilityChange}
        />
        {!readOnly && (
          <div className="flex items-center gap-0.5">
            <MenuIconButton icon="pencil" label={`Edit ${item.name}`} onClick={onEdit} />
            <MenuIconButton icon="trash" label={`Delete ${item.name}`} tone="danger" onClick={onDelete} />
          </div>
        )}
      </div>
    </li>
  )
}
