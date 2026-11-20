import { Link } from 'react-router'
import { cn } from '../../shared/utils/cn'

/** The FreshCart mark and name, linking to the storefront: the auth screens have no app top bar. */
export function BrandLogo({ onDark = false }: { onDark?: boolean }) {
  return (
    <Link
      to="/"
      className={cn(
        'inline-flex items-center gap-2.5 self-start rounded-lg font-bold focus-visible:outline-2 focus-visible:outline-offset-4',
        onDark ? 'text-white focus-visible:outline-white' : 'text-slate-900 focus-visible:outline-emerald-600',
      )}
    >
      <span
        aria-hidden="true"
        className="flex size-9 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-400 to-emerald-600 text-white shadow-lg ring-1 shadow-emerald-950/30 ring-white/25"
      >
        <svg className="size-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <path d="M5 16c0-6 4-9 12-9-1 7-4 10-9 10-1 0-2-.3-3-1" />
          <path d="M5 18c2-3 5-5 8-6" />
        </svg>
      </span>
      <span className="text-xl tracking-tight">FreshCart</span>
    </Link>
  )
}
