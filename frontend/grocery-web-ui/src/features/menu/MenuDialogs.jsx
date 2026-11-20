import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { hasErrorCode } from '../../shared/api/problemDetail'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
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
import { FOOD_TYPE_LABELS, FOOD_TYPES } from '../../shared/utils/foodType'
import { MENU_LIMITS } from './api'
import { menuErrorMessage } from './errors'
import { useSaveMenuCategory, useSaveMenuItem } from './hooks'
import {
  categoryFormToRequest,
  categoryToFormValues,
  itemFormToRequest,
  itemToFormValues,
  menuCategorySchema,
  menuItemSchema,
} from './schema'

/** @typedef {import('./api').MenuCategoryView} MenuCategoryView */
/** @typedef {import('./api').MenuItemView} MenuItemView */

/** @param {{ submitting: boolean, submitLabel: string, onClose: () => void }} props */
function FormActions({ submitting, submitLabel, onClose }) {
  return (
    <div className="flex flex-col-reverse gap-2 border-t border-slate-100 pt-4 sm:flex-row sm:justify-end">
      <Button variant="secondary" onClick={onClose} disabled={submitting}>
        Cancel
      </Button>
      <Button type="submit" disabled={submitting}>
        {submitting && <Spinner size="sm" className="[&_svg]:text-white" />}
        {submitLabel}
      </Button>
    </div>
  )
}

/**
 * @typedef {object} MenuCategoryDialogProps
 * @property {boolean} open
 * @property {MenuCategoryView | null} category null = new category.
 * @property {number} nextSortOrder Suggested sort order for a new category (after the last one).
 * @property {() => void} onClose
 */

/** @param {MenuCategoryDialogProps} props */
export function MenuCategoryDialog({ open, category, nextSortOrder, onClose }) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={category ? `Edit ${category.name}` : 'Add menu category'}
      description="Categories group your dishes, e.g. Starters, Biryani, Desserts."
    >
      <MenuCategoryForm category={category} nextSortOrder={nextSortOrder} onClose={onClose} />
    </Dialog>
  )
}

/** @param {Omit<MenuCategoryDialogProps, 'open'>} props */
function MenuCategoryForm({ category, nextSortOrder, onClose }) {
  const toast = useToast()
  const save = useSaveMenuCategory()
  const [formError, setFormError] = useState(null)
  const [defaultValues] = useState(() => categoryToFormValues(category, nextSortOrder))
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(menuCategorySchema), defaultValues })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const saved = await save.mutateAsync({ id: category?.id, body: categoryFormToRequest(values) })
      toast.success(category ? `${saved.name} updated.` : `${saved.name} added to your menu.`)
      onClose()
    } catch (error) {
      if (hasErrorCode(error, 'MENU_CATEGORY_NAME_TAKEN')) {
        setError(
          'name',
          { type: 'server', message: 'Your menu already has a category with this name.' },
          { shouldFocus: true },
        )
        return
      }
      if (hasErrorCode(error, 'MENU_LIMIT_REACHED')) {
        setFormError(`A menu can have up to ${MENU_LIMITS.categories} categories. Remove one before adding another.`)
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.join(' ') || menuErrorMessage(error))
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <TextField
        label="Name"
        required
        maxLength={80}
        placeholder="Biryani"
        error={errors.name?.message}
        {...register('name')}
      />
      <TextareaField
        label="Description"
        rows={2}
        maxLength={300}
        hint="Optional. Shown under the category name."
        error={errors.description?.message}
        {...register('description')}
      />
      <TextField
        label="Sort order"
        inputMode="numeric"
        required
        hint="0–9999. Lower numbers are shown first."
        error={errors.sortOrder?.message}
        {...register('sortOrder')}
      />
      <FormActions
        submitting={isSubmitting}
        submitLabel={category ? 'Save category' : 'Add category'}
        onClose={onClose}
      />
    </form>
  )
}

/**
 * @typedef {object} MenuItemDialogProps
 * @property {boolean} open
 * @property {MenuItemView | null} item null = new dish.
 * @property {readonly MenuCategoryView[]} categories
 * @property {string} defaultCategoryId Pre-selected category for a new dish.
 * @property {() => void} onClose
 */

