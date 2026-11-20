import { useState } from 'react'
import { Link } from 'react-router'
import { PURCHASING_ERRORS, type SupplierView } from '../../../features/purchasing/api'
import { useDeactivateSupplier, useSuppliers } from '../../../features/purchasing/hooks'
import { SupplierDialog } from '../../../features/purchasing/SupplierDialog'
import { ApiError, errorMessage, hasErrorCode } from '../../../shared/api/problemDetail'
import { useToast } from '../../../shared/components/toast/useToast'
import { Badge, Button, type Column, ConfirmDialog, DataTable, EmptyState, ErrorState, PageHeader, Spinner } from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'

/**
 * `/vendor/suppliers` (Phase 12 §4) — the vendor's suppliers: add, edit, deactivate (refused while the supplier
 * has open purchase orders), and start a purchase order for one.
 */
export function VendorSuppliersPage() {
  const toast = useToast()
  const suppliers = useSuppliers()
  const deactivate = useDeactivateSupplier()
  const [editing, setEditing] = useState<SupplierView | null | 'new'>(null)
  const [toDeactivate, setToDeactivate] = useState<SupplierView | null>(null)
  const [deactivateError, setDeactivateError] = useState<string | null>(null)

  async function confirmDeactivate() {
    if (!toDeactivate) return
    setDeactivateError(null)
    try {
      await deactivate.mutateAsync(toDeactivate.id)
      toast.success('Supplier deactivated', { description: toDeactivate.name })
      setToDeactivate(null)
    } catch (error) {
      setDeactivateError(
        hasErrorCode(error, PURCHASING_ERRORS.supplierInUse)
          ? `${toDeactivate.name} still has open purchase orders. Receive, close or cancel them first.`
          : errorMessage(error),
      )
    }
  }

  const columns: Column<SupplierView>[] = [
    {
      key: 'name',
      header: 'Supplier',
      cell: (supplier) => (
        <div className="min-w-0">
          <p className="font-medium text-slate-900">{supplier.name}</p>
          {supplier.gstin && <p className="font-mono text-xs text-slate-500">GSTIN {supplier.gstin}</p>}
        </div>
      ),
    },
    {
      key: 'contact',
      header: 'Contact',
      hideOnMobile: true,
      cell: (supplier) => (
        <div className="min-w-0 text-xs text-slate-600">
          {supplier.contactName && <p className="text-sm text-slate-900">{supplier.contactName}</p>}
          {supplier.phone && <p>{supplier.phone}</p>}
          {supplier.email && <p>{supplier.email}</p>}
          {!supplier.contactName && !supplier.phone && !supplier.email && <span className="text-slate-400">—</span>}
        </div>
      ),
    },
    {
      key: 'lead',
      header: 'Lead time',
      hideOnMobile: true,
      cell: (supplier) => (supplier.leadTimeDays !== null ? `${supplier.leadTimeDays} day${supplier.leadTimeDays === 1 ? '' : 's'}` : '—'),
    },
    {
      key: 'status',
      header: 'Status',
      cell: (supplier) => (supplier.active ? <Badge tone="green">Active</Badge> : <Badge tone="gray">Inactive</Badge>),
    },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      className: 'text-right',
      cell: (supplier) => (
        <div className="flex flex-wrap justify-end gap-2">
          {supplier.active && (
            <Link
              to={`/vendor/purchase-orders/new?supplierId=${encodeURIComponent(supplier.id)}`}
              className="inline-flex h-8 items-center rounded-lg px-3 text-sm font-medium text-emerald-700 hover:bg-emerald-50"
            >
              New order
            </Link>
          )}
          <Button size="sm" variant="secondary" aria-label={`Edit ${supplier.name}`} onClick={() => setEditing(supplier)}>
            Edit
          </Button>
          {supplier.active && (
            <Button size="sm" variant="ghost" aria-label={`Deactivate ${supplier.name}`} onClick={() => setToDeactivate(supplier)}>
              Deactivate
            </Button>
          )}
        </div>
      ),
    },
  ]

  return (
    <div className="space-y-4">
      <PageHeader
        icon="truck"
        title="Suppliers"
        description="Who you buy from. Purchase orders go to one supplier each."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <ExportButton path="/vendor/suppliers/export" label="suppliers" total={suppliers.data?.length} />
            <Button onClick={() => setEditing('new')}>Add supplier</Button>
          </div>
        }
      />
      {suppliers.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading suppliers" />
        </div>
      ) : suppliers.isError ? (
        <ErrorState
          title="Could not load your suppliers"
          message={suppliers.error.message}
          correlationId={suppliers.error instanceof ApiError ? suppliers.error.correlationId : null}
          onRetry={() => void suppliers.refetch()}
        />
      ) : (
        <DataTable
          caption="Suppliers"
          columns={columns}
          rows={suppliers.data}
          getRowKey={(supplier) => supplier.id}
          empty={
            <EmptyState
              title="No suppliers yet"
              description="Add the wholesalers and distributors you buy from, then order stock from them."
              action={<Button onClick={() => setEditing('new')}>Add supplier</Button>}
            />
          }
        />
      )}

      <SupplierDialog open={editing !== null} supplier={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />
      <ConfirmDialog
        open={toDeactivate !== null}
        title={`Deactivate ${toDeactivate?.name ?? 'supplier'}?`}
        confirmLabel="Deactivate"
        tone="danger"
        pending={deactivate.isPending}
        error={deactivateError}
        onConfirm={() => void confirmDeactivate()}
        onClose={() => {
          setToDeactivate(null)
          setDeactivateError(null)
        }}
      >
        <p>New purchase orders cannot be sent to an inactive supplier. Past orders keep it.</p>
      </ConfirmDialog>
    </div>
  )
}
