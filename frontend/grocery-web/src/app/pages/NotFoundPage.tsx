import { Link } from 'react-router'
import { EmptyState } from '../../shared/components/ui'
import { LostIllustration } from '../../shared/illustrations'

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-xl py-8">
      <EmptyState
        size="lg"
        illustration={<LostIllustration />}
        title="Page not found"
        description="The page you are looking for doesn't exist or hasn't been built yet."
        action={
          <>
            <Link
              to="/"
              className="inline-flex h-10 items-center rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-600/25 hover:bg-emerald-700"
            >
              Back to the storefront
            </Link>
            <Link
              to="/search"
              className="inline-flex h-10 items-center rounded-xl px-4 text-sm font-semibold text-slate-700 ring-1 ring-slate-300 ring-inset hover:bg-slate-50"
            >
              Search products
            </Link>
          </>
        }
      />
    </div>
  )
}
