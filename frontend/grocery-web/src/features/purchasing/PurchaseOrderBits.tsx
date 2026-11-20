import { Badge, TABLE_HEAD_CLASS, TABLE_HEAD_ROW_CLASS } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { formatCount, formatDateTime, formatMoney } from '../../shared/utils/format'
import { lineTotal, openQuantity, orderTotal, type PurchaseOrderView } from './api'
import { poStatusLabel, poStatusTone } from './labels'

export function PoStatusBadge({ status }: { status: string }) {
  return (
    <Badge tone={poStatusTone(status)} dot>
      {poStatusLabel(status)}
    </Badge>
  )
}

/** The lines of a purchase order: ordered, received and still open, with costs and the total. */
export function PurchaseOrderLinesTable({ order, caption = 'Purchase order lines' }: { order: PurchaseOrderView; caption?: string }) {
  const currency = order.currency ?? 'INR'
  const showReceipt = order.status !== 'DRAFT'
  return (
    <div className="overflow-x-auto rounded-2xl border border-slate-200/70 bg-white shadow-sm shadow-slate-900/[0.04] print:overflow-visible print:rounded-none print:border-slate-400 print:shadow-none">
      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead className={TABLE_HEAD_CLASS}>
          <tr className={TABLE_HEAD_ROW_CLASS}>
            <th scope="col" className="px-4 py-3">
              Product
            </th>
            <th scope="col" className="px-4 py-3 text-right">
              Ordered
            </th>
            {showReceipt && (
              <>
                <th scope="col" className="px-4 py-3 text-right">
                  Received
                </th>
                <th scope="col" className="px-4 py-3 text-right">
                  Open
                </th>
              </>
            )}
            <th scope="col" className="px-4 py-3 text-right">
              Unit cost
            </th>
            <th scope="col" className="px-4 py-3 text-right">
              Total
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {order.lines.map((line) => (
            <tr key={line.productId} className="transition-colors even:bg-slate-50/40 hover:bg-emerald-50/50 print:even:bg-transparent">
              <th scope="row" className="px-4 py-2 text-left font-medium text-slate-900">
                {line.productName}
              </th>
              <td className="px-4 py-2 text-right tabular-nums">{formatCount(line.orderedQuantity)}</td>
              {showReceipt && (
                <>
                  <td className="px-4 py-2 text-right tabular-nums">{formatCount(line.receivedQuantity)}</td>
                  <td className="px-4 py-2 text-right tabular-nums">{formatCount(openQuantity(line))}</td>
                </>
              )}
              <td className="px-4 py-2 text-right tabular-nums">{formatMoney(line.unitCost, currency)}</td>
              <td className="px-4 py-2 text-right tabular-nums">{formatMoney(lineTotal(line), currency)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot className="bg-gradient-to-r from-emerald-50 to-teal-50 print:bg-slate-50">
          <tr>
            <th scope="row" colSpan={showReceipt ? 5 : 3} className="px-4 py-3 text-right font-semibold text-emerald-900 print:text-slate-900">
              Total
            </th>
            <td className="px-4 py-3 text-right font-bold text-emerald-900 tabular-nums print:text-slate-900">{formatMoney(orderTotal(order), currency)}</td>
          </tr>
        </tfoot>
      </table>
    </div>
  )
}

/** Goods receipts of the order, newest first. */
export function ReceiptsList({ order }: { order: PurchaseOrderView }) {
  const receipts = [...(order.receipts ?? [])].sort((a, b) => b.receivedAt.localeCompare(a.receivedAt))
  const names = new Map(order.lines.map((line) => [line.productId, line.productName]))
  if (receipts.length === 0) {
    return (
      <p className="flex items-center gap-2 rounded-xl bg-slate-50 px-3 py-2.5 text-sm text-slate-500 ring-1 ring-slate-100">
        <Icon name="hourglass" className="size-4" />
        Nothing received yet.
      </p>
    )
  }
  // A small timeline: a dot per delivery on a line, newest on top.
  return (
    <ol aria-label="Goods receipts" className="relative space-y-3 before:absolute before:inset-y-2 before:left-[15px] before:w-0.5 before:bg-emerald-100">
      {receipts.map((receipt) => (
        <li key={receipt.id} className="relative flex gap-3 text-sm">
          <span aria-hidden="true" className="relative z-10 flex size-8 shrink-0 items-center justify-center rounded-full bg-white shadow-sm ring-1 ring-emerald-200">
            <Icon name="box" className="size-4" />
          </span>
          <div className="min-w-0 flex-1 rounded-xl bg-white px-4 py-3 shadow-sm ring-1 ring-slate-200/80">
            <p className="font-semibold text-slate-900">
              <time dateTime={receipt.receivedAt}>{formatDateTime(receipt.receivedAt)}</time>
            </p>
            <ul className="mt-1 space-y-0.5 text-slate-700">
              {receipt.lines.map((line) => (
                <li key={line.productId} className="break-words">
                  {formatCount(line.quantity)} × {line.productName ?? names.get(line.productId) ?? line.productId}
                </li>
              ))}
            </ul>
            {receipt.note && <p className="mt-1.5 break-words text-slate-500 italic">“{receipt.note}”</p>}
          </div>
        </li>
      ))}
    </ol>
  )
}
