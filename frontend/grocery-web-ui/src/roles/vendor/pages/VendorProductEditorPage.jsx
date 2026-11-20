import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useCategoryTree } from '../../../features/catalog/hooks'
import { PRODUCT_NOT_FOUND, VENDOR_NOT_ACTIVE } from '../../../features/vendor-products/api'
import { useVendorProduct } from '../../../features/vendor-products/hooks'
import { ProductForm } from '../../../features/vendor-products/ProductForm'
import {
  ProductStatusActions,
  ProductStatusBadge,
  VendorNotActiveNotice,
} from '../../../features/vendor-products/ProductStatusActions'
import { ApiError, hasErrorCode } from '../../../shared/api/problemDetail'
import { useToast } from '../../../shared/components/toast/useToast'
import { EmptyState, ErrorState, PageHeader, Spinner } from '../../../shared/components/ui'
import { formatDateTime } from '../../../shared/utils/format'

function BackLink() {
  return (
    <Link to="/vendor/products" className="text-sm font-semibold text-emerald-700 hover:text-emerald-800">
      ← All products
    </Link>
  )
}

/** @param {{ label: string }} props */
function Loading({ label }) {
  return (
    <div className="flex justify-center py-16">
      <Spinner size="lg" label={label} />
    </div>
  )
}

/** `/vendor/products/new` and `/vendor/products/:productId` — create or edit one product. */
export function VendorProductEditorPage() {
  const { productId } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const product = useVendorProduct(productId)
  const categories = useCategoryTree()
  const isNew = productId === undefined
  // Bumped after each save so the form restarts from the saved values (status changes keep unsaved edits).
  const [formVersion, setFormVersion] = useState(0)

  const header = (
    <PageHeader
      icon="bag"
      title={isNew ? 'Add product' : (product.data?.name ?? 'Edit product')}
      description={
        isNew
          ? 'New products are saved as drafts. Publish when everything looks right.'
          : product.data
            ? `Last updated ${formatDateTime(product.data.updatedAt)}`
            : undefined
      }
      actions={<BackLink />}
    />
  )

  if (!isNew && product.isPending) {
    return (
      <div className="w-full">
        {header}
        <Loading label="Loading product" />
      </div>
    )
  }

  if (!isNew && product.isError) {
    return (
      <div className="w-full">
        {header}
        {hasErrorCode(product.error, VENDOR_NOT_ACTIVE) ? (
          <VendorNotActiveNotice />
        ) : hasErrorCode(product.error, PRODUCT_NOT_FOUND) ? (
          <EmptyState
            title="Product not found"
            description="It does not exist or belongs to another vendor."
            action={<BackLink />}
          />
        ) : (
          <ErrorState
            title="Could not load the product"
            message={product.error.message}
            correlationId={product.error instanceof ApiError ? product.error.correlationId : null}
            onRetry={() => void product.refetch()}
          />
        )}
      </div>
    )
  }

  const current = product.data

  return (
    <div className="space-y-5">
      {header}

      {current && (
        <div className="flex flex-col gap-3 rounded-2xl border border-slate-200/70 bg-white px-5 py-3 shadow-sm sm:flex-row sm:items-center sm:justify-between">
          <p className="flex items-center gap-2 text-sm text-slate-600">
            Status <ProductStatusBadge status={current.status} />
            {current.publishedAt && current.status === 'PUBLISHED' && (
              <span className="text-xs text-slate-400">since {formatDateTime(current.publishedAt)}</span>
            )}
          </p>
          <ProductStatusActions
            product={current}
            variant="header"
            onArchived={() => void navigate('/vendor/products')}
          />
        </div>
      )}

      {categories.isPending ? (
        <Loading label="Loading categories" />
      ) : (
        <ProductForm
          key={`${current?.id ?? 'new'}-${formVersion}`}
          product={current}
          categories={categories.data ?? []}
          categoriesError={categories.isError ? categories.error.message : null}
          categoriesRefreshing={categories.isFetching}
          onRefreshCategories={() => void categories.refetch()}
          onSaved={(saved, created) => {
            if (created) {
              toast.success(`${saved.name} was created as a draft. Publish it when you are ready.`)
              void navigate(`/vendor/products/${encodeURIComponent(saved.id)}`, { replace: true })
            } else {
              setFormVersion((version) => version + 1)
              toast.success('Changes saved.')
            }
          }}
        />
      )}
    </div>
  )
}
