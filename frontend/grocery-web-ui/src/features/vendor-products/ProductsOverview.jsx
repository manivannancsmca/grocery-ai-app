import { Link } from 'react-router'
import { Skeleton, StatCard } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('./hooks').ProductCounts} ProductCounts */

/**
 * @typedef {object} ProductsOverviewProps
 * @property {ProductCounts} counts
 * @property {number | undefined} blocked Published products customers cannot buy (no price or no stock); undefined while unknown.
 * @property {number | undefined} publishedUnpriced
 * @property {number | undefined} publishedOutOfStock
 */

const hintLink = 'inline-flex items-center gap-1 font-semibold text-emerald-700 hover:text-emerald-900 hover:underline'

/**
 * @param {number | undefined} value
 * @returns {ReactNode}
 */
function count(value) {
  return value === undefined ? <Skeleton className="mt-1 h-6 w-10" /> : value
}

/**
 * The catalog at a glance, above the products list: how many products, how many customers can see, how many still
 * need publishing, and how many are listed but cannot be bought (with links to fix the price or the stock).
 * @param {ProductsOverviewProps} props
 */
export function ProductsOverview({ counts, blocked, publishedUnpriced, publishedOutOfStock }) {
  const notLive =
    counts.DRAFT !== undefined && counts.UNPUBLISHED !== undefined ? counts.DRAFT + counts.UNPUBLISHED : undefined
  return (
    <dl aria-label="Catalog overview" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <StatCard
        icon="bag"
        accent="sky"
        label="In your catalog"
        value={count(counts.ALL)}
        hint={counts.ARCHIVED ? `Plus ${counts.ARCHIVED} archived` : 'Not counting archived'}
      />
      <StatCard
        icon="checkCircle"
        accent="emerald"
        label="Live for customers"
        value={count(counts.PUBLISHED)}
        hint={
          <Link to="/vendor/products?status=PUBLISHED" className={hintLink}>
            Show published
            <Icon name="arrowRight" className="size-3" />
          </Link>
        }
      />
      <StatCard
        icon="pencil"
        accent="violet"
        label="Drafts and hidden"
        value={count(notLive)}
        hint={
          counts.DRAFT ? (
            <Link to="/vendor/products?status=DRAFT" className={hintLink}>
              Finish {counts.DRAFT} draft{counts.DRAFT === 1 ? '' : 's'}
              <Icon name="arrowRight" className="size-3" />
            </Link>
          ) : (
            'Hidden from customers'
          )
        }
      />
      <StatCard
        icon="warning"
        accent="amber"
        label="Can’t be bought"
        value={blocked === undefined ? '—' : blocked}
        valueClassName={blocked ? 'text-amber-700' : 'text-slate-900'}
        hint={
          blocked === undefined ? (
            'Price and stock not checked'
          ) : blocked === 0 ? (
            <span className="inline-flex items-center gap-1 text-emerald-700">
              <Icon name="check" className="size-3" />
              All priced and in stock
            </span>
          ) : (
            <span className="flex flex-wrap gap-x-3 gap-y-0.5">
              {publishedUnpriced ? (
                <Link to="/vendor/pricing?filter=UNPRICED" className={hintLink}>
                  {publishedUnpriced} without a price
                </Link>
              ) : null}
              {publishedOutOfStock ? (
                <Link to="/vendor/inventory?filter=OUT_OF_STOCK" className={hintLink}>
                  {publishedOutOfStock} out of stock
                </Link>
              ) : null}
            </span>
          )
        }
      />
    </dl>
  )
}
