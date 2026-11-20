import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useFieldArray, useForm, useWatch } from 'react-hook-form'
import { problemString } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, SelectField, Spinner, TextareaField, TextField } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { formatCount } from '../../shared/utils/format'
import {
  ADJUSTMENT_REASON_LABELS,
  ADJUSTMENT_REASONS,
  ADJUSTMENT_TYPE_LABELS,
  ADJUSTMENT_TYPES,
  type AdjustmentType,
  BULK_MAX_ITEMS,
  listInventory,
  STOCK_LIMITS,
  type StockView,
} from './api'
import { stockRuleMessage, vendorProductErrorMessage } from './errors'
import { useBulkAdjustStock, useVendorInventory } from './hooks'
import {
  type BulkFormValues,
  bulkFormToItems,
  type BulkRowValues,
  bulkSchema,
  DEFAULT_REASON,
  emptyBulkRow,
  type PastedLineError,
  parseStockLines,
} from './schema'

interface BulkUpdateDialogProps {
  open: boolean
  onClose: () => void
}

/** Several stock changes in one all-or-nothing request: rows picked by hand, or pasted `SKU,quantity` lines. */
export function BulkUpdateDialog({ open, onClose }: BulkUpdateDialogProps) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      size="lg"
      title="Bulk stock update"
      description="Change the stock of up to 100 products at once. Either every row is saved, or nothing changes."
    >
      <BulkUpdateForm onClose={onClose} />
    </Dialog>
  )
}

function productLabel(product: StockView): string {
  return [product.productName, product.unitLabel, product.sku].filter(Boolean).join(' · ')
}

