import { Icon } from '../../icons'
import { Button } from './Button'

/**
 * @typedef {object} ErrorStateProps
 * @property {string} [title]
 * @property {string} message
 * @property {() => void} [onRetry]
 * @property {string} [retryLabel]
 * @property {string | null} [correlationId] Shown so users can quote it to support; it matches the backend log lines.
 */

/** @param {ErrorStateProps} props */
export function ErrorState({
  title = 'Something went wrong',
  message,
  onRetry,
  retryLabel = 'Try again',
  correlationId,
}) {
  return (
    <div
      role="alert"
      className="animate-fade-up relative overflow-hidden rounded-2xl border border-red-200/80 bg-gradient-to-br from-red-50 via-white to-rose-50 p-5 shadow-sm"
    >
      <span aria-hidden="true" className="absolute inset-y-0 left-0 w-1 bg-gradient-to-b from-red-400 to-rose-500" />
      <div className="flex items-start gap-4">
        <span
          aria-hidden="true"
          className="flex size-11 shrink-0 items-center justify-center rounded-2xl bg-white shadow-sm ring-1 ring-red-100"
        >
          <Icon name="alertCircle" className="size-6" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-red-800">{title}</p>
          <p className="mt-1 text-sm text-red-700">{message}</p>
          {correlationId && (
            <p className="mt-2 inline-block rounded-lg bg-white/80 px-2 py-1 font-mono text-xs break-all text-red-600/80 ring-1 ring-red-100">
              Reference: {correlationId}
            </p>
          )}
          {onRetry && (
            <div className="mt-3">
              <Button variant="secondary" size="sm" onClick={onRetry}>
                <Icon name="refresh" className="size-4" />
                {retryLabel}
              </Button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
