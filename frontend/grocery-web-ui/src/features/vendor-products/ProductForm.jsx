import { zodResolver } from '@hookform/resolvers/zod'
import { useId, useState } from 'react'
import { Controller, useFieldArray, useForm, useWatch } from 'react-hook-form'
import { Link } from 'react-router'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import {
  Alert,
  Button,
  Input,
  SelectField,
  Spinner,
  TagInput,
  TextareaField,
  TextField,
} from '../../shared/components/ui'
import { applyServerFieldErrors, fieldErrorMessage } from '../../shared/forms/serverErrors'
import { isHttpUrl } from '../../shared/forms/validation'
import { useDebouncedValue } from '../../shared/hooks/useDebouncedValue'
import { cn } from '../../shared/utils/cn'
import { FOOD_TYPE_LABELS, FOOD_TYPES } from '../../shared/utils/foodType'
import { UNITS_OF_MEASURE } from '../catalog/api'
import { flattenTree, indentLabel, PATH_SEPARATOR } from '../catalog/categoryTree'
import { ProductImage } from '../catalog/ProductImage'
import { FormSection } from '../stakeholders/OnboardingLayout'
import { VENDOR_NOT_ACTIVE } from './api'
import { useBrandSuggestions, useSaveVendorProduct } from './hooks'
import { VendorNotActiveNotice } from './ProductStatusActions'
import {
  EMPTY_PRODUCT_FORM,
  keptRows,
  normalizeTag,
  PRODUCT_LIMITS,
  productFormToRequest,
  productSchema,
  productToFormValues,
  serverFieldAliases,
  tagError,
  UNIT_LABELS,
} from './schema'

/** @typedef {import('../catalog/api').CategoryNode} CategoryNode */
/** @typedef {import('./api').ProductView} ProductView */

/**
 * @typedef {object} ProductFormProps
 * @property {ProductView} [product] The product being edited; omitted when creating one.
 * @property {readonly CategoryNode[]} categories Active categories (public tree).
 * @property {string | null} [categoriesError]
 * @property {boolean} [categoriesRefreshing]
 * @property {() => void} onRefreshCategories
 * @property {(view: ProductView, created: boolean) => void} onSaved
 */

/**
 * Create / edit a product (contract §3 ProductRequest). Archived products are shown read-only.
 * Server validation errors land on the matching fields, including rows of the attribute and image lists.
 * @param {ProductFormProps} props
 */
