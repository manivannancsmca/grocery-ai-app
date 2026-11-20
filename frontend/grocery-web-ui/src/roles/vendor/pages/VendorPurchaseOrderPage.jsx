import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { orderTotal, PURCHASING_ERRORS } from '../../../features/purchasing/api'
import { usePurchaseOrder, usePurchaseOrderTransition, useSuppliers } from '../../../features/purchasing/hooks'
import { poActions, transitionConflictMessage } from '../../../features/purchasing/labels'
import { PoStatusBadge, PurchaseOrderLinesTable, ReceiptsList } from '../../../features/purchasing/PurchaseOrderBits'
import { ReceiveDialog } from '../../../features/purchasing/ReceiveDialog'
import { ApiError, errorMessage, hasErrorCode } from '../../../shared/api/problemDetail'
import { useToast } from '../../../shared/components/toast/useToast'
import {
  Alert,
  Button,
  Card,
  ConfirmDialog,
  DescriptionList,
  ErrorState,
  PageHeader,
  Spinner,
} from '../../../shared/components/ui'
import { formatDate, formatDateTime, formatMoney } from '../../../shared/utils/format'

/** @typedef {import('../../../features/purchasing/api').PurchaseOrderTransition} PurchaseOrderTransition */

/** @type {Record<PurchaseOrderTransition, { title: string, confirm: string, body: string, done: string, tone: 'primary' | 'danger' }>} */
const TRANSITIONS = {
  send: {
    title: 'Send this purchase order?',
    confirm: 'Send to supplier',
    body: 'It can no longer be edited afterwards. Record the goods here as they arrive.',
    done: 'Purchase order sent',
    tone: 'primary',
  },
  cancel: {
    title: 'Cancel this purchase order?',
    confirm: 'Cancel order',
    body: 'Nothing more can be received against it.',
    done: 'Purchase order cancelled',
    tone: 'danger',
  },
  close: {
    title: 'Close this purchase order short?',
    confirm: 'Close order',
    body: 'The quantities still open will not come. What was received stays in stock.',
    done: 'Purchase order closed',
    tone: 'danger',
  },
}

const linkButton =
  'inline-flex h-10 items-center rounded-lg px-4 text-sm font-medium ring-1 ring-inset ring-slate-300 bg-white text-slate-700 hover:bg-slate-50'

/**
 * `/vendor/purchase-orders/:purchaseOrderId` — one purchase order: lines (ordered, received, open), receipts, and the
 * actions its status allows: edit and send a DRAFT, receive goods (in parts), close a partly received order short,
 * cancel before anything arrived, and a printable view.
 */
export function VendorPurchaseOrderPage() {
  const { purchaseOrderId = '' } = useParams()
  const toast = useToast()
  const order = usePurchaseOrder(purchaseOrderId)
  const suppliers = useSuppliers()
  const transition = usePurchaseOrderTransition()
  const [confirming, setConfirming] = useState(null)
  const [transitionError, setTransitionError] = useState(null)
  const [conflict, setConflict] = useState(null)
  const [receiving, setReceiving] = useState(false)

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
  const actions = poActions(view.status)
  const supplier = suppliers.data?.find((candidate) => candidate.id === view.supplierId)
  const currency = view.currency ?? 'INR'

  async function runTransition() {
    if (!confirming) return
    setTransitionError(null)
    try {
      await transition.mutateAsync({ id: view.id, transition: confirming })
      toast.success(TRANSITIONS[confirming].done, { description: view.number })
      setConfirming(null)
    } catch (error) {
      if (hasErrorCode(error, PURCHASING_ERRORS.invalidTransition)) {
        setConfirming(null)
        setConflict(transitionConflictMessage(error))
        return
      }
      setTransitionError(errorMessage(error))
    }
  }

  const buttons = []
  if (actions.edit) {
    buttons.push(
      <Link key="edit" to={`/vendor/purchase-orders/${encodeURIComponent(view.id)}/edit`} className={linkButton}>
        Edit
      </Link>,
    )
  }
  if (actions.send)
    buttons.push(
      <Button key="send" onClick={() => setConfirming('send')}>
        Send to supplier
      </Button>,
    )
  if (actions.receive)
    buttons.push(
      <Button key="receive" onClick={() => setReceiving(true)}>
        Receive goods
      </Button>,
    )
  if (actions.close)
    buttons.push(
      <Button key="close" variant="secondary" onClick={() => setConfirming('close')}>
        Close short
      </Button>,
    )
  if (actions.cancel)
    buttons.push(
      <Button key="cancel" variant="ghost" onClick={() => setConfirming('cancel')}>
        Cancel order
      </Button>,
    )

  return (
    <div className="space-y-6">
      <PageHeader
        icon="clipboard"
        title={`Purchase order ${view.number}`}
        description={`To ${view.supplierName ?? supplier?.name ?? 'supplier'} · ${formatMoney(orderTotal(view), currency)}`}
        actions={
          <Link to={`/vendor/purchase-orders/${encodeURIComponent(view.id)}/print`} className={linkButton}>
            Printable view
          </Link>
        }
      />
      <p>
        <Link to="/vendor/purchase-orders" className="text-sm font-medium text-emerald-700 hover:text-emerald-800">
          ← All purchase orders
        </Link>
      </p>

      {conflict && (
        <Alert tone="warning" role="alert">
          {conflict}
        </Alert>
      )}

      <Card icon="clipboard" title="Order" actions={<PoStatusBadge status={view.status} />}>
        <DescriptionList
          items={[
            { label: 'Supplier', value: view.supplierName ?? supplier?.name },
            { label: 'Expected on', value: view.expectedAt ? formatDate(view.expectedAt) : null },
            { label: 'Created', value: formatDateTime(view.createdAt) },
            { label: 'Sent', value: view.sentAt ? formatDateTime(view.sentAt) : null },
            { label: 'Notes', value: view.notes, wide: true },
          ]}
        />
        {buttons.length > 0 && (
          <div
            role="group"
            aria-label="Order actions"
            className="mt-5 flex flex-wrap gap-2 border-t border-slate-100 pt-4"
          >
            {buttons}
          </div>
        )}
      </Card>

      <section aria-labelledby="po-lines" className="space-y-3">
        <h2 id="po-lines" className="text-lg font-semibold text-slate-900">
          Products
        </h2>
        <PurchaseOrderLinesTable order={view} />
      </section>

      {view.status !== 'DRAFT' && (
        <section aria-labelledby="po-receipts" className="space-y-3">
          <h2 id="po-receipts" className="text-lg font-semibold text-slate-900">
            Receipts
          </h2>
          <ReceiptsList order={view} />
        </section>
      )}

      {confirming && (
        <ConfirmDialog
          open
          title={TRANSITIONS[confirming].title}
          confirmLabel={TRANSITIONS[confirming].confirm}
          cancelLabel="Back"
          tone={TRANSITIONS[confirming].tone}
          pending={transition.isPending}
          error={transitionError}
          onConfirm={() => void runTransition()}
          onClose={() => {
            setConfirming(null)
            setTransitionError(null)
          }}
        >
          <p>
            {view.number}: {TRANSITIONS[confirming].body}
          </p>
        </ConfirmDialog>
      )}
      <ReceiveDialog open={receiving} order={view} onClose={() => setReceiving(false)} />
    </div>
  )
}
