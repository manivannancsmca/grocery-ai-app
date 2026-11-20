import { useId, useState } from 'react'
import { Link } from 'react-router'
import { ApiError, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, SelectField, Spinner, TextareaField } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDate } from '../../shared/utils/format'
import { useMyOrders, useOrder } from '../orders/hooks'
import { ORDER_STATUS_LABELS } from '../orders/labels'
import {
  COMPLAINT_CATEGORIES,
  COMPLAINT_CATEGORY_INFO,
  COMPLAINT_ERRORS,
  COMPLAINT_LIMITS,
  createComplaintErrorMessage,
} from './api'
import { useCreateComplaint } from './hooks'

/** @typedef {import('./api').ComplaintCategory} ComplaintCategory */
/** @typedef {import('./api').ComplaintView} ComplaintView */

/** How many recent orders the order picker offers. */
const RECENT_ORDERS = 20

/**
 * One choice of the order picker.
 * @typedef {object} OrderOption
 * @property {string} id
 * @property {string} orderNumber
 * @property {string} storeName
 * @property {string} status
 * @property {string} createdAt
 */

/**
 * Errors shown under the fields (from our own checks or the server's 400 field errors).
 * @typedef {Partial<Record<'category' | 'orderId' | 'description', string>>} FieldErrors
 */

const linkClass = 'font-semibold text-emerald-700 underline-offset-2 hover:text-emerald-800 hover:underline'

/**
 * The recent orders for the picker. A `?orderId=` link to an older order (not among the recent ones) is read on its own
 * so it can still be preselected.
 * @param {string} preselectedId
 */
function useOrderOptions(preselectedId) {
  const recent = useMyOrders({ size: RECENT_ORDERS })
  const recentOrders = recent.data?.content ?? []
  const missing = preselectedId !== '' && recent.isSuccess && !recentOrders.some((order) => order.id === preselectedId)
  const extra = useOrder(missing ? preselectedId : '')

  const options = recentOrders.map((order) => ({
    id: order.id,
    orderNumber: order.orderNumber,
    storeName: order.store.name,
    status: order.status,
    createdAt: order.createdAt,
  }))
  if (missing && extra.data) {
    const order = extra.data
    options.unshift({
      id: order.id,
      orderNumber: order.orderNumber,
      storeName: order.store.name,
      status: order.status,
      createdAt: order.createdAt,
    })
  }
  return { options, loading: recent.isPending || (missing && extra.isPending), failed: recent.isError }
}

/**
 * @param {OrderOption} order
 * @returns {string}
 */
function orderOptionLabel(order) {
  return `${order.orderNumber} · ${order.storeName} · ${formatDate(order.createdAt)}`
}

/**
 * @param {string} status
 * @returns {string}
 */
function statusText(status) {
  return status in ORDER_STATUS_LABELS ? ORDER_STATUS_LABELS[status] : status
}

/**
 * The six categories as large radio tiles: picture, name and a one-line hint.
 * @param {{ value: ComplaintCategory | '', onChange: (next: ComplaintCategory) => void, error?: string }} props
 */
function CategoryPicker({ value, onChange, error }) {
  const name = useId()
  const errorId = useId()
  return (
    <fieldset aria-describedby={error ? errorId : undefined} className="@container">
      <legend className="mb-2 block text-sm font-medium text-slate-700">
        What is it about?
        <span className="ml-0.5 text-red-600" aria-hidden="true">
          *
        </span>
      </legend>
      {/* One per row on phones (the hints stay on one line), two or three side by side where the form is wider. */}
      <div className="grid grid-cols-1 gap-2 @sm:grid-cols-2 @lg:grid-cols-3">
        {COMPLAINT_CATEGORIES.map((category) => {
          const info = COMPLAINT_CATEGORY_INFO[category]
          const selected = value === category
          const labelId = `${name}-${category}-label`
          const hintId = `${name}-${category}-hint`
          return (
            <label
              key={category}
              className={cn(
                'relative flex min-h-14 min-w-0 cursor-pointer items-start gap-2.5 rounded-xl p-2.5 ring-inset transition-colors',
                'has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-emerald-600',
                selected
                  ? 'bg-emerald-50/80 ring-2 ring-emerald-500'
                  : 'bg-white ring-1 ring-slate-200 hover:bg-slate-50 hover:ring-slate-300',
              )}
            >
              <input
                type="radio"
                name={name}
                value={category}
                checked={selected}
                onChange={() => onChange(category)}
                aria-labelledby={labelId}
                aria-describedby={hintId}
                className="sr-only"
              />
              <span
                aria-hidden="true"
                className={cn(
                  'flex size-8 shrink-0 items-center justify-center rounded-lg ring-1 ring-inset',
                  selected ? 'bg-white ring-emerald-200' : 'bg-slate-50 ring-slate-100',
                )}
              >
                <Icon name={info.icon} className="size-5" />
              </span>
              <span className="min-w-0 pr-3">
                <span id={labelId} className="block text-sm leading-tight font-semibold text-slate-900">
                  {info.label}
                </span>
                <span id={hintId} className="mt-0.5 block text-xs leading-snug text-slate-500">
                  {info.hint}
                </span>
              </span>
              {selected && <Icon name="checkCircle" className="absolute top-1.5 right-1.5 size-4" />}
            </label>
          )
        })}
      </div>
      {error && (
        <p id={errorId} className="mt-1.5 text-xs font-medium text-red-600">
          {error}
        </p>
      )}
    </fieldset>
  )
}

