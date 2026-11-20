import { useState } from 'react'
import { ApiError, errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { useAuth } from '../../shared/auth/useAuth'
import { useToast } from '../../shared/components/toast/useToast'
import {
  ActionMenu,
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  EmptyState,
  ErrorState,
  Spinner,
  StatCard,
} from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCount } from '../../shared/utils/format'
import { flattenTree } from '../catalog/categoryTree'
import { MAX_CATEGORY_DEPTH } from './api'
import { CategoryDialog } from './CategoryDialog'
import { useAdminCategories, useCategoryAction } from './hooks'

/** @typedef {import('../catalog/categoryTree').FlatNode} FlatNode */
/** @typedef {import('./api').AdminCategoryNode} AdminCategoryNode */
/** @typedef {import('./hooks').CategoryAction} CategoryAction */

/** @typedef {FlatNode<AdminCategoryNode>} Row */

/**
 * @typedef {(
 *   | { type: 'edit', category: AdminCategoryNode | null, parentId: string }
 *   | { type: 'deactivate' | 'delete', category: AdminCategoryNode }
 *   | null
 * )} DialogState
 */

/**
 * The small tile before a category name: one colour per tree level (1 = top level).
 * @type {Record<number, string>}
 */
const LEVEL_TILES = {
  1: 'from-emerald-50 to-teal-50 ring-emerald-100',
  2: 'from-sky-50 to-blue-50 ring-sky-100',
  3: 'from-violet-50 to-fuchsia-50 ring-violet-100',
}

/**
 * @param {unknown} error
 * @param {CategoryAction} action
 * @returns {string}
 */
function actionError(error, action) {
  if (hasErrorCode(error, 'CATEGORY_IN_USE')) {
    return 'This category still has sub-categories or products. Move or archive them first, or deactivate the category instead.'
  }
  if (hasErrorCode(error, 'CATEGORY_NOT_FOUND')) return 'The category no longer exists. The tree has been refreshed.'
  return `Could not ${action} the category: ${errorMessage(error)}`
}

/**
 * Headline numbers of the tree, counted from the categories already loaded (no extra request).
 * @param {{ rows: readonly Row[], topLevel: number }} props
 */
function CategorySummary({ rows, topLevel }) {
  const active = rows.filter(({ node }) => node.active).length
  const inactive = rows.length - active
  // `productCount` counts the products directly in a category, so the sum counts every product once.
  const products = rows.reduce((sum, { node }) => sum + node.productCount, 0)
  return (
    <section aria-label="Category summary">
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard
          label="Categories"
          value={formatCount(rows.length)}
          icon="grid"
          accent="sky"
          hint={`${formatCount(topLevel)} at the top level`}
        />
        <StatCard label="Active" value={formatCount(active)} icon="eye" accent="emerald" hint="Shown to customers" />
        <StatCard
          label="Inactive"
          value={formatCount(inactive)}
          icon="eyeOff"
          accent="amber"
          valueClassName={inactive > 0 ? 'text-amber-700' : undefined}
          hint="Hidden from customers"
        />
        <StatCard label="Products" value={formatCount(products)} icon="box" accent="violet" hint="Not archived" />
      </dl>
    </section>
  )
}

