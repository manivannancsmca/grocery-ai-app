import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { ProductCartControl } from '../../../features/cart/CartItemControl'
import { PRODUCT_NOT_FOUND } from '../../../features/catalog/api'
import { useProduct } from '../../../features/catalog/hooks'
import { ProductGallery } from '../../../features/catalog/ProductGallery'
import { DiscountRibbon, OutOfStockBadge, PriceTag } from '../../../features/catalog/ProductTile'
import { RatingLink, REVIEWS_ANCHOR } from '../../../features/reviews/RatingLink'
import { ReviewsSection } from '../../../features/reviews/ReviewsSection'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { FoodTypeMark } from '../../../shared/components/FoodTypeMark'
import { Badge, EmptyState, ErrorState, Skeleton } from '../../../shared/components/ui'
import { Icon, type IconName } from '../../../shared/icons'
import { SearchIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'
import { formatPrice } from '../../../shared/utils/format'
import { FOOD_TYPE_LABELS } from '../../../shared/utils/foodType'

const FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

function categoryLink(id: string): string {
  return `/search?categoryId=${encodeURIComponent(id)}`
}

/** Purely informational promises shown under the buy box. */
const REASSURANCES: Array<{ title: string; text: string; icon: IconName; tint: string }> = [
  { title: 'Fast delivery', text: 'From a store nearby', icon: 'bolt', tint: 'bg-amber-100' },
  { title: 'Fresh quality', text: 'Packed with care', icon: 'leaf', tint: 'bg-emerald-100' },
  { title: 'Easy returns', text: 'Right from your orders', icon: 'return', tint: 'bg-sky-100' },
]

function SectionHeading({ id, icon, children }: { id: string; icon: IconName; children: ReactNode }) {
  return (
    <h2 id={id} className="flex items-center gap-2 text-base font-semibold text-slate-900">
      <span className="flex size-7 items-center justify-center rounded-lg bg-emerald-50">
        <Icon name={icon} className="size-4" />
      </span>
      {children}
    </h2>
  )
}

/** `/products/:productId` — public product page. */
export function ProductDetailPage() {
  const { productId = '' } = useParams()
  const product = useProduct(productId)

  if (product.isPending) {
    return (
      <div role="status" aria-label="Loading product" className="space-y-6">
        <Skeleton className="h-5 w-64" />
        <div className="grid gap-6 md:grid-cols-2 lg:gap-10">
          <Skeleton className="aspect-square w-full rounded-3xl" />
          <div className="space-y-4">
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-9 w-3/4" />
            <Skeleton className="h-4 w-1/3" />
            <Skeleton className="h-48 w-full rounded-2xl" />
            <Skeleton className="h-20 w-full rounded-2xl" />
          </div>
        </div>
      </div>
    )
  }

  if (product.isError) {
    if (hasErrorCode(product.error, PRODUCT_NOT_FOUND) || (product.error instanceof ApiError && product.error.status === 404)) {
      return (
        <div className="mx-auto max-w-xl py-8">
          <EmptyState
            size="lg"
            illustration={<SearchIllustration />}
            title="Product not found"
            description="It may have been removed, or it is not available right now."
            action={
              <Link
                to="/search"
                className={cn(
                  'inline-flex h-10 items-center rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-600/25 hover:bg-emerald-700',
                  FOCUS,
                )}
              >
                Browse products
              </Link>
            }
          />
        </div>
      )
    }
    return (
      <ErrorState
        title="Could not load this product"
        message={product.error.message}
        correlationId={product.error instanceof ApiError ? product.error.correlationId : null}
        onRetry={() => void product.refetch()}
      />
    )
  }

  const item = product.data
  const images = item.imageUrls.length > 0 ? item.imageUrls : item.primaryImageUrl ? [item.primaryImageUrl] : []
  const path = item.categoryPath.length > 0 ? item.categoryPath : [item.category]
  const price = item.price
  const outOfStock = item.inStock === false
  const discounted = Boolean(price && price.mrp > price.sellingPrice)
  const savings = price && discounted ? Math.round((price.mrp - price.sellingPrice) * 100) / 100 : 0
  const discountPercent = discounted ? (price?.discountPercent ?? 0) : 0
  const hasDetails = Boolean(item.description) || item.attributes.length > 0 || item.tags.length > 0

  return (
    <article className="space-y-8">
      <nav aria-label="Breadcrumb" className="animate-fade-up">
        <ol className="flex flex-wrap items-center gap-0.5 text-sm text-slate-500">
          <li>
            <Link to="/" className={cn('inline-flex items-center gap-1.5 rounded-lg px-1.5 py-1 hover:bg-white hover:text-slate-800', FOCUS)}>
              <Icon name="home" className="size-4" />
              Home
            </Link>
          </li>
          {path.map((category, index) => {
            const last = index === path.length - 1
            return (
              <li key={category.id} className="flex items-center gap-0.5">
                <Icon name="chevronRight" className="size-3.5 text-slate-300" />
                <Link
                  to={categoryLink(category.id)}
                  aria-current={last ? 'page' : undefined}
                  className={cn(
                    'rounded-lg px-1.5 py-1 hover:bg-white hover:text-slate-800',
                    last && 'font-medium text-slate-700',
                    FOCUS,
                  )}
                >
                  {category.name}
                </Link>
              </li>
            )
          })}
        </ol>
      </nav>

      <div className="grid gap-6 md:grid-cols-2 lg:gap-10">
        <div className="animate-fade-up md:sticky md:top-20 md:self-start">
          <ProductGallery
            images={images}
            productName={item.name}
            muted={outOfStock}
            badge={!outOfStock && discountPercent > 0 ? <DiscountRibbon percent={discountPercent} className="px-2 py-1.5" /> : undefined}
          />
        </div>

        <div className="animate-fade-up min-w-0 space-y-5">
          <header>
            {item.brand && (
              <Link
                to={`/search?brandId=${encodeURIComponent(item.brand.id)}`}
                className={cn(
                  'inline-flex items-center rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-semibold tracking-wide text-emerald-700 ring-1 ring-emerald-100 ring-inset hover:bg-emerald-100',
                  FOCUS,
                )}
              >
                {item.brand.name}
              </Link>
            )}
            <h1 className="mt-2.5 text-2xl leading-tight font-bold tracking-tight text-balance text-slate-900 sm:text-3xl">{item.name}</h1>
            <RatingLink rating={item.rating} count={item.ratingCount} />
            <div className="mt-3 flex flex-wrap items-center gap-2 text-sm text-slate-600">
              <span className="inline-flex items-center gap-1.5 rounded-full bg-white px-3 py-1 font-medium ring-1 ring-slate-200 ring-inset">
                <Icon name="scale" className="size-4" />
                {item.unitLabel}
              </span>
              {item.foodType && (
                <span className="inline-flex items-center gap-1.5 rounded-full bg-white px-3 py-1 font-medium ring-1 ring-slate-200 ring-inset">
                  <FoodTypeMark foodType={item.foodType} size="md" />
                  {FOOD_TYPE_LABELS[item.foodType]}
                </span>
              )}
            </div>
          </header>

          <section
            aria-label="Price and availability"
            className="space-y-4 rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm shadow-slate-900/[0.03] sm:p-6"
          >
            <div className="space-y-2">
              <PriceTag price={price} size="lg" />
              {price && savings > 0 && (
                <p className="inline-flex items-center gap-1.5 rounded-full bg-amber-50 px-2.5 py-1 text-xs font-semibold text-amber-800 ring-1 ring-amber-200 ring-inset">
                  <Icon name="savings" className="size-3.5" />
                  You save {formatPrice(savings, price.currency)} on MRP
                </p>
              )}
            </div>
            {outOfStock ? (
              <p className="flex flex-wrap items-center gap-2 rounded-xl bg-red-50/60 px-3 py-2 text-sm text-slate-700">
                <OutOfStockBadge />
                This product is out of stock right now.
              </p>
            ) : item.inStock === true ? (
              <Badge tone="green" dot>
                In stock
              </Badge>
            ) : null}
            <div className="pt-1">
              <ProductCartControl product={item} size="md" block />
            </div>
            <p className="flex items-center gap-2.5 border-t border-slate-100 pt-4 text-sm text-slate-600">
              <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-slate-100">
                <Icon name="store" className="size-4" />
              </span>
              <span>
                Sold by{' '}
                <Link
                  to={`/search?vendorId=${encodeURIComponent(item.vendor.id)}`}
                  className="rounded font-semibold text-emerald-700 hover:text-emerald-800 hover:underline"
                >
                  {item.vendor.name}
                </Link>
              </span>
            </p>
          </section>

          <ul
            aria-label="Shopping with FreshCart"
            className="grid grid-cols-3 divide-x divide-slate-100 rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.03]"
          >
            {REASSURANCES.map((promise) => (
              <li key={promise.title} className="flex flex-col items-center gap-2 px-2 py-3.5 text-center">
                <span className={cn('flex size-9 shrink-0 items-center justify-center rounded-xl', promise.tint)}>
                  <Icon name={promise.icon} className="size-[1.1rem]" />
                </span>
                <span className="min-w-0">
                  <span className="block text-xs leading-snug font-semibold text-slate-900 sm:text-[0.8125rem]">{promise.title}</span>
                  <span className="mt-0.5 block text-[0.7rem] leading-snug text-slate-500 sm:text-xs">{promise.text}</span>
                </span>
              </li>
            ))}
          </ul>

          {hasDetails && (
            <div className="divide-y divide-slate-100 rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.03]">
              {item.description && (
                <section aria-labelledby="product-description" className="p-5 sm:p-6">
                  <SectionHeading id="product-description" icon="info">
                    About this product
                  </SectionHeading>
                  <p className="mt-3 text-sm leading-relaxed whitespace-pre-line text-slate-600">{item.description}</p>
                </section>
              )}

              {item.attributes.length > 0 && (
                <section aria-labelledby="product-attributes" className="p-5 sm:p-6">
                  <SectionHeading id="product-attributes" icon="list">
                    Product details
                  </SectionHeading>
                  <div className="mt-3 overflow-hidden rounded-xl ring-1 ring-slate-200">
                    <table className="w-full text-sm">
                      <tbody className="divide-y divide-slate-100">
                        {item.attributes.map((attribute) => (
                          <tr key={`${attribute.name}-${attribute.value}`} className="even:bg-slate-50/70">
                            <th scope="row" className="w-2/5 bg-slate-50/70 px-4 py-2.5 text-left font-medium text-slate-500">
                              {attribute.name}
                            </th>
                            <td className="px-4 py-2.5 font-medium text-slate-800">{attribute.value}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>
              )}

              {item.tags.length > 0 && (
                <section aria-labelledby="product-tags" className="p-5 sm:p-6">
                  <SectionHeading id="product-tags" icon="tag">
                    Tags
                  </SectionHeading>
                  <ul className="mt-3 flex flex-wrap gap-2">
                    {item.tags.map((tag) => (
                      <li key={tag}>
                        <Link
                          to={`/search?q=${encodeURIComponent(tag)}`}
                          className={cn(
                            'inline-flex rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-700 transition hover:bg-emerald-50 hover:text-emerald-800',
                            FOCUS,
                          )}
                        >
                          #{tag}
                        </Link>
                      </li>
                    ))}
                  </ul>
                </section>
              )}
            </div>
          )}
        </div>
      </div>

      <ReviewsSection id={REVIEWS_ANCHOR} targetType="PRODUCT" targetId={item.id} storeName={item.vendor.name} />
    </article>
  )
}
