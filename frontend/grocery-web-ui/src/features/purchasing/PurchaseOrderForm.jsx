import { zodResolver } from '@hookform/resolvers/zod'
import { useId, useState } from 'react'
import { useFieldArray, useForm, useWatch } from 'react-hook-form'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import {
  Alert,
  Button,
  Card,
  EmptyState,
  Input,
  SelectField,
  Spinner,
  TABLE_HEAD_CLASS,
  TABLE_HEAD_ROW_CLASS,
  TextareaField,
  TextField,
} from '../../shared/components/ui'
import { applyServerFieldErrors, fieldErrorMessage } from '../../shared/forms/serverErrors'
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue'
import { formatMoney } from '../../shared/utils/format'
import { useVendorProducts } from '../vendor-products/hooks'
import { PURCHASING_ERRORS, PURCHASING_LIMITS } from './api'
import { useSavePurchaseOrder } from './hooks'
import { transitionConflictMessage } from './labels'
import { formTotal, purchaseOrderFormToRequest, purchaseOrderSchema, purchaseOrderToForm } from './schema'

/** @typedef {import('./api').PurchaseOrderView} PurchaseOrderView */
/** @typedef {import('./api').SupplierView} SupplierView */

/**
 * @typedef {object} ProductPickerProps
 * @property {ReadonlySet<string>} chosen Products already on the order (their "Add" is disabled).
 * @property {(product: { id: string, name: string }) => void} onAdd
 */

/**
 * Search the vendor's own products (not archived) and add them as lines.
 * @param {ProductPickerProps} props
 */
