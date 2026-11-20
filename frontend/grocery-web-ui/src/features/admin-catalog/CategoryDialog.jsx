import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, SelectField, Spinner, TextareaField, TextField } from '../../shared/components/ui'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import { decimalField, emptyToNull, httpUrlField, textField } from '../../shared/forms/validation'
import { flattenTree, indentLabel, subtreeHeight, subtreeIds } from '../catalog/categoryTree'
import { MAX_CATEGORY_DEPTH } from './api'
import { useSaveCategory } from './hooks'

/** @typedef {import('./api').AdminCategoryNode} AdminCategoryNode */

const categorySchema = z.object({
  name: textField({ required: true, requiredMessage: 'Enter a category name.', max: 80 }),
  parentId: z.string(),
  description: textField({ max: 500 }),
  imageUrl: httpUrlField(500),
  sortOrder: decimalField({
    required: true,
    requiredMessage: 'Enter a sort order (0 shows first).',
    min: 0,
    max: 9999,
    decimals: 0,
    integer: true,
  }),
})

/** @typedef {z.infer<typeof categorySchema>} CategoryFormValues */

/**
 * @typedef {object} CategoryDialogProps
 * @property {boolean} open
 * @property {AdminCategoryNode | null} category The category being edited; null to create one.
 * @property {string} parentId Pre-selected parent for a new category ('' = top level).
 * @property {readonly AdminCategoryNode[]} tree
 * @property {() => void} onClose
 */

/**
 * Create or edit (and move) a category. Parents that would create a cycle or a 4th level are not offered.
 * @param {CategoryDialogProps} props
 */
export function CategoryDialog({ open, category, parentId, tree, onClose }) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={category ? `Edit ${category.name}` : 'New category'}
      description={
        category
          ? 'Renaming or moving a category updates every product in it.'
          : 'Vendors can pick the new category a few seconds after you create it.'
      }
      size="lg"
    >
      <CategoryForm category={category} parentId={parentId} tree={tree} onClose={onClose} />
    </Dialog>
  )
}

/** @param {Omit<CategoryDialogProps, 'open'>} props */
function CategoryForm({ category, parentId, tree, onClose }) {
  const toast = useToast()
  const save = useSaveCategory()
  const [formError, setFormError] = useState(null)
  const [defaultValues] = useState(() => ({
    name: category?.name ?? '',
    parentId: category ? (category.parentId ?? '') : parentId,
    description: category?.description ?? '',
    imageUrl: category?.imageUrl ?? '',
    sortOrder: String(category?.sortOrder ?? 0),
  }))
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(categorySchema), defaultValues })

  // A parent at depth d puts this category at d + 1 and its deepest descendant at d + height.
  const excluded = category ? subtreeIds(category) : new Set()
  const height = category ? subtreeHeight(category) : 1
  const parents = flattenTree(tree).filter(
    ({ node, depth }) => !excluded.has(node.id) && depth + height <= MAX_CATEGORY_DEPTH,
  )

  const submit = handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      name: values.name.trim(),
      parentId: values.parentId || null,
      description: emptyToNull(values.description),
      imageUrl: emptyToNull(values.imageUrl),
      sortOrder: Number(values.sortOrder.trim()),
    }
    try {
      const saved = await save.mutateAsync({ id: category?.id, body })
      toast.success(category ? `${saved.name} saved.` : `${saved.name} created.`)
      onClose()
    } catch (error) {
      if (hasErrorCode(error, 'CATEGORY_NAME_TAKEN')) {
        setError(
          'name',
          { type: 'server', message: 'A category with this name already exists at this level.' },
          { shouldFocus: true },
        )
        return
      }
      if (hasErrorCode(error, 'CATEGORY_CYCLE')) {
        setError(
          'parentId',
          { type: 'server', message: 'A category cannot be moved under itself or one of its sub-categories.' },
          { shouldFocus: true },
        )
        return
      }
      if (hasErrorCode(error, 'CATEGORY_TOO_DEEP')) {
        setError(
          'parentId',
          { type: 'server', message: `Categories can be at most ${MAX_CATEGORY_DEPTH} levels deep.` },
          { shouldFocus: true },
        )
        return
      }
      if (hasErrorCode(error, 'CATEGORY_NOT_FOUND')) {
        setFormError('The category or its parent no longer exists. The tree has been refreshed.')
        return
      }
      const { unmatched, placedOnFields } = applyServerFieldErrors(error, setError, defaultValues)
      if (!placedOnFields || unmatched.length > 0) setFormError(unmatched.join(' ') || errorMessage(error))
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
          label="Name"
          required
          maxLength={80}
          placeholder="Fresh Vegetables"
          error={errors.name?.message}
          {...register('name')}
        />
        <SelectField
          label="Parent category"
          hint={`Up to ${MAX_CATEGORY_DEPTH} levels.`}
          error={errors.parentId?.message}
          {...register('parentId')}
        >
          <option value="">None — top level</option>
          {parents.map(({ node, depth }) => (
            <option key={node.id} value={node.id}>
              {indentLabel(node.name, depth)}
              {node.active ? '' : ' (inactive)'}
            </option>
          ))}
        </SelectField>
        <TextareaField
          label="Description"
          rows={2}
          maxLength={500}
          hint="Optional."
          error={errors.description?.message}
          containerClassName="sm:col-span-2"
          {...register('description')}
        />
        <TextField
          label="Image URL"
          type="url"
          maxLength={500}
          placeholder="https://…"
          hint="Optional. Shown on the storefront category grid."
          error={errors.imageUrl?.message}
          {...register('imageUrl')}
        />
        <TextField
          label="Sort order"
          inputMode="numeric"
          required
          hint="0–9999. Lower numbers come first."
          error={errors.sortOrder?.message}
          {...register('sortOrder')}
        />
      </div>
      <div className="flex flex-col-reverse gap-2 border-t border-slate-100 pt-4 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {category ? 'Save category' : 'Create category'}
        </Button>
      </div>
    </form>
  )
}