/**
 * Quicker ways to get help: a return request for item refunds, and the assistant's "Customer support".
 * @param {{ orderId: string, orderNumber: string | null }} props
 */
function HelpTips({ orderId, orderNumber }) {
  return (
    <div className="border-t border-slate-100 pt-4">
      <h3 className="text-sm font-semibold text-slate-900">Other ways to get help</h3>
      <ul className="mt-2.5 space-y-2.5 text-sm">
        <li className="flex items-start gap-3 rounded-xl bg-violet-50/60 p-3 ring-1 ring-violet-100 ring-inset">
          <Icon name="return" className="mt-0.5 size-5" />
          <p className="min-w-0 text-slate-700">
            <span className="font-semibold text-slate-900">Refund for damaged, wrong or missing items?</span> A return
            request on the order is the fastest way to get your money back.{' '}
            {orderId ? (
              <Link to={`/orders/${encodeURIComponent(orderId)}`} className={linkClass}>
                Open order <span className="whitespace-nowrap">{orderNumber}</span>
              </Link>
            ) : (
              <Link to="/orders" className={linkClass}>
                Go to my orders
              </Link>
            )}
          </p>
        </li>
        <li className="flex items-start gap-3 rounded-xl bg-fuchsia-50/60 p-3 ring-1 ring-fuchsia-100 ring-inset">
          <Icon name="sparkle" className="mt-0.5 size-5" />
          <p className="min-w-0 text-slate-700">
            <span className="font-semibold text-slate-900">Ask the assistant.</span> Open “Ask AI” and choose{' '}
            <span className="font-medium">Customer support</span>: it can check an order, a return or a complaint for
            you.
          </p>
        </li>
      </ul>
    </div>
  )
}

/**
 * @typedef {object} RaiseComplaintFormProps
 * @property {string} initialOrderId `?orderId=` from the page's URL: that order is chosen to start with ('' for none).
 * @property {(complaint: ComplaintView) => void} onCreated Called after the server stored the complaint (the form has already been cleared).
 */

/**
 * "Raise a complaint": a category, an optional order (from the customer's recent orders) and what happened. Our own
 * checks run first; the server's refusals (already open, too many, unknown order, field errors) show next to the form.
 * @param {RaiseComplaintFormProps} props
 */