export function ProductForm({
  product,
  categories,
  categoriesError,
  categoriesRefreshing,
  onRefreshCategories,
  onSaved,
}) {
  const save = useSaveVendorProduct()
  const brandListId = useId()
  const [formError, setFormError] = useState(null)
  const [notActive, setNotActive] = useState(false)
  const [defaultValues] = useState(() => (product ? productToFormValues(product) : EMPTY_PRODUCT_FORM))
  const readOnly = product?.status === 'ARCHIVED'
  // On phones the actions stick to the bottom of the screen: the "Ask AI" button moves above them.

  const {
    register,
    control,
    handleSubmit,
    setError,
    getValues,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(productSchema), defaultValues })
  const attributes = useFieldArray({ control, name: 'attributes' })
  const images = useFieldArray({ control, name: 'imageUrls' })
  const imageValues = useWatch({ control, name: 'imageUrls' })
  const brandText = useWatch({ control, name: 'brandName' })
  const debouncedBrand = useDebouncedValue(readOnly ? '' : brandText, 250)
  const brands = useBrandSuggestions(debouncedBrand)

  const flat = flattenTree(categories)
  const currentCategory = product?.category ?? null
  const currentMissing = currentCategory !== null && !flat.some(({ node }) => node.id === currentCategory.id)

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    setNotActive(false)
    const kept = keptRows(values)
    try {
      const saved = await save.mutateAsync({ id: product?.id, body: productFormToRequest(values) })
      onSaved(saved, product === undefined)
    } catch (error) {
      if (hasErrorCode(error, VENDOR_NOT_ACTIVE)) {
        setNotActive(true)
        return
      }
      if (hasErrorCode(error, 'SKU_ALREADY_EXISTS')) {
        setError(
          'sku',
          { type: 'server', message: 'Another of your products already uses this SKU.' },
          { shouldFocus: true },
        )
        return
      }
      if (hasErrorCode(error, 'CATEGORY_INACTIVE', 'CATEGORY_NOT_FOUND')) {
        setError(
          'categoryId',
          { type: 'server', message: 'This category is not available any more. Choose another one.' },
          { shouldFocus: true },
        )
        onRefreshCategories()
        return
      }
      if (hasErrorCode(error, 'PRODUCT_ARCHIVED')) {
        setFormError('This product is archived and can no longer be edited.')
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(
        error,
        setError,
        getValues(),
        serverFieldAliases(kept),
      )
      if (!placedOnFields || unmatched.length > 0) {
        setFormError(`Could not save the product: ${unmatched.length > 0 ? unmatched.join(' ') : errorMessage(error)}`)
      }
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-5">
      {notActive && <VendorNotActiveNotice />}
      {readOnly && (
        <Alert tone="neutral" role="status" title="Archived product">
          Archived products are kept for order history and can no longer be edited or published.
        </Alert>
      )}
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}

      <fieldset disabled={readOnly || isSubmitting} className="space-y-5">
        <legend className="sr-only">Product details</legend>

        <FormSection title="Basics" description="What customers see first. Fields marked * are required.">
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField
              label="Product name"
              required
              maxLength={PRODUCT_LIMITS.name}
              placeholder="Tomato"
              error={errors.name?.message}
              containerClassName="sm:col-span-2"
              {...register('name')}
            />
            <Controller
              control={control}
              name="categoryId"
              render={({ field, fieldState }) => (
                <SelectField
                  label="Category"
                  required
                  error={fieldState.error?.message}
                  hint={
                    <>
                      {categoriesError ? `Categories could not be loaded: ${categoriesError} ` : null}A category an
                      admin has just created appears here after a few seconds.{' '}
                      <button
                        type="button"
                        onClick={onRefreshCategories}
                        disabled={categoriesRefreshing}
                        className="font-semibold text-emerald-700 hover:text-emerald-800 disabled:opacity-60"
                      >
                        {categoriesRefreshing ? 'Refreshing…' : 'Refresh list'}
                      </button>
                    </>
                  }
                  containerClassName="sm:col-span-2"
                  name={field.name}
                  ref={field.ref}
                  value={field.value}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                >
                  <option value="">Choose a category…</option>
                  {currentMissing && currentCategory && (
                    <option value={currentCategory.id}>
                      {currentCategory.path.join(PATH_SEPARATOR) || currentCategory.name}
                      {currentCategory.active ? '' : ' (inactive)'}
                    </option>
                  )}
                  {flat.map(({ node, depth }) => (
                    <option key={node.id} value={node.id}>
                      {indentLabel(node.name, depth)}
                    </option>
                  ))}
                </SelectField>
              )}
            />
            <div className="sm:col-span-2">
              <TextField
                label="Brand"
                list={brandListId}
                autoComplete="off"
                maxLength={PRODUCT_LIMITS.brand}
                hint="Optional. Pick an existing brand or type a new one."
                error={errors.brandName?.message}
                {...register('brandName')}
              />
              <datalist id={brandListId}>
                {(brands.data ?? []).map((brand) => (
                  <option key={brand.id} value={brand.name} />
                ))}
              </datalist>
            </div>
            <TextareaField
              label="Description"
              rows={4}
              maxLength={PRODUCT_LIMITS.description}
              hint={`Optional. Up to ${PRODUCT_LIMITS.description.toLocaleString('en-IN')} characters.`}
              error={errors.description?.message}
              containerClassName="sm:col-span-2"
              {...register('description')}
            />
          </div>
        </FormSection>

        <FormSection title="Pack size, codes and food type">
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField
              label="Pack size"
              required
              inputMode="decimal"
              placeholder="500"
              hint="Up to 3 decimals, e.g. 0.5"
              error={errors.unitValue?.message}
              {...register('unitValue')}
            />
            <SelectField label="Unit" required error={errors.unitOfMeasure?.message} {...register('unitOfMeasure')}>
              {UNITS_OF_MEASURE.map((unit) => (
                <option key={unit} value={unit}>
                  {UNIT_LABELS[unit]}
                </option>
              ))}
            </SelectField>
            <TextField
              label="SKU"
              maxLength={40}
              autoComplete="off"
              hint="Optional. Your own code; unique among your products."
              error={errors.sku?.message}
              {...register('sku')}
            />
            <TextField
              label="Barcode"
              inputMode="numeric"
              maxLength={14}
              hint="Optional. EAN / UPC, 8–14 digits."
              error={errors.barcode?.message}
              {...register('barcode')}
            />
            <fieldset className="sm:col-span-2">
              <legend className="text-sm font-medium text-slate-700">Food type</legend>
              <div className="mt-2 flex flex-wrap gap-2">
                {['', ...FOOD_TYPES].map((value) => (
                  <label
                    key={value || 'none'}
                    className="flex cursor-pointer items-center gap-2 rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-700 has-[:checked]:border-emerald-400 has-[:checked]:bg-emerald-50"
                  >
                    <input type="radio" value={value} className="accent-emerald-600" {...register('foodType')} />
                    {value ? (
                      <>
                        <FoodTypeMark foodType={value} />
                        {FOOD_TYPE_LABELS[value]}
                      </>
                    ) : (
                      'Not applicable'
                    )}
                  </label>
                ))}
              </div>
            </fieldset>
          </div>
        </FormSection>

        <FormSection title="Tags and attributes" description="Help customers find and compare the product.">
          <div className="space-y-5">
            <Controller
              control={control}
              name="tags"
              render={({ field }) => (
                <TagInput
                  label="Tags"
                  value={field.value}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                  normalize={normalizeTag}
                  validate={tagError}
                  maxTags={PRODUCT_LIMITS.tags}
                  placeholder="organic, local…"
                  hint={`Optional. Press Enter or comma after each tag (up to ${PRODUCT_LIMITS.tags}).`}
                  error={fieldErrorMessage(errors.tags)}
                  disabled={readOnly}
                />
              )}
            />

            <div>
              <div className="flex items-center justify-between gap-3">
                <p className="text-sm font-medium text-slate-700" id="attributes-label">
                  Attributes
                </p>
                <span className="text-xs text-slate-500">
                  {attributes.fields.length} / {PRODUCT_LIMITS.attributes}
                </span>
              </div>
              <p className="text-xs text-slate-500">Optional details such as Origin → Nashik or Shelf life → 7 days.</p>
              {attributes.fields.length > 0 && (
                <ul className="mt-3 space-y-2" aria-labelledby="attributes-label">
                  {attributes.fields.map((row, index) => {
                    const rowErrors = errors.attributes?.[index]
                    const rowError =
                      rowErrors?.name?.message ?? rowErrors?.value?.message ?? fieldErrorMessage(rowErrors)
                    return (
                      <li key={row.id}>
                        <div className="flex flex-col gap-2 sm:flex-row">
                          <Input
                            aria-label={`Attribute ${index + 1} name`}
                            placeholder="Name"
                            maxLength={50}
                            invalid={Boolean(rowErrors?.name)}
                            className="sm:w-48"
                            {...register(`attributes.${index}.name`)}
                          />
                          <Input
                            aria-label={`Attribute ${index + 1} value`}
                            placeholder="Value"
                            maxLength={200}
                            invalid={Boolean(rowErrors?.value)}
                            {...register(`attributes.${index}.value`)}
                          />
                          <Button
                            variant="ghost"
                            size="md"
                            aria-label={`Remove attribute ${index + 1}`}
                            onClick={() => attributes.remove(index)}
                            className="shrink-0"
                          >
                            Remove
                          </Button>
                        </div>
                        {rowError && <p className="mt-1 text-xs font-medium text-red-600">{rowError}</p>}
                      </li>
                    )
                  })}
                </ul>
              )}
              {!readOnly && (
                <Button
                  variant="secondary"
                  size="sm"
                  className="mt-3"
                  disabled={attributes.fields.length >= PRODUCT_LIMITS.attributes}
                  onClick={() => attributes.append({ name: '', value: '' })}
                >
                  Add attribute
                </Button>
              )}
              {fieldErrorMessage(errors.attributes?.root) && (
                <p className="mt-1 text-xs font-medium text-red-600">{fieldErrorMessage(errors.attributes?.root)}</p>
              )}
            </div>
          </div>
        </FormSection>

        <FormSection title="Images" description="Paste http(s) image addresses. The first image is the main photo.">
          {images.fields.length > 0 && (
            <ul className="space-y-3">
              {images.fields.map((row, index) => {
                const url = imageValues[index]?.value.trim() ?? ''
                const rowError =
                  errors.imageUrls?.[index]?.value?.message ?? fieldErrorMessage(errors.imageUrls?.[index])
                return (
                  <li key={row.id} className="flex gap-3">
                    <ProductImage
                      src={isHttpUrl(url) ? url : null}
                      alt={url ? `Preview of image ${index + 1}` : ''}
                      className="size-16 shrink-0 rounded-lg border border-slate-200"
                      iconClassName="size-6"
                    />
                    <div className="min-w-0 flex-1 space-y-1.5">
                      <Input
                        type="url"
                        aria-label={`Image URL ${index + 1}`}
                        placeholder="https://…"
                        maxLength={500}
                        invalid={Boolean(rowError)}
                        {...register(`imageUrls.${index}.value`)}
                      />
                      {rowError && <p className="text-xs font-medium text-red-600">{rowError}</p>}
                      <div className="flex flex-wrap items-center gap-2">
                        {index === 0 ? (
                          <span className="text-xs font-medium text-emerald-700">Main photo</span>
                        ) : (
                          !readOnly && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => images.move(index, 0)}
                              aria-label={`Make image ${index + 1} the main photo`}
                            >
                              Make main photo
                            </Button>
                          )
                        )}
                        {!readOnly && (
                          <Button
                            variant="ghost"
                            size="sm"
                            className="text-red-600 hover:bg-red-50 hover:text-red-700"
                            onClick={() => images.remove(index)}
                            aria-label={`Remove image ${index + 1}`}
                          >
                            Remove
                          </Button>
                        )}
                      </div>
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
          {!readOnly && (
            <Button
              variant="secondary"
              size="sm"
              className={cn(images.fields.length > 0 && 'mt-4')}
              disabled={images.fields.length >= PRODUCT_LIMITS.images}
              onClick={() => images.append({ value: '' })}
            >
              Add image
            </Button>
          )}
          <p className="mt-2 text-xs text-slate-500">
            {images.fields.length} / {PRODUCT_LIMITS.images} images
          </p>
        </FormSection>
      </fieldset>

      {!readOnly && (
        <div className="sticky bottom-0 z-10 -mx-4 flex flex-col-reverse gap-2 border-t border-slate-200 bg-white/95 px-4 py-3 backdrop-blur sm:static sm:mx-0 sm:flex-row sm:justify-end sm:rounded-xl sm:border sm:px-5">
          <Link
            to="/vendor/products"
            className="inline-flex h-10 items-center justify-center rounded-lg px-4 text-sm font-medium text-slate-600 hover:bg-slate-100"
          >
            Cancel
          </Link>
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
            {product ? 'Save changes' : 'Create product'}
          </Button>
        </div>
      )}
    </form>
  )
}
