import { useId, useState } from 'react'
import { Link } from 'react-router'
import { Alert, Button, Dialog, SelectField, Spinner, TextareaField } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatPrice } from '../../shared/utils/format'
import { RETURN_LIMITS, RETURN_REASONS } from './api'
import { useRequestReturn } from './hooks'
import { RETURN_REASON_LABELS, returnActionLabel, returnableQuantities, returnErrorMessage } from './labels'

/** @typedef {import('../orders/api').OrderItem} OrderItem */
/** @typedef {import('../orders/api').OrderView} OrderView */
/** @typedef {import('./api').ReturnReason} ReturnReason */
/** @typedef {import('./api').ReturnView} ReturnView */

/**
 * @param {string} value
 * @returns {value is ReturnReason}
 */
function isReason(value) {
  return RETURN_REASONS.includes(value)
}

/**
 * "− n +" for the quantity to return of one line (0 = not returned).
 * @param {{ item: OrderItem, value: number, max: number, onChange: (next: number) => void, disabled: boolean }} props
 */
function ReturnQuantity({ item, value, max, onChange, disabled }) {
  const buttonClass =
    'flex size-8 items-center justify-center rounded-md text-base font-semibold text-slate-700 hover:bg-slate-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent'
  return (
    <div
      role="group"
      aria-label={`Quantity to return of ${item.name}`}
      className="inline-flex items-center gap-1 rounded-lg ring-1 ring-slate-300 ring-inset"
    >
      <button
        type="button"
        className={buttonClass}
        aria-label={`Return one less ${item.name}`}
        disabled={disabled || value <= 0}
        onClick={() => onChange(value - 1)}
      >
        −
      </button>
      <output aria-live="polite" className="min-w-6 text-center text-sm font-semibold text-slate-900 tabular-nums">
        {value}
      </output>
      <button
        type="button"
        className={buttonClass}
        aria-label={`Return one more ${item.name}`}
        disabled={disabled || value >= max}
        onClick={() => onChange(value + 1)}
      >
        +
      </button>
    </div>
  )
}

/** @param {{ view: ReturnView, onClose: () => void }} props */
function ReturnRequested({ view, onClose }) {
  return (
    <div className="space-y-4">
      <Alert tone="success" role="status" title={`Return ${view.returnNumber} requested`}>
        <p>
          If {view.storeName || 'the store'} approves it,{' '}
          <span className="font-semibold">{formatPrice(view.refundAmount)}</span> will be refunded to your original
          payment method.
          {view.decideBy ? ` The store decides by ${formatDateTime(view.decideBy)}.` : ''}
        </p>
      </Alert>
      <dl aria-label="Refund per item" className="space-y-1.5 text-sm">
        {view.items.map((line) => (
          <div key={line.itemId} className="flex justify-between gap-4">
            <dt className="text-slate-600">
              {line.quantity} × {line.name}
            </dt>
            <dd className="font-medium text-slate-900 tabular-nums">{formatPrice(line.refundAmount)}</dd>
          </div>
        ))}
        <div className="flex justify-between gap-4 border-t border-slate-200 pt-1.5">
          <dt className="font-semibold text-slate-900">Refund</dt>
          <dd className="font-bold text-slate-900 tabular-nums">{formatPrice(view.refundAmount)}</dd>
        </div>
      </dl>
      <p className="text-xs text-slate-500">
        The refund is worked out from what you paid (after any coupon discount); the delivery fee is included when
        everything left is returned.
      </p>
      <div className="flex flex-wrap items-center justify-end gap-3">
        <Link
          to="/account/returns"
          onClick={onClose}
          className="text-sm font-semibold text-emerald-700 hover:text-emerald-800 hover:underline"
        >
          See my returns
        </Link>
        <Button onClick={onClose}>Done</Button>
      </div>
    </div>
  )
}

/**
 * @typedef {object} ReturnRequestDialogProps
 * @property {OrderView} order
 * @property {boolean} open
 * @property {() => void} onClose
 */

/**
 * "Return items" ("Report a problem" for restaurant orders): pick lines and quantities, a reason and an optional note.
 * The refund is computed by order-service and shown from its answer.
 * @param {ReturnRequestDialogProps} props
 */
