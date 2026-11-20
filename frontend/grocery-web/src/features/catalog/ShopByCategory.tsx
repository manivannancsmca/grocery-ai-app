import { Link } from 'react-router'
import { Alert, Button, Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { categoryTint } from './categoryStyle'
import { CategoryArt } from './categoryVisuals'
import { useCategoryTree } from './hooks'

const GRID = 'grid grid-cols-3 gap-3 sm:grid-cols-4 lg:grid-cols-6'

/** Storefront grid of top-level categories, each linking to its search results. */
export function ShopByCategory() {
  const categories = useCategoryTree()
  const roots = categories.data ?? []

  return (
    <section aria-labelledby="shop-by-category" className="animate-fade-up">
      <div className="mb-4 flex items-end justify-between gap-3">
        <div className="min-w-0">
          <h2 id="shop-by-category" className="text-lg font-semibold text-slate-900">
            Shop by category
          </h2>
          <p className="mt-0.5 text-sm text-slate-500">Everything for your kitchen, one tap away.</p>
        </div>
        <Link
          to="/search"
          className="group inline-flex shrink-0 items-center gap-1 rounded-lg text-sm font-semibold text-emerald-700 hover:text-emerald-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
        >
          Browse all products
          <Icon name="chevronRight" className="size-4 transition-transform group-hover:translate-x-0.5" />
        </Link>
      </div>

      {categories.isPending ? (
        <div role="status" aria-label="Loading categories" className={GRID}>
          {Array.from({ length: 6 }, (_, index) => (
            <Skeleton key={index} className="h-36 rounded-2xl" />
          ))}
        </div>
      ) : categories.isError ? (
        <Alert
          tone="warning"
          title="Categories could not be loaded"
          actions={
            <Button variant="secondary" size="sm" onClick={() => void categories.refetch()}>
              Try again
            </Button>
          }
        >
          {categories.error.message}
        </Alert>
      ) : roots.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-center text-sm text-slate-500">
          Categories appear here as soon as the catalog is set up.
        </p>
      ) : (
        <ul className={GRID}>
          {roots.map((category, index) => {
            const tint = categoryTint(index)
            return (
              <li key={category.id}>
                <Link
                  to={`/search?categoryId=${encodeURIComponent(category.id)}`}
                  className={cn(
                    'group relative flex h-full flex-col items-center overflow-hidden rounded-2xl border border-white bg-gradient-to-b px-2 pt-4 pb-3.5 text-center shadow-sm shadow-slate-900/[0.03] transition duration-200',
                    'hover:-translate-y-0.5 hover:shadow-md motion-reduce:hover:translate-y-0',
                    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600',
                    tint.tile,
                    tint.hover,
                  )}
                >
                  <span aria-hidden="true" className={cn('pointer-events-none absolute -top-5 -right-5 size-16 rounded-full', tint.blob)} />
                  <span aria-hidden="true" className={cn('pointer-events-none absolute -bottom-6 -left-4 size-12 rounded-full opacity-60', tint.blob)} />
                  <CategoryArt name={category.name} imageUrl={category.imageUrl} tint={tint} className="size-14 sm:size-16" />
                  <span className="relative mt-3 text-[0.8125rem] leading-tight font-semibold text-slate-900 group-hover:text-slate-950 sm:text-sm">
                    {category.name}
                  </span>{' '}
                  <span className="relative mt-1 text-xs text-slate-500">
                    {category.productCount} product{category.productCount === 1 ? '' : 's'}
                  </span>
                </Link>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
