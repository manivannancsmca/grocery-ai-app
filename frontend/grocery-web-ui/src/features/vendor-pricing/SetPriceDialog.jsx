import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, Spinner, TextareaField, TextField } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatMoney, formatSignedPercent } from '../../shared/utils/format'
import { vendorProductErrorMessage } from '../vendor-inventory/errors'
import {
  DEFAULT_APPROVAL_THRESHOLD_PERCENT,
  fetchVendorPrice,
  PRICE_CHANGE_NOT_PENDING,
  PRICE_CHANGE_PENDING,
  PRICE_LIMITS,
  PRICE_UNCHANGED,
  SELLING_PRICE_ABOVE_MRP,
} from './api'
import { useCancelPriceRequest, useSetPrice } from './hooks'
import {
  changePercent,
  discountPercent,
  needsApproval,
  parseMoney,
  priceFormToRequest,
  priceSchema,
  priceToForm,
  SELLING_ABOVE_MRP_MESSAGE,
} from './schema'

/** @typedef {import('./api').PriceView} PriceView */

/**
 * @typedef {object} SetPriceDialogProps
 * @property {PriceView | null} price The product being priced; null closes the dialog.
 * @property {() => void} onClose
 */

/**
 * Set MRP and selling price, with a live discount preview and a warning when the change needs admin approval.
 * @param {SetPriceDialogProps} props
 */
export function SetPriceDialog({ price, onClose }) {
  return (
    <Dialog
      open={price !== null}
      onClose={onClose}
      title={price ? `Set the price of ${price.productName}` : 'Set price'}
      description={price ? `${price.unitLabel}${price.sku ? ` · SKU ${price.sku}` : ''}` : undefined}
    >
      {price && <SetPriceForm key={price.productId} view={price} onClose={onClose} />}
    </Dialog>
  )
}