function BulkUpdateForm({ onClose }: { onClose: () => void }) {
  const toast = useToast()
  const bulk = useBulkAdjustStock()
  // The first 100 products (sorted by name) feed the product pickers; pasted SKUs beyond them are looked up.
  const products = useVendorInventory({ page: 0, size: BULK_MAX_ITEMS })
  const [lookedUp, setLookedUp] = useState<StockView[]>([])
  const [formError, setFormError] = useState<string | null>(null)
  const [pasteText, setPasteText] = useState('')
  const [pasteErrors, setPasteErrors] = useState<PastedLineError[]>([])
  const [pasteNotice, setPasteNotice] = useState<string | null>(null)
  const [resolving, setResolving] = useState(false)

  const {
    register,
    control,
    handleSubmit,
    setError,
    setValue,
    getValues,
    formState: { errors, isSubmitting, dirtyFields },
  } = useForm<BulkFormValues>({ resolver: zodResolver(bulkSchema), defaultValues: { items: [emptyBulkRow()], note: '' } })
  const { fields, append, remove, replace } = useFieldArray({ control, name: 'items' })
  const rows = useWatch({ control, name: 'items' })

  const byId = new Map<string, StockView>()
  for (const product of [...(products.data?.content ?? []), ...lookedUp]) byId.set(product.productId, product)
  const options = [...byId.values()].sort((a, b) => a.productName.localeCompare(b.productName))
  const allLoaded = products.data !== undefined && products.data.totalElements <= products.data.content.length

  async function addPastedLines() {
    setPasteNotice(null)
    const parsed = parseStockLines(pasteText)
    if (parsed.lines.length === 0 && parsed.errors.length === 0) {
      setPasteErrors([{ line: null, message: 'Paste at least one line, e.g. TOM-500,24.' }])
      return
    }
    setResolving(true)
    try {
      const bySku = new Map<string, StockView>()
      for (const product of options) if (product.sku) bySku.set(product.sku.toLowerCase(), product)
      // `q` matches a SKU exactly, so a SKU outside the first page costs one small request.
      const found: StockView[] = []
      if (!allLoaded) {
        for (const line of parsed.lines.filter((entry) => !bySku.has(entry.sku.toLowerCase()))) {
          try {
            const page = await listInventory({ q: line.sku, page: 0, size: 5 })
            const match = page.content.find((product) => product.sku?.toLowerCase() === line.sku.toLowerCase())
            if (match) {
              bySku.set(line.sku.toLowerCase(), match)
              found.push(match)
            }
          } catch {
            // Reported below as an unknown SKU.
          }
        }
      }

      const lineErrors = [...parsed.errors]
      const added: BulkRowValues[] = []
      for (const line of parsed.lines) {
        const product = bySku.get(line.sku.toLowerCase())
        if (!product) {
          lineErrors.push({ line: line.line, message: `Line ${line.line}: none of your products has SKU ${line.sku}.` })
          continue
        }
        added.push({ productId: product.productId, type: 'SET', quantity: String(line.quantity), reason: 'STOCK_COUNT' })
      }

      // Pasted lines replace rows of the same product; untouched blank rows are dropped.
      const kept = getValues('items').filter(
        (row) => !(row.productId === '' && row.quantity.trim() === '') && !added.some((entry) => entry.productId === row.productId),
      )
      if (kept.length + added.length > BULK_MAX_ITEMS) {
        setPasteErrors([{ line: null, message: `A bulk update takes at most ${BULK_MAX_ITEMS} products. Remove some rows first.` }])
        return
      }
      if (found.length > 0) setLookedUp((previous) => [...previous, ...found])
      if (added.length > 0) {
        replace([...kept, ...added])
        setPasteNotice(`Added ${added.length} row${added.length === 1 ? '' : 's'} that set the counted stock.`)
      }
      lineErrors.sort((a, b) => (a.line ?? 0) - (b.line ?? 0))
      setPasteErrors(lineErrors)
      // Keep only the lines that still need fixing.
      const failed = new Set(lineErrors.map((entry) => entry.line))
      setPasteText(
        pasteText
          .split(/\r?\n/)
          .filter((_, index) => failed.has(index + 1))
          .join('\n'),
      )
    } finally {
      setResolving(false)
    }
  }

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const saved = await bulk.mutateAsync(bulkFormToItems(values))
      toast.success(`Stock updated for ${saved.length} product${saved.length === 1 ? '' : 's'}.`)
      onClose()
    } catch (error) {
      // All or nothing: the error names the product that failed in its `productId` property.
      const failedId = problemString(error, 'productId')
      const index = failedId ? values.items.findIndex((row) => row.productId === failedId) : -1
      if (failedId && index >= 0) {
        const product = byId.get(failedId)
        const ruleMessage = stockRuleMessage(error, product ?? { available: 0, reserved: 0 })
        const message = ruleMessage ?? vendorProductErrorMessage(error)
        setError(ruleMessage ? `items.${index}.quantity` : `items.${index}.productId`, { type: 'server', message }, { shouldFocus: true })
        setFormError(`Nothing was saved. ${product?.productName ?? 'A product'} (row ${index + 1}): ${message}`)
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, values)
      setFormError(
        `Nothing was saved. ${placedOnFields && unmatched.length === 0 ? 'Fix the marked rows and try again.' : unmatched.join(' ') || vendorProductErrorMessage(error)}`,
      )
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-5">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      {products.isError && (
        <Alert tone="warning" role="status">
          Your products could not be loaded for the product lists: {products.error.message}
        </Alert>
      )}

      <section aria-labelledby="bulk-paste-title" className="space-y-2 rounded-xl bg-slate-50 p-4">
        <h3 id="bulk-paste-title" className="text-sm font-semibold text-slate-900">
          Paste from a spreadsheet
        </h3>
        <TextareaField
          label="SKU,quantity lines"
          rows={3}
          value={pasteText}
          onChange={(event) => setPasteText(event.target.value)}
          placeholder={'TOM-500,24\nONI-1KG,10'}
          hint="One product per line. Each line sets the counted stock on hand of the product with that SKU."
        />
        {pasteErrors.length > 0 && (
          <ul role="alert" aria-label="Lines that could not be added" className="space-y-0.5 text-xs font-medium text-red-600">
            {pasteErrors.map((entry) => (
              <li key={`${entry.line ?? 'all'}-${entry.message}`}>{entry.message}</li>
            ))}
          </ul>
        )}
        {pasteNotice && (
          <p role="status" className="text-xs font-medium text-emerald-700">
            {pasteNotice}
          </p>
        )}
        <Button variant="secondary" size="sm" onClick={() => void addPastedLines()} disabled={resolving || pasteText.trim() === ''}>
          {resolving && <Spinner size="sm" />}
          Add lines
        </Button>
      </section>

      <fieldset className="space-y-3">
        <legend className="text-sm font-semibold text-slate-900">Changes ({fields.length})</legend>
        <ol className="space-y-3">
          {fields.map((field, index) => {
            const row = rows[index]
            const selected = row?.productId ? byId.get(row.productId) : undefined
            const rowErrors = errors.items?.[index]
            const typeField = register(`items.${index}.type`, {
              onChange: (event: { target: { value: AdjustmentType } }) => {
                if (!dirtyFields.items?.[index]?.reason) setValue(`items.${index}.reason`, DEFAULT_REASON[event.target.value])
              },
            })
            return (
              <li
                key={field.id}
                className="grid gap-3 rounded-xl border border-slate-200 p-3 sm:grid-cols-[minmax(0,2fr)_minmax(0,1.2fr)_6rem_minmax(0,1.2fr)_auto] sm:items-start"
              >
                <SelectField
                  label={
                    <>
                      Product<span className="sr-only"> (row {index + 1})</span>
                    </>
                  }
                  required
                  error={rowErrors?.productId?.message}
                  hint={selected ? `${formatCount(selected.onHand)} on hand · ${formatCount(selected.reserved)} reserved` : undefined}
                  {...register(`items.${index}.productId`)}
                >
                  <option value="">{products.isPending ? 'Loading products…' : 'Choose a product'}</option>
                  {options.map((product) => (
                    <option key={product.productId} value={product.productId}>
                      {productLabel(product)}
                    </option>
                  ))}
                </SelectField>
                <SelectField
                  label={
                    <>
                      Change<span className="sr-only"> (row {index + 1})</span>
                    </>
                  }
                  required
                  {...typeField}
                >
                  {ADJUSTMENT_TYPES.map((value) => (
                    <option key={value} value={value}>
                      {ADJUSTMENT_TYPE_LABELS[value]}
                    </option>
                  ))}
                </SelectField>
                <TextField
                  label={
                    <>
                      Quantity<span className="sr-only"> (row {index + 1})</span>
                    </>
                  }
                  inputMode="numeric"
                  required
                  error={rowErrors?.quantity?.message}
                  {...register(`items.${index}.quantity`)}
                />
                <SelectField
                  label={
                    <>
                      Reason<span className="sr-only"> (row {index + 1})</span>
                    </>
                  }
                  required
                  {...register(`items.${index}.reason`)}
                >
                  {ADJUSTMENT_REASONS.map((value) => (
                    <option key={value} value={value}>
                      {ADJUSTMENT_REASON_LABELS[value]}
                    </option>
                  ))}
                </SelectField>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-red-600 hover:bg-red-50 hover:text-red-700 sm:mt-7"
                  aria-label={`Remove row ${index + 1}`}
                  disabled={fields.length === 1}
                  onClick={() => remove(index)}
                >
                  Remove
                </Button>
              </li>
            )
          })}
        </ol>
        <Button variant="secondary" size="sm" onClick={() => append(emptyBulkRow())} disabled={fields.length >= BULK_MAX_ITEMS}>
          Add row
        </Button>
      </fieldset>

      <TextareaField
        label="Note"
        rows={2}
        maxLength={STOCK_LIMITS.note}
        hint="Optional. Saved with every row in the stock history."
        error={errors.note?.message}
        {...register('note')}
      />

      <div className="flex flex-col-reverse gap-2 border-t border-slate-100 pt-4 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting || resolving}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Save all changes
        </Button>
      </div>
    </form>
  )
}
