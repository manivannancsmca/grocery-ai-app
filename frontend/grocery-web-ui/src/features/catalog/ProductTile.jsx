import { Link } from 'react-router'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { Badge, Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatPrice } from '../../shared/utils/format'
import { RatingBadge } from '../reviews/StarRating'
import { ProductImage } from './ProductImage'
import { formatDistance } from '../stores/api'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('./api').ProductCard} ProductCard */
/** @typedef {import('./api').ProductPrice} ProductPrice */

/**
 * @typedef {object} PriceTagProps
 * @property {ProductPrice | null | undefined} price null / absent = the product has no price yet.
 * @property {'sm' | 'lg'} [size]
 * @property {string} [className]
 */

/**
 * Selling price, the MRP struck through and "x% off" when discounted, or "Price not available".
 * @param {PriceTagProps} props
 */
export function PriceTag({ price, size = 'sm', className }) {
  if (!price) {
    return (
      <p className={cn('font-medium text-slate-500', size === 'lg' ? 'text-base' : 'text-xs', className)}>
        Price not available
      </p>
    )
  }
  const discount = price.discountPercent ?? 0
  const discounted = price.mrp > price.sellingPrice
  return (
    <p className={cn('flex flex-wrap items-baseline gap-x-2 gap-y-0.5', className)}>
      <span
        className={cn('font-bold tracking-tight text-slate-900 tabular-nums', size === 'lg' ? 'text-3xl' : 'text-base')}
      >
        {formatPrice(price.sellingPrice, price.currency)}
      </span>
      {discounted && (
        <span className={cn('text-slate-400 tabular-nums line-through', size === 'lg' ? 'text-base' : 'text-xs')}>
          <span className="sr-only">MRP </span>
          {formatPrice(price.mrp, price.currency)}
        </span>
      )}
      {discount > 0 && (
        <span
          className={cn(
            'self-center rounded-md bg-emerald-50 font-bold text-emerald-700 ring-1 ring-emerald-200/70 ring-inset',
            size === 'lg' ? 'px-2 py-0.5 text-sm' : 'px-1.5 text-[0.7rem] leading-5',
          )}
        >
          {discount}% off
        </span>
      )}
    </p>
  )
}

/**
 * "Out of stock" — shown on products that are visible but cannot be bought right now.
 * @param {{ className?: string }} props
 */
export function OutOfStockBadge({ className }) {
  return (
    <Badge tone="red" className={className}>
      Out of stock
    </Badge>
  )
}

/**
 * Amber corner ribbon on a discounted product's photo (decorative: the price block says the same in words).
 * @param {{ percent: number, className?: string }} props
 */
export function DiscountRibbon({ percent, className }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'pointer-events-none inline-flex flex-col items-center rounded-lg bg-amber-400 px-1.5 py-1 leading-none font-extrabold text-amber-950 shadow-sm shadow-amber-900/20',
        className,
      )}
    >
      <span className="text-xs tabular-nums">{percent}%</span>
      <span className="mt-0.5 text-[0.6rem] tracking-wider">OFF</span>
    </span>
  )
}

/**
 * @typedef {object} ProductTileProps
 * @property {ProductCard} product
 * @property {ReactNode} [action] Control below the price (Phase 5: "Add" / quantity stepper); it sits above the card-wide link.
 * @property {ReactNode} [flag] Badge in the photo's top-right corner (e.g. a live "Price dropped").
 * @property {number} [storeDistanceKm] Road km from the delivery location to the product's store, when known ("Tambaram Super Bazaar · 4.3 km").
 */

/**
 * One search hit: photo, category, name, unit, brand, food-type mark, vendor, price and stock. The whole card is
 * clickable.
 * @param {ProductTileProps} props
 */
export function ProductTile({ product, action, flag, storeDistanceKm }) {
  const outOfStock = product.inStock === false
  const discount =
    product.price && product.price.mrp > product.price.sellingPrice ? (product.price.discountPercent ?? 0) : 0
  return (
    // border-slate-300 (darker than the page background) so each tile stands out; it turns green on hover.
    <article className="group relative flex h-full flex-col rounded-2xl border border-slate-300 bg-white p-2 shadow-sm shadow-slate-900/[0.03] transition duration-200 focus-within:ring-2 focus-within:ring-emerald-500/40 hover:-translate-y-0.5 hover:border-emerald-400 hover:shadow-md motion-reduce:hover:translate-y-0">
      <div className="relative overflow-hidden rounded-xl bg-slate-50">
        <ProductImage
          src={product.primaryImageUrl}
          alt=""
          className={cn(
            'aspect-square w-full transition duration-300 group-hover:scale-[1.04] motion-reduce:group-hover:scale-100',
            outOfStock && 'opacity-60 grayscale',
          )}
          iconClassName="size-12"
        />
        {outOfStock ? (
          <OutOfStockBadge className="absolute top-2 left-2 shadow-sm" />
        ) : (
          discount > 0 && <DiscountRibbon percent={discount} className="absolute top-2 left-2" />
        )}
        {flag && <div className="absolute top-2 right-2">{flag}</div>}
      </div>
      <div className="flex flex-1 flex-col gap-1 px-1 pt-2.5 pb-0.5">
        {product.category?.name && (
          <p className="truncate text-[11px] font-semibold tracking-wide text-emerald-700 uppercase">
            <span className="sr-only">Category: </span>
            {product.category.name}
          </p>
        )}
        <div className="flex items-start gap-1.5">
          <FoodTypeMark foodType={product.foodType} className="mt-0.5 shrink-0" />
          <h3 className="line-clamp-2 min-w-0 text-sm leading-snug font-semibold text-slate-900">
            <Link
              to={`/products/${encodeURIComponent(product.id)}`}
              className="after:absolute after:inset-0 after:rounded-2xl focus:outline-none group-hover:text-emerald-800"
            >
              {product.name}
            </Link>
          </h3>
        </div>
        <p className="text-xs text-slate-500">
          {product.unitLabel}
          {product.brand && <> · {product.brand.name}</>}
        </p>
        <RatingBadge rating={product.rating} count={product.ratingCount} />
        <p className="flex min-w-0 items-center gap-1 text-xs text-slate-500">
          <Icon name="store" className="size-3.5" />
          <span className="truncate">
            <span className="sr-only">Sold by </span>
            {product.vendor.name}
          </span>
          {storeDistanceKm !== undefined && (
            <span className="shrink-0 font-medium text-emerald-700">
              <span aria-hidden="true">· </span>
              <span className="sr-only">, </span>
              {formatDistance(storeDistanceKm)}
              <span className="sr-only"> away</span>
            </span>
          )}
        </p>
        <div className="mt-auto pt-2">
          <PriceTag price={product.price} />
        </div>
        {action && <div className="relative z-10 pt-2">{action}</div>}
      </div>
    </article>
  )
}

export function ProductTileSkeleton() {
  return (
    <div className="rounded-2xl border border-slate-300 bg-white p-2 shadow-sm">
      <Skeleton className="aspect-square w-full rounded-xl" />
      <div className="space-y-2 px-1 pt-3 pb-1">
        <Skeleton className="h-4 w-3/4" />
        <Skeleton className="h-3 w-1/2" />
        <Skeleton className="h-3 w-1/3" />
        <Skeleton className="mt-3 h-5 w-2/5" />
        <Skeleton className="mt-2 h-8 w-full rounded-xl" />
      </div>
    </div>
  )
}
