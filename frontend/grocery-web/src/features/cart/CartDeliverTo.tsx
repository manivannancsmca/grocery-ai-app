import { useState } from 'react'
import { Link } from 'react-router'
import { Badge, Button, Dialog, Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatAddress, formatDistanceKm } from '../../shared/utils/format'
import type { Address } from '../customer/api'
import type { CartIssue, DeliveryQuote } from './api'
import { CheckoutAddressPicker } from './CheckoutAddressPicker'
import { addressIssueLabel } from './issues'

/** The address book, with a way back to the cart afterwards. */
const MANAGE_ADDRESSES_PATH = `/account/addresses?returnTo=${encodeURIComponent('/cart')}`

const smallLinkClass =
  'inline-flex h-8 shrink-0 items-center rounded-lg px-2.5 text-sm font-semibold text-emerald-700 transition-colors hover:bg-emerald-50 hover:text-emerald-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

interface CartDeliverToProps {
  addresses: readonly Address[]
  /** The saved addresses are loading / could not be loaded. */
  loading?: boolean
  failed?: boolean
  onRetry?: () => void
  selected: Address | null
  onSelect: (id: string) => void
  /** The preview's quote for the selected address (distance). */
  delivery: DeliveryQuote | null | undefined
  /** The cart's issues: the ones about the address (no map location, outside the delivery area) show under it. */
  issues: readonly CartIssue[]
}

/**
 * "Deliver to" in the cart's order summary: the chosen saved address (the default one at first) with its distance from
 * the store and "Change", which opens the saved addresses as radio cards. The bill below follows the choice.
 */
export function CartDeliverTo({
  addresses,
  loading = false,
  failed = false,
  onRetry,
  selected,
  onSelect,
  delivery,
  issues,
}: CartDeliverToProps) {
  const [choosing, setChoosing] = useState(false)
  const distance = formatDistanceKm(delivery?.distanceKm)
  const problems = issues.flatMap((issue) => {
    const label = addressIssueLabel(issue, delivery)
    return label ? [{ code: issue.code, label }] : []
  })

  let body
  if (loading) {
    body = (
      <div role="status" aria-label="Loading your addresses" className="flex-1 space-y-2">
        <Skeleton className="h-4 w-1/3" />
        <Skeleton className="h-3 w-3/4" />
      </div>
    )
  } else if (failed) {
    body = (
      <div className="min-w-0 flex-1 text-sm">
        <p className="font-semibold text-slate-900">Your addresses could not be loaded</p>
        <p className="text-xs text-slate-500">The fee is shown for your default address.</p>
      </div>
    )
  } else if (!selected) {
    body = (
      <div className="min-w-0 flex-1 text-sm">
        <p className="font-semibold text-slate-900">No delivery address yet</p>
        <p className="text-xs text-slate-500">Add one to see the delivery fee.</p>
      </div>
    )
  } else {
    body = (
      <div className="min-w-0 flex-1 text-sm">
        <p className="flex min-w-0 items-center gap-2">
          <span className="truncate font-semibold text-slate-900">{selected.label}</span>
          {selected.isDefault && (
            <Badge tone="green" className="shrink-0">
              Default
            </Badge>
          )}
        </p>
        <p className="mt-0.5 line-clamp-2 text-xs leading-relaxed break-words text-slate-500">{formatAddress(selected)}</p>
        {distance && (
          <p className="mt-1 flex items-center gap-1 text-xs font-medium text-slate-600">
            <Icon name="route" className="size-3.5" />
            {distance} away
          </p>
        )}
      </div>
    )
  }

  let action = null
  if (failed && onRetry) {
    action = (
      <Button variant="ghost" size="sm" onClick={onRetry} className="shrink-0 text-emerald-700 hover:bg-emerald-50 hover:text-emerald-800">
        Try again
      </Button>
    )
  } else if (!loading && !failed && !selected) {
    action = (
      <Link to={MANAGE_ADDRESSES_PATH} className={smallLinkClass}>
        Add address
      </Link>
    )
  } else if (!loading && !failed && addresses.length > 1) {
    action = (
      <Button
        variant="ghost"
        size="sm"
        aria-haspopup="dialog"
        aria-label="Change delivery address"
        onClick={() => setChoosing(true)}
        className="shrink-0 text-emerald-700 hover:bg-emerald-50 hover:text-emerald-800"
      >
        Change
      </Button>
    )
  } else if (!loading && !failed) {
    action = (
      <Link to={MANAGE_ADDRESSES_PATH} className={smallLinkClass} aria-label="Edit or add delivery addresses">
        Edit
      </Link>
    )
  }

  return (
    <div>
      <p className="mb-2 text-xs font-semibold tracking-wider text-slate-500 uppercase">Deliver to</p>
      <div
        className={cn(
          'flex items-start gap-3 rounded-xl px-3 py-2.5 ring-1 ring-inset',
          problems.length > 0 ? 'bg-red-50/60 ring-red-200/80' : 'bg-slate-50/80 ring-slate-200/70',
        )}
      >
        <span aria-hidden="true" className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-lg bg-white ring-1 ring-slate-200">
          <Icon name="mapPin" className="size-5" />
        </span>
        {body}
        {action}
      </div>
      {problems.map((problem) => (
        <p key={problem.code} className="mt-2 flex items-start gap-1.5 text-xs font-medium text-red-700">
          <Icon name="alertCircle" className="mt-px size-3.5" />
          <span className="min-w-0">{problem.label}</span>
        </p>
      ))}

      <Dialog
        open={choosing}
        onClose={() => setChoosing(false)}
        title="Deliver to"
        description="The delivery fee and the total follow the address you choose."
        icon="mapPin"
        footer={
          <>
            <Link
              to={MANAGE_ADDRESSES_PATH}
              className="mr-auto inline-flex h-10 items-center rounded-xl px-2 text-sm font-semibold text-emerald-700 hover:text-emerald-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
            >
              Add or edit addresses
            </Link>
            <Button onClick={() => setChoosing(false)}>Done</Button>
          </>
        }
      >
        <CheckoutAddressPicker addresses={addresses} selectedId={selected?.id ?? null} onSelect={onSelect} />
      </Dialog>
    </div>
  )
}