export function ReturnRequestDialog({ order, open, onClose }) {
  const request = useRequestReturn(order.id)
  const formId = useId()
  const itemsErrorId = useId()
  const [quantities, setQuantities] = useState({})
  const [reason, setReason] = useState('')
  const [note, setNote] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)

  const left = returnableQuantities(order)
  const restaurant = order.type === 'RESTAURANT'
  const title = returnActionLabel(order.type)
  const chosen = order.items
    .map((item) => ({
      itemId: item.itemId,
      quantity: Math.min(quantities[item.itemId] ?? 0, left.get(item.itemId) ?? 0),
    }))
    .filter((line) => line.quantity > 0)
  const itemsError = submitted && chosen.length === 0 ? 'Choose at least one item and how many of it.' : undefined
  const reasonError = submitted && !reason ? 'Choose a reason.' : undefined
  const noteError =
    note.trim().length > RETURN_LIMITS.note ? `Use at most ${RETURN_LIMITS.note} characters.` : undefined
  const pending = request.isPending

  function close() {
    if (pending) return
    setQuantities({})
    setReason('')
    setNote('')
    setSubmitted(false)
    setError(null)
    setResult(null)
    onClose()
  }

  function submit() {
    setSubmitted(true)
    setError(null)
    if (chosen.length === 0 || !reason || noteError || pending) return
    const trimmed = note.trim()
    request.mutate(
      { items: chosen, reason, ...(trimmed ? { note: trimmed } : {}) },
      {
        onSuccess: (view) => setResult(view),
        onError: (failure) => setError(returnErrorMessage(failure)),
      },
    )
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      title={result ? 'Return requested' : title}
      description={result ? undefined : `Order ${order.orderNumber} from ${order.store.name}`}
      size="lg"
      dismissible={!pending}
      footer={
        result ? undefined : (
          <>
            <Button variant="secondary" onClick={close} disabled={pending}>
              Cancel
            </Button>
            <Button type="submit" form={formId} disabled={pending}>
              {pending && <Spinner size="sm" className="[&_svg]:text-white" />}
              {restaurant ? 'Report problem' : 'Request return'}
            </Button>
          </>
        )
      }
    >
      {result ? (
        <ReturnRequested view={result} onClose={close} />
      ) : (
        <form
          id={formId}
          noValidate
          onSubmit={(event) => {
            event.preventDefault()
            submit()
          }}
          className="space-y-5"
        >
          <fieldset aria-describedby={itemsError ? itemsErrorId : undefined}>
            <legend className="text-sm font-medium text-slate-700">
              {restaurant ? 'Which dishes had a problem?' : 'What would you like to return?'}
            </legend>
            <ul
              aria-label="Items in this order"
              className="mt-2 divide-y divide-slate-100 rounded-lg border border-slate-200"
            >
              {order.items.map((item) => {
                const max = left.get(item.itemId) ?? 0
                const value = Math.min(quantities[item.itemId] ?? 0, max)
                return (
                  <li
                    key={`${item.type}-${item.itemId}`}
                    className={cn(
                      'flex flex-wrap items-center justify-between gap-3 px-3 py-2.5',
                      max === 0 && 'opacity-60',
                    )}
                  >
                    <div className="min-w-0">
                      <p className="text-sm font-semibold text-slate-900">{item.name}</p>
                      <p className="text-xs text-slate-500">
                        {[item.unitLabel, `ordered ${item.quantity} × ${formatPrice(item.unitPrice)}`]
                          .filter(Boolean)
                          .join(' · ')}
                        {max < item.quantity && max > 0 && ` · ${max} left to return`}
                      </p>
                    </div>
                    {max > 0 ? (
                      <ReturnQuantity
                        item={item}
                        value={value}
                        max={max}
                        disabled={pending}
                        onChange={(next) =>
                          setQuantities((current) => ({ ...current, [item.itemId]: Math.max(0, Math.min(next, max)) }))
                        }
                      />
                    ) : (
                      <span className="text-xs font-medium text-slate-500">Already returned</span>
                    )}
                  </li>
                )
              })}
            </ul>
            {itemsError && (
              <p id={itemsErrorId} className="mt-1.5 text-xs font-medium text-red-600">
                {itemsError}
              </p>
            )}
          </fieldset>

          <SelectField
            label="Reason"
            required
            value={reason}
            error={reasonError}
            disabled={pending}
            onChange={(event) => setReason(isReason(event.target.value) ? event.target.value : '')}
          >
            <option value="">Choose a reason</option>
            {RETURN_REASONS.map((value) => (
              <option key={value} value={value}>
                {RETURN_REASON_LABELS[value]}
              </option>
            ))}
          </SelectField>

          <TextareaField
            label={
              <>
                Details <span className="font-normal text-slate-500">(optional)</span>
              </>
            }
            rows={3}
            maxLength={RETURN_LIMITS.note}
            value={note}
            placeholder={restaurant ? 'Tell the restaurant what went wrong' : 'e.g. The packet was torn'}
            hint={`${note.trim().length}/${RETURN_LIMITS.note}`}
            error={noteError}
            disabled={pending}
            onChange={(event) => setNote(event.target.value)}
          />

          <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600">
            {order.store.name} decides on your request (if it does not answer in time, our support team does). We work
            out the refund from what you paid, after any coupon discount, and show it as soon as you send the request.
            {restaurant ? '' : ' Keep the items until the store tells you what to do with them.'}
          </p>

          {error && (
            <Alert tone="danger" role="alert">
              {error}
            </Alert>
          )}
        </form>
      )}
    </Dialog>
  )
}
