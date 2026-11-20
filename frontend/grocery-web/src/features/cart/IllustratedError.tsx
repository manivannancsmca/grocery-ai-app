import { Button } from '../../shared/components/ui'
import { ErrorIllustration } from '../../shared/illustrations'
import { cn } from '../../shared/utils/cn'

interface IllustratedErrorProps {
  title: string
  message: string
  onRetry?: () => void
  retryLabel?: string
  /** Shown so customers can quote it to support; it matches the backend log lines. */
  correlationId?: string | null
  className?: string
}

/**
 * A page-level load failure for customer screens: the "unplugged" scene, what went wrong and "Try again" (the same
 * content as `ErrorState`, announced as an alert).
 */
export function IllustratedError({ title, message, onRetry, retryLabel = 'Try again', correlationId, className }: IllustratedErrorProps) {
  return (
    <div
      role="alert"
      className={cn(
        'animate-fade-up flex flex-col items-center rounded-2xl border border-slate-200/80 bg-gradient-to-b from-white to-rose-50/40 px-6 py-10 text-center shadow-sm',
        className,
      )}
    >
      <ErrorIllustration className="w-40 sm:w-48" />
      <h2 className="mt-4 text-lg font-semibold text-slate-900">{title}</h2>
      <p className="mt-1.5 max-w-md text-sm leading-relaxed text-slate-600">{message}</p>
      {correlationId && <p className="mt-2 font-mono text-xs break-all text-slate-400">Reference: {correlationId}</p>}
      {onRetry && (
        <Button className="mt-5" onClick={onRetry}>
          {retryLabel}
        </Button>
      )}
    </div>
  )
}
