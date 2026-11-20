import { Link } from 'react-router'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatRelativeTime } from '../../shared/utils/format'
import { CategoryIcon } from '../catalog/categoryVisuals'
import { categoryLabel, vendorProductPath } from './api'

/** @typedef {import('./api').ProductView} ProductView */

/** The pieces of a product shared by the products table and the product cards. */

/**
 * Category with its icon; `full` shows the whole path ("Fruits & Vegetables › Fresh Vegetables"), else the leaf.
 * @param {{ product: ProductView, full?: boolean, className?: string }} props
 */
export function ProductCategory({ product, full = true, className }) {
  const category = product.category
  return (
    <span className={cn('inline-flex min-w-0 items-center gap-2', className)}>
      <span
        aria-hidden="true"
        className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-emerald-50 ring-1 ring-emerald-100"
      >
        <CategoryIcon name={category?.name ?? ''} className="size-4" />
      </span>
      <span className="min-w-0">
        <span>{full ? categoryLabel(category) : (category?.name ?? '—')}</span>
        {category && !category.active && <span className="ml-1 text-xs text-amber-700">(inactive)</span>}
      </span>
    </span>
  )
}

/**
 * Pack size, e.g. "500 g".
 * @param {{ label: string }} props
 */
export function UnitPill({ label }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-md bg-slate-100 px-2 py-0.5 text-xs font-semibold whitespace-nowrap text-slate-700 ring-1 ring-slate-200/70 ring-inset">
      <Icon name="scale" className="size-3" />
      {label}
    </span>
  )
}

/**
 * @typedef {object} ListingGapChipsProps
 * @property {ProductView} product
 * @property {boolean} noPrice
 * @property {boolean} noStock
 */

/**
 * A published product customers cannot buy: "No price" (→ Pricing) and/or "Out of stock" (→ Inventory).
 * @param {ListingGapChipsProps} props
 */
export function ListingGapChips({ product, noPrice, noStock }) {
  if (!noPrice && !noStock) return null
  const search = encodeURIComponent(product.sku ?? product.name)
  const chip =
    'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset transition-colors'
  return (
    <span className="flex flex-wrap gap-1.5">
      {noPrice && (
        <Link
          to={`/vendor/pricing?q=${search}`}
          className={cn(chip, 'bg-amber-50 text-amber-800 ring-amber-200 hover:bg-amber-100')}
          aria-label={`Set a price for ${product.name}`}
        >
          <Icon name="tag" className="size-3" />
          No price
        </Link>
      )}
      {noStock && (
        <Link
          to={`/vendor/inventory?q=${search}`}
          className={cn(chip, 'bg-red-50 text-red-700 ring-red-200 hover:bg-red-100')}
          aria-label={`Add stock for ${product.name}`}
        >
          <Icon name="box" className="size-3" />
          Out of stock
        </Link>
      )}
    </span>
  )
}

/**
 * "Edit" (or "View" for an archived product), named after the product.
 * @param {{ product: ProductView, className?: string }} props
 */
export function EditProductLink({ product, className }) {
  const archived = product.status === 'ARCHIVED'
  return (
    <Link
      to={vendorProductPath(product)}
      aria-label={`${archived ? 'View' : 'Edit'} ${product.name}`}
      className={cn(
        'inline-flex h-8 items-center justify-center gap-1.5 rounded-lg bg-white px-3 text-sm font-medium text-slate-700 shadow-xs ring-1 ring-slate-300 ring-inset transition-colors hover:bg-slate-50 hover:text-slate-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-400',
        className,
      )}
    >
      <Icon name={archived ? 'eye' : 'pencil'} className="size-3.5" />
      {archived ? 'View' : 'Edit'}
    </Link>
  )
}

/**
 * "2 h ago", with the exact time on hover.
 * @param {{ at: string, className?: string }} props
 */
export function UpdatedAgo({ at, className }) {
  return (
    <time dateTime={at} title={`Updated ${formatDateTime(at)}`} className={cn('whitespace-nowrap', className)}>
      {formatRelativeTime(at)}
    </time>
  )
}
