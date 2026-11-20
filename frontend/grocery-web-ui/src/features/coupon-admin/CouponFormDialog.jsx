import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  SelectField,
  Spinner,
  TextareaField,
  TextField,
} from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { COUPON_ERRORS, COUPON_LIMITS, COUPON_STORE_TYPES, DISCOUNT_TYPES } from './api'
import { useSaveCoupon } from './hooks'
import { COUPON_STORE_TYPE_LABELS, DISCOUNT_TYPE_LABELS } from './labels'
import { couponFormToRequest, couponSchema, couponToForm, newCouponForm, normaliseCode } from './schema'

/** @typedef {import('./api').CouponOwner} CouponOwner */
/** @typedef {import('./api').CouponView} CouponView */

/**
 * @typedef {object} CouponFormDialogProps
 * @property {CouponOwner} owner
 * @property {boolean} open
 * @property {CouponView | null} coupon null = a new coupon.
 * @property {() => void} onClose
 */

/**
 * Create or edit a coupon. Admins pick the scope; a store's coupons always belong to the store.
 * @param {CouponFormDialogProps} props
 */
export function CouponFormDialog({ owner, open, coupon, onClose }) {
  return (
    <Dialog open={open} onClose={onClose} title={coupon ? `Edit coupon ${coupon.code}` : 'New coupon'} size="lg">
      {open && <CouponForm key={coupon?.id ?? 'new'} owner={owner} coupon={coupon} onClose={onClose} />}
    </Dialog>
  )
}

/** @param {{ owner: CouponOwner, coupon: CouponView | null, onClose: () => void }} props */
function CouponForm({ owner, coupon, onClose }) {
  const toast = useToast()
  const save = useSaveCoupon(owner)
  const [defaultValues] = useState(() => (coupon ? couponToForm(coupon) : newCouponForm(owner)))
  const [schema] = useState(() => couponSchema(owner))
  const [formError, setFormError] = useState(null)
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(schema), defaultValues })
  const discountType = useWatch({ control, name: 'discountType' })
  const scope = useWatch({ control, name: 'scope' })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const saved = await save.mutateAsync({ id: coupon?.id ?? null, body: couponFormToRequest(owner, values) })
      toast.success(coupon ? 'Coupon saved' : 'Coupon created', { description: `${saved.code} — ${saved.name}` })
      onClose()
    } catch (error) {
      if (hasErrorCode(error, COUPON_ERRORS.codeTaken)) {
        setError(
          'code',
          {
            type: 'server',
            message: `${normaliseCode(values.code)} is already used by another coupon. Choose another code.`,
          },
          { shouldFocus: true },
        )
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.join(' ') || errorMessage(error))
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-5">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Code"
          required
          maxLength={20}
          autoComplete="off"
          spellCheck={false}
          className="font-mono uppercase"
          hint="4–20 letters, digits or dashes; stored in capitals."
          error={errors.code?.message}
          {...register('code')}
        />
        <TextField
          label="Name"
          required
          maxLength={COUPON_LIMITS.name}
          hint="Shown to customers."
          error={errors.name?.message}
          {...register('name')}
        />
        <TextareaField
          label="Description"
          rows={2}
          maxLength={COUPON_LIMITS.description}
          containerClassName="sm:col-span-2"
          error={errors.description?.message}
          {...register('description')}
        />
      </div>

      {owner === 'admin' && (
        <fieldset className="grid gap-4 rounded-lg border border-slate-200 p-4 sm:grid-cols-2">
          <legend className="px-1 text-sm font-semibold text-slate-900">Where it applies</legend>
          <SelectField
            label="Scope"
            hint="Platform coupons are funded by the platform; store coupons by the store."
            error={errors.scope?.message}
            {...register('scope')}
          >
            <option value="PLATFORM">Platform (any store)</option>
            <option value="STORE">One store</option>
          </SelectField>
          {scope === 'STORE' ? (
            <TextField
              label="Store ID"
              required
              autoComplete="off"
              hint="The vendor or restaurant ID."
              error={errors.storeId?.message}
              {...register('storeId')}
            />
          ) : (
            <SelectField label="Store type" error={errors.storeType?.message} {...register('storeType')}>
              {COUPON_STORE_TYPES.map((value) => (
                <option key={value} value={value}>
                  {COUPON_STORE_TYPE_LABELS[value]}
                </option>
              ))}
            </SelectField>
          )}
        </fieldset>
      )}

      <fieldset className="grid gap-4 rounded-lg border border-slate-200 p-4 sm:grid-cols-2">
        <legend className="px-1 text-sm font-semibold text-slate-900">Discount</legend>
        <SelectField label="Type" error={errors.discountType?.message} {...register('discountType')}>
          {DISCOUNT_TYPES.map((value) => (
            <option key={value} value={value}>
              {DISCOUNT_TYPE_LABELS[value]}
            </option>
          ))}
        </SelectField>
        {discountType === 'PERCENT' && (
          <>
            <TextField
              label="Percent off"
              required
              inputMode="decimal"
              hint="1 to 90."
              error={errors.value?.message}
              {...register('value')}
            />
            <TextField
              label="Maximum discount (₹)"
              inputMode="decimal"
              hint="Optional cap."
              error={errors.maxDiscount?.message}
              {...register('maxDiscount')}
            />
          </>
        )}
        {discountType === 'FLAT' && (
          <TextField
            label="Amount off (₹)"
            required
            inputMode="decimal"
            hint="Taken off the item subtotal."
            error={errors.value?.message}
            {...register('value')}
          />
        )}
        {discountType === 'FREE_DELIVERY' && (
          <p className="self-end text-sm text-slate-500">The delivery fee is waived; items are full price.</p>
        )}
        <TextField
          label="Minimum order (₹)"
          inputMode="decimal"
          hint="Item subtotal needed; empty = none."
          error={errors.minOrderSubtotal?.message}
          {...register('minOrderSubtotal')}
        />
      </fieldset>

      <fieldset className="grid gap-4 rounded-lg border border-slate-200 p-4 sm:grid-cols-2">
        <legend className="px-1 text-sm font-semibold text-slate-900">Validity and limits</legend>
        <TextField
          label="Valid from"
          type="datetime-local"
          required
          error={errors.validFrom?.message}
          {...register('validFrom')}
        />
        <TextField
          label="Valid until"
          type="datetime-local"
          required
          error={errors.validUntil?.message}
          {...register('validUntil')}
        />
        <TextField
          label="Total uses"
          inputMode="numeric"
          hint="Across all customers; empty = unlimited."
          error={errors.totalUsageLimit?.message}
          {...register('totalUsageLimit')}
        />
        <TextField
          label="Uses per customer"
          required
          inputMode="numeric"
          error={errors.perCustomerLimit?.message}
          {...register('perCustomerLimit')}
        />
        <label className="flex items-start gap-2 text-sm text-slate-700 sm:col-span-2">
          <Checkbox className="mt-0.5" {...register('public')} />
          <span>
            <span className="font-medium">Show as an offer</span>
            <span className="block text-xs text-slate-500">
              Listed to customers at checkout. Unlisted coupons work only for people who know the code.
            </span>
          </span>
        </label>
      </fieldset>

      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {coupon ? 'Save coupon' : 'Create coupon'}
        </Button>
      </div>
    </form>
  )
}
