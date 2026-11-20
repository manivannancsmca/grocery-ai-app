import { useId } from 'react'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { useToast } from '../../shared/components/toast/useToast'
import { Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatPrice } from '../../shared/utils/format'
import { ProductImage } from '../catalog/ProductImage'
import { CartLineStepper } from './CartItemControl'
import { cartErrorMessage } from './errors'
import { useCartItemBusy, useRemoveCartItem, useUpdateCartItem } from './hooks'
import { lineIssueHint, lineIssueLabel } from './issues'
import { discountPercent, LOW_STOCK_THRESHOLD, lineSaving } from './summary'

/** @typedef {import('./api').CartIssue} CartIssue */
/** @typedef {import('./api').CartLine} CartLine */

/**
 * @typedef {object} CartLineItemProps
 * @property {CartLine} line
 * @property {CartIssue | null} issue
 * @property {string} [storeName]
 * @property {() => void} [onRemoved] Called after the line was removed (lets the container keep keyboard focus).
 */

/**
 * One cart line: photo, name, unit, live price (MRP struck through, % off), a stepper, the line total (and what it
 * saves) and remove. Narrow (drawer, phones) it takes two rows; once the row is wide enough everything sits on one.
 * An issue shows as a calm note under the line — red when it blocks checkout, amber for a price change — with a
 * one-tap fix when there is one ("Reduce to 2"). Put it in a list with `divide-y`.
 * @param {CartLineItemProps} props
 */
