import { Skeleton, Spinner } from '../../shared/components/ui'

/** The menu page's shape while it loads (tiles, categories, dish cards), with the loading message on top. */
export function MenuSkeleton({ message }: { message: string }) {
  return (
    <div className="space-y-4">
      <p role="status" className="flex items-center gap-2 text-sm text-slate-500">
        <Spinner size="sm" />
        {message}
      </p>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {Array.from({ length: 4 }, (_, index) => (
          <Skeleton key={index} className="h-[5.5rem] rounded-2xl" />
        ))}
      </div>
      <div className="grid gap-4 xl:grid-cols-[15rem_minmax(0,1fr)]">
        <Skeleton className="h-11 rounded-2xl xl:h-48" />
        <div className="space-y-4">
          <Skeleton className="h-14 rounded-2xl" />
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {Array.from({ length: 6 }, (_, index) => (
              <Skeleton key={index} className="h-36 rounded-xl" />
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
