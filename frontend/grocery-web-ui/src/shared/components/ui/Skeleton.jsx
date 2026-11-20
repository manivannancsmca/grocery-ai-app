import { cn } from '../../utils/cn'

/**
 * Placeholder block with a soft shimmer, shown while content loads (decorative; pair it with a status message).
 * @param {{ className?: string }} props
 */
export function Skeleton({ className }) {
  return (
    <div
      aria-hidden="true"
      className={cn(
        'animate-shimmer rounded-md bg-slate-200/70 bg-[linear-gradient(90deg,transparent_25%,rgb(255_255_255/0.65)_50%,transparent_75%)] bg-[length:200%_100%]',
        className,
      )}
    />
  )
}
