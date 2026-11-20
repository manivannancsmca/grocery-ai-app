import { useState } from 'react'
import { ApiError } from '../../shared/api/problemDetail'
import { Dialog, EmptyState, ErrorState, Pagination, Spinner } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatMoney } from '../../shared/utils/format'
import type { PriceView } from './api'
import { usePriceHistory } from './hooks'
import { ChangePercent } from './PriceBits'

interface PriceHistoryDrawerProps {
  /** The product whose history is shown; null closes the drawer. */
  price: PriceView | null
  onClose: () => void
}

const SOURCE_LABELS: Record<string, string> = { VENDOR: 'Set by you', APPROVED_REQUEST: 'Approved by an admin' }

/** Every change of a product's live price, newest first. */
export function PriceHistoryDrawer({ price, onClose }: PriceHistoryDrawerProps) {
  return (
    <Dialog
      open={price !== null}
      onClose={onClose}
      variant="drawer"
      icon="tag"
      title={price ? `Price history of ${price.productName}` : 'Price history'}
      description={price?.unitLabel}
    >
      {price && <HistoryList key={price.productId} productId={price.productId} />}
    </Dialog>
  )
}

function HistoryList({ productId }: { productId: string }) {
  const [page, setPage] = useState(0)
  const history = usePriceHistory(productId, page)

  if (history.isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner size="lg" label="Loading price history" />
      </div>
    )
  }
  if (history.isError) {
    return (
      <ErrorState
        title="Could not load the price history"
        message={history.error.message}
        correlationId={history.error instanceof ApiError ? history.error.correlationId : null}
        onRetry={() => void history.refetch()}
      />
    )
  }
  if (history.data.content.length === 0) {
    return <EmptyState title="No price yet" description="Prices you set, and changes an admin approves, appear here." />
  }

  return (
    <div className={cn('space-y-3', history.isPlaceholderData && 'opacity-60')}>
      <ol aria-label="Price changes" className="divide-y divide-slate-100 rounded-xl border border-slate-200">
        {history.data.content.map((entry) => (
          <li key={entry.id} className="space-y-1 px-4 py-3 text-sm">
            <div className="flex items-start justify-between gap-3">
              <p className="font-medium text-slate-900">
                {entry.oldSellingPrice === null ? (
                  <>First price: {formatMoney(entry.newSellingPrice)}</>
                ) : (
                  <>
                    {formatMoney(entry.oldSellingPrice)} → {formatMoney(entry.newSellingPrice)}
                  </>
                )}
              </p>
              {entry.oldSellingPrice !== null && <ChangePercent value={entry.changePercent} />}
            </div>
            <p className="text-xs text-slate-500">
              MRP {entry.oldMrp !== null && entry.oldMrp !== entry.newMrp ? `${formatMoney(entry.oldMrp)} → ` : ''}
              {formatMoney(entry.newMrp)} · {SOURCE_LABELS[entry.source] ?? entry.source} · {formatDateTime(entry.changedAt)}
            </p>
          </li>
        ))}
      </ol>
      <Pagination
        page={history.data.page}
        size={history.data.size}
        totalElements={history.data.totalElements}
        totalPages={history.data.totalPages}
        disabled={history.isFetching}
        onPageChange={setPage}
      />
    </div>
  )
}