/** @param {MenuItemDialogProps} props */
export function MenuItemDialog({ open, item, categories, defaultCategoryId, onClose }) {
  return (
    <Dialog open={open} onClose={onClose} title={item ? `Edit ${item.name}` : 'Add dish'} size="lg">
      <MenuItemForm item={item} categories={categories} defaultCategoryId={defaultCategoryId} onClose={onClose} />
    </Dialog>
  )
}

/** @param {Omit<MenuItemDialogProps, 'open'>} props */
function MenuItemForm({ item, categories, defaultCategoryId, onClose }) {
  const toast = useToast()
  const save = useSaveMenuItem()
  const [formError, setFormError] = useState(null)
  const [defaultValues] = useState(() => itemToFormValues(item, defaultCategoryId))
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(menuItemSchema), defaultValues })

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      const saved = await save.mutateAsync({ id: item?.id, body: itemFormToRequest(values) })
      toast.success(item ? `${saved.name} updated.` : `${saved.name} added to your menu.`)
      onClose()
    } catch (error) {
      if (hasErrorCode(error, 'MENU_LIMIT_REACHED')) {
        setFormError(`A menu can have up to ${MENU_LIMITS.items} dishes. Remove one before adding another.`)
        return
      }
      if (hasErrorCode(error, 'MENU_CATEGORY_NOT_FOUND')) {
        setError(
          'categoryId',
          { type: 'server', message: 'This category no longer exists. Choose another one.' },
          { shouldFocus: true },
        )
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.join(' ') || menuErrorMessage(error))
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Dish name"
          required
          maxLength={120}
          placeholder="Chicken Biryani"
          error={errors.name?.message}
          containerClassName="sm:col-span-2"
          {...register('name')}
        />
        <SelectField label="Category" required error={errors.categoryId?.message} {...register('categoryId')}>
          <option value="">Choose a category…</option>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </SelectField>
        <TextField
          label="Price (₹)"
          required
          inputMode="decimal"
          placeholder="249"
          hint="₹1 – ₹1,00,000, up to 2 decimals."
          error={errors.price?.message}
          {...register('price')}
        />
        <fieldset className="sm:col-span-2">
          <legend className="text-sm font-medium text-slate-700">
            Food type
            <span className="ml-0.5 text-red-600" aria-hidden="true">
              *
            </span>
          </legend>
          <div className="mt-2 flex flex-wrap gap-2">
            {FOOD_TYPES.map((value) => (
              <label
                key={value}
                className="flex cursor-pointer items-center gap-2 rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-700 has-[:checked]:border-emerald-400 has-[:checked]:bg-emerald-50"
              >
                <input type="radio" value={value} className="accent-emerald-600" {...register('foodType')} />
                <FoodTypeMark foodType={value} />
                {FOOD_TYPE_LABELS[value]}
              </label>
            ))}
          </div>
          {errors.foodType?.message && (
            <p className="mt-1.5 text-xs font-medium text-red-600">{errors.foodType.message}</p>
          )}
        </fieldset>
        <TextareaField
          label="Description"
          rows={3}
          maxLength={500}
          hint="Optional. Ingredients, portion size, spice level…"
          error={errors.description?.message}
          containerClassName="sm:col-span-2"
          {...register('description')}
        />
        <TextField
          label="Image URL"
          type="url"
          maxLength={500}
          placeholder="https://…"
          hint="Optional."
          error={errors.imageUrl?.message}
          {...register('imageUrl')}
        />
        <TextField
          label="Sort order"
          inputMode="numeric"
          required
          hint="Lower numbers are shown first."
          error={errors.sortOrder?.message}
          {...register('sortOrder')}
        />
        <label className="flex items-center gap-2 text-sm text-slate-700 sm:col-span-2">
          <Checkbox {...register('available')} />
          Available to order (untick to mark it sold out)
        </label>
      </div>
      <FormActions submitting={isSubmitting} submitLabel={item ? 'Save dish' : 'Add dish'} onClose={onClose} />
    </form>
  )
}