function ProductPicker({ chosen, onAdd }) {
  const [query, setQuery] = useState('')
  const q = useDebouncedValue(query.trim(), 300)
  const products = useVendorProducts({ q: q || undefined, page: 0, size: 10 })
  const resultsId = useId()

  return (
    <div className="space-y-2">
      <TextField
        label="Add a product"
        type="search"
        value={query}
        placeholder="Search your products by name or SKU"
        autoComplete="off"
        aria-controls={resultsId}
        onChange={(event) => setQuery(event.target.value)}
      />
      <div id={resultsId}>
        {products.isPending ? (
          <Spinner size="sm" label="Loading products" />
        ) : products.isError ? (
          <p className="text-sm text-red-700">Could not load your products: {products.error.message}</p>
        ) : products.data.content.length === 0 ? (
          <p className="text-sm text-slate-500">{q ? 'No product matches.' : 'You have no products yet.'}</p>
        ) : (
          <ul aria-label="Matching products" className="divide-y divide-slate-100 rounded-lg border border-slate-200">
            {products.data.content.map((product) => (
              <li key={product.id} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
                <span className="min-w-0">
                  <span className="block truncate font-medium text-slate-900">{product.name}</span>
                  <span className="block text-xs text-slate-500">
                    {product.unitLabel}
                    {product.sku ? ` · SKU ${product.sku}` : ''}
                  </span>
                </span>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={chosen.has(product.id)}
                  aria-label={`Add ${product.name}`}
                  onClick={() => onAdd({ id: product.id, name: product.name })}
                >
                  {chosen.has(product.id) ? 'Added' : 'Add'}
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

/**
 * @typedef {object} PurchaseOrderFormProps
 * @property {PurchaseOrderView | null} order null = a new DRAFT.
 * @property {readonly SupplierView[]} suppliers
 * @property {string} [initialSupplierId] Pre-selected supplier for a new order (`?supplierId=`).
 * @property {(order: PurchaseOrderView) => void} onSaved
 * @property {() => void} onCancel
 */

/**
 * Create or edit a DRAFT purchase order: supplier, expected day, notes and the lines (product, quantity, unit cost).
 * @param {PurchaseOrderFormProps} props
 */
export function PurchaseOrderForm({ order, suppliers, initialSupplierId, onSaved, onCancel }) {
  const save = useSavePurchaseOrder()
  const [defaultValues] = useState(() => purchaseOrderToForm(order, initialSupplierId ?? ''))
  const [formError, setFormError] = useState(null)
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(purchaseOrderSchema), defaultValues })
  const { fields, append, remove } = useFieldArray({ control, name: 'lines' })
  const lines = useWatch({ control, name: 'lines' })
  const chosen = new Set(fields.map((field) => field.productId))
  const selectable = suppliers.filter((supplier) => supplier.active || supplier.id === order?.supplierId)

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const saved = await save.mutateAsync({ id: order?.id ?? null, body: purchaseOrderFormToRequest(values) })
      onSaved(saved)
    } catch (error) {
      if (hasErrorCode(error, PURCHASING_ERRORS.invalidTransition)) {
        setFormError(transitionConflictMessage(error))
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.join(' ') || errorMessage(error))
    }
  })

  const linesError =
    fieldErrorMessage(errors.lines?.root) ??
    (typeof errors.lines?.message === 'string' ? errors.lines.message : undefined)

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-6">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <Card icon="truck" title="Supplier and delivery">
        <div className="grid gap-4 sm:grid-cols-2">
          <SelectField label="Supplier" required error={errors.supplierId?.message} {...register('supplierId')}>
            <option value="">Choose a supplier</option>
            {selectable.map((supplier) => (
              <option key={supplier.id} value={supplier.id}>
                {supplier.name}
                {supplier.active ? '' : ' (inactive)'}
              </option>
            ))}
          </SelectField>
          <TextField label="Expected on" type="date" error={errors.expectedAt?.message} {...register('expectedAt')} />
          <TextareaField
            label="Notes for the supplier"
            rows={2}
            maxLength={PURCHASING_LIMITS.poNotes}
            containerClassName="sm:col-span-2"
            error={errors.notes?.message}
            {...register('notes')}
          />
        </div>
      </Card>

      <Card
        icon="bag"
        title="Products"
        description="Only your own products can be ordered. The name is kept on the order as it is now."
      >
        <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
          <div className="space-y-3">
            {fields.length === 0 ? (
              <EmptyState title="No products yet" description="Search your products on the right and add them." />
            ) : (
              <div className="overflow-x-auto rounded-xl border border-slate-200">
                <table className="min-w-full divide-y divide-slate-200 text-sm">
                  <caption className="sr-only">Order lines</caption>
                  <thead className={TABLE_HEAD_CLASS}>
                    <tr className={TABLE_HEAD_ROW_CLASS}>
                      <th scope="col" className="px-3 py-2">
                        Product
                      </th>
                      <th scope="col" className="px-3 py-2">
                        Quantity
                      </th>
                      <th scope="col" className="px-3 py-2">
                        Unit cost (₹)
                      </th>
                      <th scope="col" className="px-3 py-2 text-right">
                        Total
                      </th>
                      <th scope="col" className="px-3 py-2">
                        <span className="sr-only">Remove</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {fields.map((field, index) => {
                      const quantityError = errors.lines?.[index]?.orderedQuantity?.message
                      const costError = errors.lines?.[index]?.unitCost?.message
                      const line = lines?.[index]
                      const total = line ? formTotal([line]) : 0
                      return (
                        <tr key={field.id}>
                          <th scope="row" className="px-3 py-2 text-left font-medium text-slate-900">
                            {field.productName}
                          </th>
                          <td className="px-3 py-2 align-top">
                            <Input
                              aria-label={`Quantity of ${field.productName}`}
                              inputMode="numeric"
                              className="w-24"
                              invalid={Boolean(quantityError)}
                              {...register(`lines.${index}.orderedQuantity`)}
                            />
                            {quantityError && <p className="mt-1 text-xs font-medium text-red-600">{quantityError}</p>}
                          </td>
                          <td className="px-3 py-2 align-top">
                            <Input
                              aria-label={`Unit cost of ${field.productName}`}
                              inputMode="decimal"
                              className="w-28"
                              invalid={Boolean(costError)}
                              {...register(`lines.${index}.unitCost`)}
                            />
                            {costError && <p className="mt-1 text-xs font-medium text-red-600">{costError}</p>}
                          </td>
                          <td className="px-3 py-2 text-right whitespace-nowrap tabular-nums">{formatMoney(total)}</td>
                          <td className="px-3 py-2 text-right">
                            <Button
                              size="sm"
                              variant="ghost"
                              aria-label={`Remove ${field.productName}`}
                              onClick={() => remove(index)}
                            >
                              Remove
                            </Button>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                  <tfoot className="bg-slate-50">
                    <tr>
                      <th scope="row" colSpan={3} className="px-3 py-2 text-right font-semibold text-slate-900">
                        Order total
                      </th>
                      <td className="px-3 py-2 text-right font-semibold text-slate-900 tabular-nums">
                        {formatMoney(formTotal(lines ?? []))}
                      </td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
            )}
            {linesError && (
              <p role="alert" className="text-sm font-medium text-red-600">
                {linesError}
              </p>
            )}
          </div>
          <ProductPicker
            chosen={chosen}
            onAdd={(product) =>
              append({ productId: product.id, productName: product.name, orderedQuantity: '1', unitCost: '' })
            }
          />
        </div>
      </Card>

      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Save draft
        </Button>
      </div>
    </form>
  )
}
