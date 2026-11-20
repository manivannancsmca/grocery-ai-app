import { Badge } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { formatMoney, formatSignedPercent } from '../../shared/utils/format'
import { isPriceChangeStatus, PRICE_CHANGE_STATUS_LABELS, PRICE_CHANGE_STATUS_TONES } from './api'

export function PriceChangeStatusBadge({ status }: { status: string }) {
  if (!isPriceChangeStatus(status)) return <Badge>{status}</Badge>
  return (
    <Badge tone={PRICE_CHANGE_STATUS_TONES[status]} dot>
      {PRICE_CHANGE_STATUS_LABELS[status]}
    </Badge>
  )
}

/** A change of at least this many percent (twice the default approval threshold) is highlighted for reviewers. */
const BIG_CHANGE_PERCENT = 40

/** Signed selling-price change: drops red, raises amber; big moves get a filled badge and a word for screen readers. */
export function ChangePercent({ value, className }: { value: number | null | undefined; className?: string }) {
  if (value === null || value === undefined) return <span className="text-slate-400">—</span>
  const big = Math.abs(value) >= BIG_CHANGE_PERCENT
  const text = formatSignedPercent(value)
  if (big) {
    return (
      <Badge tone={value < 0 ? 'red' : 'amber'} className={cn('font-semibold', className)}>
        {text}
        <span className="sr-only">{value < 0 ? ' (big drop)' : ' (big raise)'}</span>
      </Badge>
    )
  }
  return (
    <span
      className={cn(
        'font-medium whitespace-nowrap tabular-nums',
        value < 0 ? 'text-red-700' : value > 0 ? 'text-amber-700' : 'text-slate-600',
        className,
      )}
    >
      {text}
    </span>
  )
}

/** Selling price over MRP, e.g. "₹49.00" / "MRP ₹60.00"; a dash when there is no price. */
export function MoneyPair({ sellingPrice, mrp }: { sellingPrice: number | null | undefined; mrp: number | null | undefined }) {
  if (sellingPrice === null || sellingPrice === undefined) return <span className="text-slate-400">—</span>
  return (
    <span className="block whitespace-nowrap">
      <span className="font-medium text-slate-900 tabular-nums">{formatMoney(sellingPrice)}</span>
      <span className="block text-xs text-slate-500 tabular-nums">MRP {formatMoney(mrp)}</span>
    </span>
  )
}
