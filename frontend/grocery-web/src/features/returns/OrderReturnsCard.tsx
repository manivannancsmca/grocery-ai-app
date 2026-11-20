import { Badge } from '../../shared/components/ui'
import { formatDateTime } from '../../shared/utils/format'
import type { OrderView } from '../orders/api'
import { Panel } from '../orders/OrderVisuals'
import type { OrderReturnSummary, ReturnView } from './api'
import { useOrderReturns } from './hooks'
import { returnReasonLabel, returnStatusLabel, returnStatusText, returnStatusTone } from './labels'

function isFull(entry: ReturnView | OrderReturnSummary): entry is ReturnView {
  return 'storeName' in entry && 'reason' in entry
}

/** The order's return requests (Phase 12): status, refund, items and the store's comment. Nothing without any. */
export function OrderReturnsCard({ order }: { order: OrderView }) {
  const summaries = order.returns ?? []
  const details = useOrderReturns(order.id, { enabled: summaries.length > 0 })
  if (summaries.length === 0) return null
  const entries: Array<ReturnView | OrderReturnSummary> = details.data && details.data.length > 0 ? details.data : summaries

  return (
    <Panel title={order.type === 'RESTAURANT' ? 'Problems reported' : 'Returns'} icon="return">
      <ul aria-label="Return requests" className="space-y-3">
        {entries.map((entry) => {
          const full = isFull(entry) ? entry : null
          const text = returnStatusText({
            status: entry.status,
            refundAmount: entry.refundAmount,
            storeName: full?.storeName ?? order.store.name,
            decideBy: full?.decideBy ?? null,
            refundedAt: full?.refundedAt ?? null,
            comment: full?.comment ?? null,
          })
          return (
            <li key={entry.id} className="space-y-1.5 rounded-xl bg-slate-50/80 p-3.5 ring-1 ring-slate-100">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-sm">
                  <span className="font-mono font-semibold text-slate-900">{entry.returnNumber}</span>
                  <span className="text-slate-500"> · {formatDateTime(entry.requestedAt)}</span>
                </p>
                <Badge tone={returnStatusTone(entry.status)} dot className="px-2.5 py-1 font-semibold">
                  {returnStatusLabel(entry.status)}
                </Badge>
              </div>
              {full && full.items.length > 0 && (
                <p className="text-sm text-slate-700">
                  {full.items.map((line) => `${line.quantity} × ${line.name}`).join(', ')}
                  <span className="text-slate-500"> · {returnReasonLabel(full.reason)}</span>
                </p>
              )}
              {text && <p className="text-sm text-slate-600">{text}</p>}
            </li>
          )
        })}
      </ul>
    </Panel>
  )
}
