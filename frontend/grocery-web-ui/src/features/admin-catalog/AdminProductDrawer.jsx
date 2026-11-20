import { Link } from 'react-router'
import { ApiError } from '../../shared/api/problemDetail'
import { FoodTypeMark } from '../../shared/components/FoodTypeMark'
import { DescriptionList, Dialog, ErrorState, Spinner } from '../../shared/components/ui'
import { FOOD_TYPE_LABELS } from '../../shared/utils/foodType'
import { formatDateTime } from '../../shared/utils/format'
import { categoryLabel } from '../vendor-products/api'
import { ProductImage } from '../catalog/ProductImage'
import { ProductStatusBadge } from '../vendor-products/ProductStatusActions'
import { useAdminProduct } from './hooks'

/** @typedef {import('./api').AdminProductView} AdminProductView */

/**
 * @typedef {object} AdminProductDrawerProps
 * @property {string} id
 * @property {AdminProductView} [initial] The clicked row, shown while the full record loads.
 * @property {() => void} onClose
 */

/**
 * Read-only product details for admins.
 * @param {AdminProductDrawerProps} props
 */
export function AdminProductDrawer({ id, initial, onClose }) {
  const detail = useAdminProduct(id)
  const product = detail.data ?? initial

  return (
    <Dialog
      open
      variant="drawer"
      icon="basket"
      onClose={onClose}
      title={product?.name ?? 'Loading product…'}
      description={product ? <ProductStatusBadge status={product.status} /> : undefined}
    >
      {!product && detail.isPending ? (
        <div className="flex justify-center py-10">
          <Spinner size="lg" label="Loading product" />
        </div>
      ) : !product && detail.isError ? (
        <ErrorState
          message={detail.error.message}
          correlationId={detail.error instanceof ApiError ? detail.error.correlationId : null}
          onRetry={() => void detail.refetch()}
        />
      ) : product ? (
        <div className="space-y-6">
          {product.imageUrls.length > 0 && (
            <ul className="flex gap-2 overflow-x-auto" aria-label="Images">
              {product.imageUrls.map((url, index) => (
                <li key={`${url}-${index}`} className="shrink-0">
                  <ProductImage
                    src={url}
                    alt={`${product.name}, image ${index + 1}`}
                    className="size-24 rounded-lg border border-slate-200"
                  />
                </li>
              ))}
            </ul>
          )}
          <DescriptionList
            items={[
              { label: 'Vendor', value: product.vendorName },
              {
                label: 'Category',
                value: (
                  <>
                    {categoryLabel(product.category)}
                    {product.category && !product.category.active && (
                      <span className="ml-1 text-xs text-amber-700">(inactive)</span>
                    )}
                  </>
                ),
              },
              { label: 'Brand', value: product.brand?.name },
              { label: 'Unit', value: product.unitLabel },
              { label: 'SKU', value: product.sku && <span className="font-mono">{product.sku}</span> },
              { label: 'Barcode', value: product.barcode && <span className="font-mono">{product.barcode}</span> },
              {
                label: 'Food type',
                value: product.foodType && (
                  <span className="inline-flex items-center gap-1.5">
                    <FoodTypeMark foodType={product.foodType} />
                    {FOOD_TYPE_LABELS[product.foodType]}
                  </span>
                ),
              },
              { label: 'Tags', value: product.tags.length > 0 ? product.tags.join(', ') : null },
              { label: 'Description', value: product.description, wide: true },
              { label: 'Published', value: product.publishedAt ? formatDateTime(product.publishedAt) : null },
              { label: 'Created', value: formatDateTime(product.createdAt) },
              { label: 'Last updated', value: formatDateTime(product.updatedAt) },
              { label: 'Product ID', value: <span className="font-mono text-xs">{product.id}</span>, wide: true },
            ]}
          />
          {product.attributes.length > 0 && (
            <section>
              <h3 className="mb-2 text-sm font-semibold text-slate-900">Attributes</h3>
              <table className="w-full text-sm">
                <tbody className="divide-y divide-slate-100">
                  {product.attributes.map((attribute) => (
                    <tr key={`${attribute.name}-${attribute.value}`}>
                      <th scope="row" className="w-2/5 py-1.5 pr-4 text-left font-medium text-slate-500">
                        {attribute.name}
                      </th>
                      <td className="py-1.5 text-slate-800">{attribute.value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}
          {product.status === 'PUBLISHED' && (
            <Link
              to={`/products/${encodeURIComponent(product.id)}`}
              className="inline-block text-sm font-semibold text-emerald-700 hover:text-emerald-800"
            >
              Open the public product page →
            </Link>
          )}
        </div>
      ) : null}
    </Dialog>
  )
}
