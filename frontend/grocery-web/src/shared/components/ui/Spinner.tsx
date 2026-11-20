import { cn } from '../../utils/cn'

interface SpinnerProps {
  size?: 'sm' | 'md' | 'lg'
  /** When set, the spinner announces itself to screen readers; otherwise it is decorative. */
  label?: string
  className?: string
}

const sizeClasses = { sm: 'size-4', md: 'size-6', lg: 'size-10' } as const

export function Spinner({ size = 'md', label, className }: SpinnerProps) {
  return (
    <span role={label ? 'status' : undefined} className={cn('inline-flex', className)}>
      <svg className={cn('animate-spin text-emerald-600', sizeClasses[size])} viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle className="opacity-20" cx="12" cy="12" r="9.5" stroke="currentColor" strokeWidth="3" />
        <path d="M12 2.5a9.5 9.5 0 0 1 9.5 9.5" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
      </svg>
      {label && <span className="sr-only">{label}</span>}
    </span>
  )
}

export function PageLoader() {
  return (
    <div className="animate-fade-in flex min-h-[40vh] items-center justify-center">
      <span className="flex size-16 items-center justify-center rounded-2xl bg-white shadow-lg ring-1 shadow-emerald-900/10 ring-slate-200/70">
        <Spinner size="lg" label="Loading page" />
      </span>
    </div>
  )
}