export function CartLineItem({ line, issue, storeName, onRemoved }) {
  const toast = useToast()
  const remove = useRemoveCartItem(line.itemId)
  const update = useUpdateCartItem(line.itemId)
  const busy = useCartItemBusy(line.itemId)
  const issueId = useId()
  const blocking = issue?.blocking ?? false
  const discounted = line.mrp !== null && line.unitPrice !== null && line.mrp > line.unitPrice
  // A blocked line (sold out, unavailable, closed…) can only shrink; INSUFFICIENT_STOCK is capped by the stock.
  const unavailableReason =
    issue && blocking && issue.code !== 'INSUFFICIENT_STOCK' ? lineIssueLabel(issue, line) : null
  const percent = discountPercent(line)
  const saved = lineSaving(line)
  const hint = issue ? lineIssueHint(issue, line) : null
  const lowStock =
    !issue &&
    line.availableQuantity !== null &&
    line.availableQuantity > 0 &&
    line.availableQuantity <= LOW_STOCK_THRESHOLD
  const reduceTo =
    issue?.code === 'INSUFFICIENT_STOCK' &&
    line.availableQuantity !== null &&
    line.availableQuantity > 0 &&
    line.quantity > line.availableQuantity
      ? line.availableQuantity
      : null
  const onError = (error) => toast.error(cartErrorMessage(error, { itemName: line.name, storeName }))

  return (
    <li aria-describedby={issue ? issueId : undefined} className="@container py-3.5">
      <div className="flex gap-3 @xl:gap-4">
        <ProductImage
          src={line.imageUrl}
          alt=""
          className={cn(
            'size-16 shrink-0 rounded-xl bg-white ring-1 ring-slate-200/70 transition @xl:size-[4.5rem]',
            blocking && 'opacity-50 grayscale',
          )}
          iconClassName="size-6"
        />
        <div className="grid min-w-0 flex-1 grid-cols-[minmax(0,1fr)_auto] items-start gap-x-3 gap-y-2.5 @xl:grid-cols-[minmax(0,1fr)_auto_6.5rem_auto] @xl:items-center @xl:gap-x-4">
          <div className="col-start-1 row-start-1 min-w-0">
            <h3 className="flex items-start gap-1.5 text-sm leading-snug font-semibold text-slate-900">
              <FoodTypeMark foodType={line.foodType} className="mt-0.5" />
              <span className="min-w-0 break-words">{line.name}</span>
            </h3>
            {line.unitPrice !== null ? (
              <p className="mt-1 flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs text-slate-500">
                {line.unitLabel && (
                  <>
                    <span className="font-medium text-slate-600">{line.unitLabel}</span>
                    <span aria-hidden="true" className="text-slate-300">
                      ·
                    </span>
                  </>
                )}
                <span className="tabular-nums">
                  {formatPrice(line.unitPrice)}
                  <span className="text-slate-400"> each</span>
                </span>
                {discounted && (
                  <span className="text-slate-400 tabular-nums line-through">
                    <span className="sr-only">MRP </span>
                    {formatPrice(line.mrp)}
                  </span>
                )}
                {percent > 0 && <span className="font-semibold text-emerald-700">{percent}% off</span>}
                {lowStock && (
                  <span className="rounded-md bg-amber-50 px-1.5 py-px font-semibold text-amber-800 ring-1 ring-amber-200/70 ring-inset">
                    Only {line.availableQuantity} left
                  </span>
                )}
              </p>
            ) : (
              <p className="mt-1 text-xs text-slate-500">
                {line.unitLabel && `${line.unitLabel} · `}Price not available right now
              </p>
            )}
          </div>

          <button
            type="button"
            disabled={busy}
            aria-label={`Remove ${line.name}`}
            title="Remove"
            onClick={() => remove.mutate(undefined, { onSuccess: () => onRemoved?.(), onError })}
            className="col-start-2 row-start-1 -mt-1 -mr-1.5 inline-flex size-9 justify-self-end items-center justify-center rounded-xl text-slate-400 transition-colors hover:bg-red-50 hover:text-red-600 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-500 disabled:cursor-not-allowed disabled:opacity-50 @xl:col-start-4 @xl:mt-0"
          >
            <Icon name="trash" className="size-[1.1rem]" />
          </button>

          <div className="col-start-1 row-start-2 flex items-center @xl:col-start-2 @xl:row-start-1">
            <CartLineStepper
              line={line}
              unavailableReason={unavailableReason}
              storeName={storeName}
              onRemoved={onRemoved}
              tone="soft"
            />
          </div>

          <div className="col-start-2 row-start-2 self-center text-right @xl:col-start-3 @xl:row-start-1">
            <p className={cn('text-[0.95rem] font-bold tabular-nums', blocking ? 'text-slate-400' : 'text-slate-900')}>
              {line.lineTotal !== null ? formatPrice(line.lineTotal) : '—'}
            </p>
            {saved > 0 && (
              <p className="text-[11px] font-semibold text-emerald-700 tabular-nums">Saved {formatPrice(saved)}</p>
            )}
          </div>

          {issue && (
            <div
              className={cn(
                'col-span-full flex flex-wrap items-center gap-x-2 gap-y-1 rounded-xl px-3 py-2 text-xs ring-1 ring-inset',
                blocking ? 'bg-red-50/80 ring-red-100' : 'bg-amber-50/80 ring-amber-100',
              )}
            >
              <Icon name={blocking ? 'alertCircle' : 'info'} className="size-4" />
              <span id={issueId} className={cn('font-semibold', blocking ? 'text-red-700' : 'text-amber-800')}>
                {lineIssueLabel(issue, line)}
              </span>
              {hint && <span className={blocking ? 'text-red-700/85' : 'text-amber-800/85'}>{hint}</span>}
              {reduceTo !== null && (
                <button
                  type="button"
                  disabled={busy}
                  aria-label={`Reduce ${line.name} to ${reduceTo}`}
                  onClick={() => update.mutate(reduceTo, { onError })}
                  className="ml-auto inline-flex h-7 items-center gap-1.5 rounded-lg bg-white px-2.5 font-semibold text-red-700 shadow-xs ring-1 ring-red-200 transition-colors ring-inset hover:bg-red-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-500 disabled:opacity-50"
                >
                  {update.isPending && <Spinner size="sm" />}
                  Reduce to {reduceTo}
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </li>
  )
}