export function RaiseComplaintForm({ initialOrderId, onCreated }) {
  const headingId = useId()
  const toast = useToast()
  const create = useCreateComplaint()
  const orders = useOrderOptions(initialOrderId)

  const [category, setCategory] = useState('')
  const [orderId, setOrderId] = useState(initialOrderId)
  const [description, setDescription] = useState('')
  const [fieldErrors, setFieldErrors] = useState({})
  const [formError, setFormError] = useState(null)

  // Only an order that is in the picker can be sent (an unknown `?orderId=` falls back to "Not about an order").
  const selectedOrder = orders.options.find((order) => order.id === orderId) ?? null
  const trimmedLength = description.trim().length

  /** Our own checks, with the same limits as the server. */
  function validate() {
    const errors = {}
    if (category === '') errors.category = 'Choose what the complaint is about.'
    if (trimmedLength < COMPLAINT_LIMITS.descriptionMin) {
      errors.description = `Describe the problem in at least ${COMPLAINT_LIMITS.descriptionMin} characters.`
    }
    return errors
  }

  /** Puts the server's refusal next to the form: field errors on their fields, anything else in an alert. */
  function showServerError(error) {
    if (error instanceof ApiError && error.fieldErrors.length > 0) {
      const errors = {}
      const others = []
      for (const { field, message } of error.fieldErrors) {
        if (field === 'category' || field === 'orderId' || field === 'description') errors[field] = message
        else others.push(message)
      }
      setFieldErrors(errors)
      setFormError(others.length > 0 ? { tone: 'danger', message: others.join(' ') } : null)
      return
    }
    // Rules the customer can act on (wait for the answer, choose another order) are warnings, not failures.
    const tone = hasErrorCode(error, COMPLAINT_ERRORS.alreadyOpen, COMPLAINT_ERRORS.tooMany) ? 'warning' : 'danger'
    setFormError({ tone, message: createComplaintErrorMessage(error) })
  }

  async function submit(event) {
    event.preventDefault()
    const errors = validate()
    setFieldErrors(errors)
    setFormError(null)
    if (category === '' || Object.keys(errors).length > 0) return

    try {
      const created = await create.mutateAsync({
        category,
        orderId: selectedOrder?.id,
        description: description.trim(),
      })
      toast.success(`Complaint ${created.complaintNumber} sent`, { description: 'We will answer it on this page.' })
      setCategory('')
      setOrderId('')
      setDescription('')
      onCreated(created)
    } catch (error) {
      showServerError(error)
    }
  }

  return (
    <section
      aria-labelledby={headingId}
      className="animate-fade-up min-w-0 space-y-4 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm shadow-slate-900/[0.03] sm:p-5"
    >
      <header className="flex items-center gap-3">
        <span
          aria-hidden="true"
          className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-emerald-50 ring-1 ring-emerald-100 ring-inset"
        >
          <Icon name="pencil" className="size-[1.15rem]" />
        </span>
        <div className="min-w-0">
          <h2 id={headingId} className="text-base font-semibold text-slate-900">
            Raise a complaint
          </h2>
          <p className="text-xs text-slate-500">Our support team reads every one and answers here.</p>
        </div>
      </header>

      <form onSubmit={(event) => void submit(event)} noValidate aria-labelledby={headingId} className="space-y-4">
        <CategoryPicker
          value={category}
          onChange={(next) => {
            setCategory(next)
            setFieldErrors((current) => ({ ...current, category: undefined }))
          }}
          error={fieldErrors.category}
        />

        {/* Items: a refund comes faster through a return request on the order. */}
        {category === 'ITEMS' && (
          <Alert tone="info" role="status">
            Want your money back for these items? A return request on the order is faster than a complaint.{' '}
            <Link
              to={selectedOrder ? `/orders/${encodeURIComponent(selectedOrder.id)}` : '/orders'}
              className={linkClass}
            >
              {selectedOrder ? (
                <>
                  Request a return on <span className="whitespace-nowrap">{selectedOrder.orderNumber}</span>
                </>
              ) : (
                'Choose an order to return'
              )}
            </Link>
          </Alert>
        )}

        <SelectField
          label="Which order? (optional)"
          value={selectedOrder?.id ?? ''}
          disabled={orders.loading}
          error={fieldErrors.orderId}
          onChange={(event) => {
            setOrderId(event.target.value)
            setFieldErrors((current) => ({ ...current, orderId: undefined }))
          }}
          hint={
            orders.loading
              ? 'Loading your recent orders…'
              : orders.failed
                ? 'We could not load your orders. You can still send the complaint without one.'
                : selectedOrder
                  ? `${selectedOrder.storeName} · ${statusText(selectedOrder.status)} · placed ${formatDate(selectedOrder.createdAt)}`
                  : 'Pick the order it is about, or leave it for app and general problems.'
          }
        >
          <option value="">Not about an order</option>
          {orders.options.map((order) => (
            <option key={order.id} value={order.id}>
              {orderOptionLabel(order)}
            </option>
          ))}
        </SelectField>

        <TextareaField
          label="What happened?"
          required
          rows={5}
          maxLength={COMPLAINT_LIMITS.descriptionMax}
          value={description}
          placeholder="For example: the order came two hours late and the milk was warm."
          error={fieldErrors.description}
          onChange={(event) => {
            setDescription(event.target.value)
            setFieldErrors((current) => ({ ...current, description: undefined }))
          }}
          hint={
            <span className="flex justify-between gap-3">
              <span>At least {COMPLAINT_LIMITS.descriptionMin} characters.</span>
              <span
                className={cn(
                  'tabular-nums',
                  description.length >= COMPLAINT_LIMITS.descriptionMax && 'font-semibold text-amber-700',
                )}
              >
                {description.length} / {COMPLAINT_LIMITS.descriptionMax}
              </span>
            </span>
          }
        />

        {formError && (
          <Alert tone={formError.tone} role="alert">
            {formError.message}
          </Alert>
        )}

        <Button type="submit" className="w-full sm:w-auto" disabled={create.isPending}>
          {create.isPending ? (
            <Spinner size="sm" className="[&_svg]:text-white" />
          ) : (
            <Icon name="arrowRight" className="size-4" />
          )}
          Send complaint
        </Button>
      </form>

      <HelpTips orderId={selectedOrder?.id ?? ''} orderNumber={selectedOrder?.orderNumber ?? null} />
    </section>
  )
}