/** The category tree with product counts; SUPER_ADMIN / OPS_ADMIN can create, edit, move, (de)activate and delete. */
export function CategoriesTab() {
  const { can } = useAuth()
  const toast = useToast()
  const canManage = can('catalog.manage')
  const categories = useAdminCategories()
  const action = useCategoryAction()
  const [dialog, setDialog] = useState(null)
  const [confirmError, setConfirmError] = useState(null)

  function close() {
    setDialog(null)
    setConfirmError(null)
  }

  function activate(category) {
    action.mutate(
      { action: 'activate', id: category.id },
      {
        onSuccess: () => toast.success(`${category.name} is active again.`),
        onError: (error) => toast.error(actionError(error, 'activate')),
      },
    )
  }

  function confirmAction(type, category) {
    setConfirmError(null)
    action.mutate(
      { action: type, id: category.id },
      {
        onSuccess: () => {
          close()
          toast.success(
            type === 'delete' ? `${category.name} was deleted.` : `${category.name} is now hidden from customers.`,
          )
        },
        onError: (error) => setConfirmError(actionError(error, type)),
      },
    )
  }

  if (categories.isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner size="lg" label="Loading categories" />
      </div>
    )
  }
  if (categories.isError) {
    return (
      <ErrorState
        title="Could not load categories"
        message={categories.error.message}
        correlationId={categories.error instanceof ApiError ? categories.error.correlationId : null}
        onRetry={() => void categories.refetch()}
      />
    )
  }

  const tree = categories.data
  const rows = flattenTree(tree)
  const busyId = action.isPending ? action.variables.id : null

  /** The row's "⋯" menu: "Delete" (and "Deactivate") still open their confirmation dialog. */
  function rowActions(node, depth) {
    const busy = busyId === node.id
    const items = []
    // Third-level categories cannot have children.
    if (depth < MAX_CATEGORY_DEPTH) {
      items.push({
        label: 'Add sub-category',
        icon: 'plus',
        onSelect: () => setDialog({ type: 'edit', category: null, parentId: node.id }),
      })
    }
    items.push({
      label: 'Edit',
      icon: 'pencil',
      onSelect: () => setDialog({ type: 'edit', category: node, parentId: '' }),
    })
    items.push(
      node.active
        ? {
            label: 'Deactivate',
            icon: 'eyeOff',
            disabled: busy,
            onSelect: () => setDialog({ type: 'deactivate', category: node }),
          }
        : { label: 'Activate', icon: 'eye', disabled: busy, onSelect: () => activate(node) },
    )
    items.push({
      label: 'Delete',
      icon: 'trash',
      tone: 'danger',
      disabled: busy,
      onSelect: () => setDialog({ type: 'delete', category: node }),
    })
    return items
  }

  // No sortable columns here: sorting would break the parent → child order of the tree.
  const columns = [
    {
      key: 'name',
      header: 'Category',
      cell: ({ node, depth }) => (
        <div className="flex items-center gap-2.5" style={{ paddingLeft: `${(depth - 1) * 1.5}rem` }}>
          {depth > 1 && (
            <span className="text-slate-300" aria-hidden="true">
              └
            </span>
          )}
          <span
            aria-hidden="true"
            className={cn(
              'flex size-8 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br ring-1',
              LEVEL_TILES[depth] ?? LEVEL_TILES[3],
              !node.active && 'opacity-50 grayscale',
            )}
          >
            <Icon name={depth === 1 ? 'grid' : 'tag'} className="size-4" />
          </span>
          <div className="min-w-0">
            <p className={node.active ? 'font-medium text-slate-900' : 'font-medium text-slate-500'}>{node.name}</p>
            {node.description && <p className="max-w-md truncate text-xs text-slate-500">{node.description}</p>}
          </div>
        </div>
      ),
    },
    {
      key: 'products',
      header: 'Products',
      className: 'text-right',
      cell: ({ node }) => (
        <span className={cn('font-semibold tabular-nums', node.productCount > 0 ? 'text-slate-900' : 'text-slate-400')}>
          {node.productCount}
        </span>
      ),
    },
    {
      key: 'sort',
      header: 'Sort',
      hideOnMobile: true,
      className: 'text-right',
      cell: ({ node }) => <span className="tabular-nums">{node.sortOrder}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      cell: ({ node }) => (
        <Badge tone={node.active ? 'green' : 'gray'} dot>
          {node.active ? 'Active' : 'Inactive'}
        </Badge>
      ),
    },
  ]

  if (canManage) {
    columns.push({
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'text-right',
      cell: ({ node, depth }) => (
        <div className="flex items-center justify-end gap-2">
          {busyId === node.id && <Spinner size="sm" label={`Updating ${node.name}`} />}
          <ActionMenu label={`Actions for ${node.name}`} items={rowActions(node, depth)} />
        </div>
      ),
    })
  }

  return (
    <div className="space-y-4">
      {rows.length > 0 && <CategorySummary rows={rows} topLevel={tree.length} />}

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-sm text-slate-500">
          {rows.length} categor{rows.length === 1 ? 'y' : 'ies'}, up to {MAX_CATEGORY_DEPTH} levels. Inactive categories
          hide their sub-categories and products from customers.
        </p>
        {canManage ? (
          <Button className="shrink-0" onClick={() => setDialog({ type: 'edit', category: null, parentId: '' })}>
            <Icon name="plus" className="size-4" />
            New category
          </Button>
        ) : (
          <p className="flex shrink-0 items-center gap-2 rounded-xl bg-slate-50 px-3 py-2 text-xs text-slate-500 ring-1 ring-slate-200 ring-inset">
            <Icon name="lock" className="size-4" />
            Your admin role can view categories but not change them.
          </p>
        )}
      </div>

      <DataTable
        caption="Category tree"
        columns={columns}
        rows={rows}
        getRowKey={({ node }) => node.id}
        fetching={categories.isFetching}
        empty={
          <EmptyState
            title="No categories yet"
            description="Create the top-level categories first, e.g. Fruits & Vegetables or Dairy & Bakery."
          />
        }
      />

      <CategoryDialog
        open={dialog?.type === 'edit'}
        category={dialog?.type === 'edit' ? dialog.category : null}
        parentId={dialog?.type === 'edit' ? dialog.parentId : ''}
        tree={tree}
        onClose={close}
      />

      <ConfirmDialog
        open={dialog?.type === 'deactivate'}
        title={`Deactivate ${dialog?.type === 'deactivate' ? dialog.category.name : 'category'}?`}
        confirmLabel="Deactivate"
        tone="danger"
        pending={action.isPending}
        error={confirmError}
        onClose={close}
        onConfirm={() => dialog?.type === 'deactivate' && confirmAction('deactivate', dialog.category)}
      >
        <p>
          Customers stop seeing this category, its sub-categories and all of their products within a few seconds.
          Vendors keep their products and can be shown again by activating it.
        </p>
      </ConfirmDialog>

      <ConfirmDialog
        open={dialog?.type === 'delete'}
        title={`Delete ${dialog?.type === 'delete' ? dialog.category.name : 'category'}?`}
        confirmLabel="Delete category"
        tone="danger"
        pending={action.isPending}
        error={confirmError}
        onClose={close}
        onConfirm={() => dialog?.type === 'delete' && confirmAction('delete', dialog.category)}
      >
        <p>Only categories without sub-categories or products can be deleted. This cannot be undone.</p>
      </ConfirmDialog>
    </div>
  )
}