/** @param {{ view: PriceView, onClose: () => void }} props */
function SetPriceForm({ view, onClose }) {
  const toast = useToast()
  const save = useSetPrice()
  const cancelRequest = useCancelPriceRequest()
  const [defaultValues] = useState(() => priceToForm(view.price))
  const [formError, setFormError] = useState(null)
  const [pending, setPending] = useState(view.pendingRequest)
  /** Set after a 409 PRICE_CHANGE_PENDING: the pending request must be cancelled before saving. */
  const [blocked, setBlocked] = useState(false)
  const [pendingNotice, setPendingNotice] = useState(null)
  const [cancelError, setCancelError] = useState(null)
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(priceSchema), defaultValues })

  const mrp = parseMoney(useWatch({ control, name: 'mrp' }))
  const sellingPrice = parseMoney(useWatch({ control, name: 'sellingPrice' }))
  const threshold = view.approvalThresholdPercent ?? DEFAULT_APPROVAL_THRESHOLD_PERCENT
  const live = view.price
  const change = live && sellingPrice !== null ? changePercent(live.sellingPrice, sellingPrice) : null
  const approvalNeeded = change !== null && needsApproval(change, threshold)
  const valid = mrp !== null && sellingPrice !== null && sellingPrice <= mrp

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    setPendingNotice(null)
    try {
      const saved = await save.mutateAsync({ productId: view.productId, body: priceFormToRequest(values) })
      if (saved.outcome === 'PENDING_APPROVAL') {
        toast.info('Sent for approval', {
          description: live
            ? `An admin reviews the new price of ${view.productName}. ${formatMoney(live.sellingPrice)} stays live until then.`
            : `An admin reviews the new price of ${view.productName}.`,
        })
      } else {
        toast.success('Price updated', {
          description: `${view.productName} now sells at ${formatMoney(saved.price?.sellingPrice ?? Number(values.sellingPrice))}.`,
        })
      }
      onClose()
    } catch (error) {
      if (hasErrorCode(error, SELLING_PRICE_ABOVE_MRP)) {
        setError('sellingPrice', { type: 'server', message: SELLING_ABOVE_MRP_MESSAGE }, { shouldFocus: true })
        return
      }
      if (hasErrorCode(error, PRICE_CHANGE_PENDING)) {
        // The row on screen may be stale: load the request that blocks the change, so it can be cancelled here.
        let request = pending
        try {
          request = (await fetchVendorPrice(view.productId)).pendingRequest
        } catch {
          // Keep what we have.
        }
        setPending(request)
        if (request) setBlocked(true)
        else setFormError('A pending change of this product was decided just now. Save again to set the new price.')
        return
      }
      if (hasErrorCode(error, PRICE_UNCHANGED)) {
        setFormError('This is already the live price. Change the MRP or the selling price.')
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.join(' ') || vendorProductErrorMessage(error))
    }
  })

  function cancelPending(request) {
    setCancelError(null)
    cancelRequest.mutate(request.id, {
      onSuccess: () => {
        setPending(null)
        setBlocked(false)
        setPendingNotice('The pending request was cancelled. Save to set the new price.')
      },
      onError: (error) => {
        if (hasErrorCode(error, PRICE_CHANGE_NOT_PENDING)) {
          setPending(null)
          setBlocked(false)
          setPendingNotice('That request was decided in the meantime. Check the live price, then save again.')
        } else {
          setCancelError(`Could not cancel the request: ${errorMessage(error)}`)
        }
      },
    })
  }

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}

      {pending && (
        <Alert
          tone="warning"
          role={blocked ? 'alert' : 'status'}
          title="A price change is waiting for approval"
          actions={
            <Button
              size="sm"
              variant="secondary"
              onClick={() => cancelPending(pending)}
              disabled={cancelRequest.isPending}
            >
              {cancelRequest.isPending && <Spinner size="sm" />}
              Cancel pending request
            </Button>
          }
        >
          <p>
            Requested {formatMoney(pending.requestedSellingPrice)} (MRP {formatMoney(pending.requestedMrp)}) on{' '}
            {formatDateTime(pending.requestedAt)}.{' '}
            {blocked
              ? 'A new price cannot be saved while it is pending. Cancel it first.'
              : 'A new price can be saved once it is decided or cancelled.'}
          </p>
          {cancelError && <p className="mt-2 font-medium text-red-700">{cancelError}</p>}
        </Alert>
      )}
      {pendingNotice && (
        <Alert tone="success" role="status">
          {pendingNotice}
        </Alert>
      )}

      <dl className="grid grid-cols-3 gap-2 rounded-lg bg-slate-50 px-3 py-2 text-center text-sm">
        <div>
          <dt className="text-xs text-slate-500">Live MRP</dt>
          <dd className="font-semibold text-slate-900 tabular-nums">{live ? formatMoney(live.mrp) : '—'}</dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Live selling price</dt>
          <dd className="font-semibold text-slate-900 tabular-nums">
            {live ? formatMoney(live.sellingPrice) : 'No price yet'}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-slate-500">Discount</dt>
          <dd className="font-semibold text-slate-900 tabular-nums">{live ? `${live.discountPercent}%` : '—'}</dd>
        </div>
      </dl>

      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="MRP (₹)"
          inputMode="decimal"
          required
          hint="The maximum retail price printed on the pack."
          error={errors.mrp?.message}
          {...register('mrp')}
        />
        <TextField
          label="Selling price (₹)"
          inputMode="decimal"
          required
          hint="What the customer pays. At most the MRP."
          error={errors.sellingPrice?.message}
          {...register('sellingPrice')}
        />
        <TextareaField
          label="Note"
          rows={2}
          maxLength={PRICE_LIMITS.note}
          hint="Optional. Shown to the admin when the change needs approval."
          error={errors.note?.message}
          containerClassName="sm:col-span-2"
          {...register('note')}
        />
      </div>

      <div role="status" className="space-y-1 rounded-lg bg-slate-50 px-3 py-2 text-sm text-slate-700">
        {!valid ? (
          <p className="text-slate-500">Enter the MRP and the selling price to see the discount.</p>
        ) : (
          <p>
            Customers pay <strong>{formatMoney(sellingPrice)}</strong>
            {discountPercent(mrp, sellingPrice) > 0 ? (
              <>
                {' '}
                — <strong>{discountPercent(mrp, sellingPrice)}% off</strong> the MRP of {formatMoney(mrp)}.
              </>
            ) : (
              ' — no discount on the MRP.'
            )}
          </p>
        )}
        {live && change !== null && (
          <p>
            Change from the live selling price of {formatMoney(live.sellingPrice)}:{' '}
            <strong className={cn(approvalNeeded && 'text-amber-800')}>{formatSignedPercent(change)}</strong>
          </p>
        )}
      </div>

      {approvalNeeded && change !== null && live ? (
        <Alert tone="warning" role="alert" title="This change needs admin approval">
          A change of {formatSignedPercent(change)} is more than {threshold}%. It is sent to an admin for approval, and{' '}
          {formatMoney(live.sellingPrice)} stays live until it is approved.
        </Alert>
      ) : (
        <p className="text-xs text-slate-500">
          {live
            ? `Selling-price changes of up to ${threshold}% (up or down) apply immediately. Bigger changes go to an admin for approval; the live price stays until then.`
            : 'This is the first price of the product, so it applies immediately.'}
        </p>
      )}

      <div className="flex flex-col-reverse gap-2 border-t border-slate-100 pt-4 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting || cancelRequest.isPending}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {approvalNeeded ? 'Send for approval' : 'Save price'}
        </Button>
      </div>
    </form>
  )
}
