import { useState } from 'react'
import { ApiError } from '../../shared/api/problemDetail'
import { Dialog, EmptyState, ErrorState, Pagination, Spinner } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { formatCount, formatDateTime } from '../../shared/utils/format'
import { adjustmentReasonLabel, movementTypeLabel, type StockMovementView, type StockView } from './api'
import { useStockMovements } from './hooks'

interface MovementsDrawerProps {
  /** The product whose ledger is shown; null closes the drawer. */
  stock: StockView | null
  onClose: () => void
}

/** The stock ledger of one product, newest first, ten entries per page. */
export function MovementsDrawer({ stock, onClose }: MovementsDrawerProps) {
  return (
    <Dialog
      open={stock !== null}
      onClose={onClose}
      variant="drawer"
      icon="box"
      title={stock ? `Stock history of ${stock.productName}` : 'Stock history'}
      description={stock ? `${stock.unitLabel}${stock.sku ? ` · SKU ${stock.sku}` : ''}` : undefined}
    >
      {stock && <MovementList key={stock.productId} productId={stock.productId} />}
    </Dialog>
  )
}

function delta(movement: StockMovementView) {
  const value = movement.quantityDelta
  if (value === 0) return <span className="text-slate-500">±0</span>
  return (
    <span className={cn('font-semibold tabular-nums', value > 0 ? 'text-emerald-700' : 'text-red-700')}>
      {value > 0 ? '+' : '−'}
      {formatCount(Math.abs(value))}
    </span>
  )
}

function MovementList({ productId }: { productId: string }) {
  const [page, setPage] = useState(0)
  const movements = useStockMovements(productId, page)

  if (movements.isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner size="lg" label="Loading stock history" />
      </div>
    )
  }
  if (movements.isError) {
    return (
      <ErrorState
        title="Could not load the stock history"
        message={movements.error.message}
        correlationId={movements.error instanceof ApiError ? movements.error.correlationId : null}
        onRetry={() => void movements.refetch()}
      />
    )
  }
  if (movements.data.content.length === 0) {
    return <EmptyState title="No stock changes yet" description="Adjustments and order reservations appear here." />
  }

  return (
    <div className={cn('space-y-3', movements.isPlaceholderData && 'opacity-60')}>
      <ol aria-label="Stock changes" className="divide-y divide-slate-100 rounded-xl border border-slate-200">
        {movements.data.content.map((movement) => (
          <li key={movement.id} className="space-y-1 px-4 py-3 text-sm">
            <div className="flex items-start justify-between gap-3">
              <p className="font-medium text-slate-900">
                {movementTypeLabel(movement.type)}
                {movement.reason && <span className="font-normal text-slate-500"> · {adjustmentReasonLabel(movement.reason)}</span>}
              </p>
              {delta(movement)}
            </div>
            <p className="text-xs text-slate-500">
              {formatDateTime(movement.createdAt)} · on hand {formatCount(movement.onHandAfter)}, reserved{' '}
              {formatCount(movement.reservedAfter)}
            </p>
            {movement.note && <p className="text-slate-600">“{movement.note}”</p>}
            {movement.reference && <p className="font-mono text-xs break-all text-slate-400">Order {movement.reference}</p>}
          </li>
        ))}
      </ol>
      <Pagination
        page={movements.data.page}
        size={movements.data.size}
        totalElements={movements.data.totalElements}
        totalPages={movements.data.totalPages}
        disabled={movements.isFetching}
        onPageChange={setPage}
      />
    </div>
  )
}
