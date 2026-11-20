import { Link, useParams } from 'react-router'
import { usePurchaseOrder, useSuppliers } from '../../../features/purchasing/hooks'
import { poStatusLabel } from '../../../features/purchasing/labels'
import { PurchaseOrderLinesTable } from '../../../features/purchasing/PurchaseOrderBits'
import { useVendorProfile } from '../../../features/vendor/hooks'
import { ApiError } from '../../../shared/api/problemDetail'
import { Button, ErrorState, Spinner } from '../../../shared/components/ui'
import { formatAddress, formatDate } from '../../../shared/utils/format'

/**
 * `/vendor/purchase-orders/:purchaseOrderId/print` — the purchase order as a document for the supplier: buyer and
 * supplier blocks, the lines and the total. The app's header, menu and assistant are hidden when printing.
 */
export function VendorPurchaseOrderPrintPage() {
  const { purchaseOrderId = '' } = useParams()
  const order = usePurchaseOrder(purchaseOrderId)
  const suppliers = useSuppliers()
  const vendor = useVendorProfile()

  if (order.isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner size="lg" label="Loading the purchase order" />
      </div>
    )
  }
  if (order.isError) {
    return (
      <ErrorState
        title="Could not load the purchase order"
        message={order.error.message}
        correlationId={order.error instanceof ApiError ? order.error.correlationId : null}
        onRetry={() => void order.refetch()}
      />
    )
  }

  const view = order.data
  const supplier = suppliers.data?.find((candidate) => candidate.id === view.supplierId)
  const buyer = vendor.data

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3 print:hidden">
        <Link
          to={`/vendor/purchase-orders/${encodeURIComponent(view.id)}`}
          className="text-sm font-medium text-emerald-700 hover:text-emerald-800"
        >
          ← Back to the order
        </Link>
        <Button onClick={() => window.print()}>Print</Button>
      </div>

      <article
        aria-label={`Purchase order ${view.number}`}
        className="space-y-6 rounded-xl border border-slate-200 bg-white p-8 shadow-sm print:border-0 print:p-0 print:shadow-none"
      >
        <header className="flex flex-wrap items-start justify-between gap-4 border-b border-slate-200 pb-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-slate-900">Purchase order</h1>
            <p className="mt-1 font-mono text-lg text-slate-700">{view.number}</p>
          </div>
          <dl className="text-right text-sm">
            <div>
              <dt className="inline text-slate-500">Date: </dt>
              <dd className="inline text-slate-900">{formatDate(view.sentAt ?? view.createdAt)}</dd>
            </div>
            {view.expectedAt && (
              <div>
                <dt className="inline text-slate-500">Deliver by: </dt>
                <dd className="inline text-slate-900">{formatDate(view.expectedAt)}</dd>
              </div>
            )}
            <div>
              <dt className="inline text-slate-500">Status: </dt>
              <dd className="inline text-slate-900">{poStatusLabel(view.status)}</dd>
            </div>
          </dl>
        </header>

        <div className="grid gap-6 sm:grid-cols-2">
          <section aria-label="Buyer">
            <h2 className="text-xs font-semibold tracking-wide text-slate-500 uppercase">Buyer</h2>
            <p className="mt-1 font-semibold text-slate-900">{buyer?.businessName ?? 'Your store'}</p>
            {buyer?.address && <p className="text-sm text-slate-700">{formatAddress(buyer.address)}</p>}
            {buyer?.gstin && <p className="text-sm text-slate-700">GSTIN {buyer.gstin}</p>}
          </section>
          <section aria-label="Supplier">
            <h2 className="text-xs font-semibold tracking-wide text-slate-500 uppercase">Supplier</h2>
            <p className="mt-1 font-semibold text-slate-900">{view.supplierName ?? supplier?.name ?? '—'}</p>
            {supplier?.contactName && <p className="text-sm text-slate-700">Attn. {supplier.contactName}</p>}
            {supplier?.address && <p className="text-sm whitespace-pre-line text-slate-700">{supplier.address}</p>}
            {supplier?.phone && <p className="text-sm text-slate-700">{supplier.phone}</p>}
            {supplier?.email && <p className="text-sm text-slate-700">{supplier.email}</p>}
            {supplier?.gstin && <p className="text-sm text-slate-700">GSTIN {supplier.gstin}</p>}
          </section>
        </div>

        <PurchaseOrderLinesTable order={view} caption={`Lines of ${view.number}`} />

        {view.notes && (
          <section aria-label="Notes">
            <h2 className="text-xs font-semibold tracking-wide text-slate-500 uppercase">Notes</h2>
            <p className="mt-1 text-sm whitespace-pre-line text-slate-700">{view.notes}</p>
          </section>
        )}

        <footer className="grid gap-8 pt-10 text-sm text-slate-500 sm:grid-cols-2">
          <p className="border-t border-slate-300 pt-2">Authorised signature</p>
          <p className="border-t border-slate-300 pt-2">Received by (supplier)</p>
        </footer>
      </article>
    </div>
  )
}
