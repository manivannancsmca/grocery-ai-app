import { Link } from 'react-router'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { ProductImage } from '../catalog/ProductImage'
import { type ProductView, vendorProductPath } from './api'
import { EditProductLink, ListingGapChips, ProductCategory, UnitPill, UpdatedAgo } from './ProductListBits'
import { ProductStatusActions, ProductStatusBadge } from './ProductStatusActions'

interface VendorProductCardProps {
  product: ProductView
  noPrice: boolean
  noStock: boolean
  /** `tile`: a picture on top (the grid); `row`: a picture on the left (phones). */
  layout?: 'tile' | 'row'
}

/** One of the vendor's products as a card: photo, status, category, name, brand, pack size, gaps and actions. */
export function VendorProductCard({ product, noPrice, noStock, layout = 'tile' }: VendorProductCardProps) {
  const brand = product.brand?.name
  const name = (
    <h3 className="text-sm leading-snug font-semibold text-slate-900">
      <Link to={vendorProductPath(product)} className="hover:text-emerald-700 focus-visible:underline focus-visible:outline-none">
        {product.name}
      </Link>
    </h3>
  )
  const details = (
    <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500">
      <UnitPill label={product.unitLabel} />
      {brand && <span className="truncate">{brand}</span>}
      {product.sku && <span className="font-mono text-[11px] text-slate-400">SKU {product.sku}</span>}
    </p>
  )
  const actions = (
    // flex-wrap: on a phone the row card's text column is narrow; the status buttons go to their own line instead of
    // pushing the card past the screen.
    <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 pt-3">
      <EditProductLink product={product} />
      <ProductStatusActions product={product} />
    </div>
  )

  if (layout === 'row') {
    return (
      <article
        aria-label={product.name}
        className={cn('flex gap-3 rounded-2xl border bg-white p-3 shadow-sm', noPrice || noStock ? 'border-amber-200' : 'border-slate-200/80')}
      >
        <ProductImage src={product.imageUrls[0]} alt="" className="size-20 shrink-0 rounded-xl border border-slate-100 bg-slate-50 object-contain p-1" iconClassName="size-7" />
        <div className="min-w-0 flex-1 space-y-1.5">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <ProductCategory product={product} full={false} className="text-[11px] font-semibold tracking-wide text-emerald-700 uppercase [&>span:first-child]:size-5 [&>span:first-child]:rounded-md" />
              {name}
            </div>
            <FoodTypeMark foodType={product.foodType} className="mt-1 shrink-0" />
          </div>
          {details}
          <div className="flex flex-wrap items-center gap-1.5">
            <ProductStatusBadge status={product.status} />
            <ListingGapChips product={product} noPrice={noPrice} noStock={noStock} />
          </div>
          {actions}
        </div>
      </article>
    )
  }

  return (
    <article
      aria-label={product.name}
      className={cn(
        'group flex flex-col overflow-hidden rounded-2xl border bg-white shadow-sm shadow-slate-900/[0.04] transition-all duration-300 hover:-translate-y-0.5 hover:shadow-lg hover:shadow-slate-900/[0.08]',
        noPrice || noStock ? 'border-amber-200' : 'border-slate-200/80',
      )}
    >
      <div className="relative aspect-[4/3] overflow-hidden bg-gradient-to-br from-slate-50 via-white to-emerald-50/60">
        <ProductImage
          src={product.imageUrls[0]}
          alt=""
          className="size-full object-contain p-5 transition-transform duration-500 group-hover:scale-105"
          iconClassName="size-12"
        />
        <span className="absolute top-3 left-3">
          <ProductStatusBadge status={product.status} />
        </span>
        {product.foodType && (
          <span className="absolute top-3 right-3 rounded-md bg-white/90 p-1 shadow-sm">
            <FoodTypeMark foodType={product.foodType} />
          </span>
        )}
      </div>
      <div className="flex flex-1 flex-col gap-2 p-4">
        <ProductCategory product={product} full={false} className="text-[11px] font-semibold tracking-wide text-emerald-700 uppercase [&>span:first-child]:size-5 [&>span:first-child]:rounded-md" />
        {name}
        {details}
        <ListingGapChips product={product} noPrice={noPrice} noStock={noStock} />
        <p className="mt-auto flex items-center gap-1 pt-1 text-xs text-slate-400">
          <Icon name="clock" className="size-3" />
          <span className="sr-only">Updated </span>
          <UpdatedAgo at={product.updatedAt} />
        </p>
        {actions}
      </div>
    </article>
  )
}
