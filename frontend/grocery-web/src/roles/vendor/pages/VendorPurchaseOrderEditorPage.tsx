import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { usePurchaseOrder, useSuppliers } from '../../../features/purchasing/hooks'
import { PurchaseOrderForm } from '../../../features/purchasing/PurchaseOrderForm'
import { ApiError } from '../../../shared/api/problemDetail'
import { useToast } from '../../../shared/components/toast/useToast'
import { Alert, EmptyState, ErrorState, PageHeader, Spinner } from '../../../shared/components/ui'

const detailPath = (id: string) => `/vendor/purchase-orders/${encodeURIComponent(id)}`

/** `/vendor/purchase-orders/new` (`?supplierId=` pre-selects one) and `/vendor/purchase-orders/:id/edit` (DRAFT only). */
export function VendorPurchaseOrderEditorPage() {
  const { purchaseOrderId } = useParams()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const suppliers = useSuppliers()
  const order = usePurchaseOrder(purchaseOrderId)
  const editing = Boolean(purchaseOrderId)

  const title = editing ? `Edit ${order.data?.number ?? 'purchase order'}` : 'New purchase order'
  const header = <PageHeader icon="clipboard" title={title} description="A draft can be changed until it is sent to the supplier." />

  if (suppliers.isPending || (editing && order.isPending)) {
    return (
      <div>
        {header}
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading" />
        </div>
      </div>
    )
  }
  const failed = suppliers.error ?? (editing ? order.error : null)
  if (failed) {
    return (
      <div>
        {header}
        <ErrorState
          title="Could not load the purchase order"
          message={failed.message}
          correlationId={failed instanceof ApiError ? failed.correlationId : null}
          onRetry={() => {
            void suppliers.refetch()
            if (editing) void order.refetch()
          }}
        />
      </div>
    )
  }
  if (!suppliers.data?.some((supplier) => supplier.active) && !editing) {
    return (
      <div>
        {header}
        <EmptyState
          title="Add a supplier first"
          description="Every purchase order goes to one of your suppliers."
          action={
            <Link to="/vendor/suppliers" className="text-sm font-semibold text-emerald-700 hover:text-emerald-800">
              Manage suppliers →
            </Link>
          }
        />
      </div>
    )
  }
  if (editing && order.data && order.data.status !== 'DRAFT') {
    return (
      <div>
        {header}
        <Alert tone="info" title="Only drafts can be edited">
          {order.data.number} has been sent already.{' '}
          <Link to={detailPath(order.data.id)} className="font-semibold underline underline-offset-2">
            Back to the order
          </Link>
        </Alert>
      </div>
    )
  }

  return (
    <div>
      {header}
      <PurchaseOrderForm
        key={order.data?.id ?? 'new'}
        order={editing ? (order.data ?? null) : null}
        suppliers={suppliers.data ?? []}
        initialSupplierId={searchParams.get('supplierId') ?? undefined}
        onSaved={(saved) => {
          toast.success(editing ? 'Draft saved' : 'Draft created', { description: `${saved.number} — send it to the supplier when it is ready.` })
          void navigate(detailPath(saved.id))
        }}
        onCancel={() => void navigate(editing && purchaseOrderId ? detailPath(purchaseOrderId) : '/vendor/purchase-orders')}
      />
    </div>
  )
}
