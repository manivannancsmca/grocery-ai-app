import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { AddressCard } from '../../../features/customer/AddressCard'
import { AddressForm } from '../../../features/customer/AddressForm'
import { addressToFormValues, emptyAddressValues } from '../../../features/customer/addressSchema'
import { MAX_ADDRESSES } from '../../../features/customer/api'
import {
  useAddresses,
  useCreateAddress,
  useDeleteAddress,
  useSetDefaultAddress,
  useUpdateAddress,
} from '../../../features/customer/hooks'
import { ApiError, errorMessage, hasErrorCode } from '../../../shared/api/problemDetail'
import { safeRedirectPath } from '../../../shared/auth/redirect'
import { useAuth } from '../../../shared/auth/useAuth'
import { useToast } from '../../../shared/components/toast/useToast'
import {
  Alert,
  Button,
  ConfirmDialog,
  Dialog,
  EmptyState,
  ErrorState,
  PageHeader,
  Spinner,
} from '../../../shared/components/ui'
import { Icon } from '../../../shared/icons'
import { DeliveryScooterIllustration, MapPinIllustration } from '../../../shared/illustrations'

/** @typedef {import('../../../features/customer/api').Address} Address */

/** @typedef {{ mode: 'create' } | { mode: 'edit', address: Address }} Editor */

/**
 * `?returnTo=/checkout` (set by the checkout page) adds a link back. Only in-app paths are accepted.
 * @returns {{ to: string, label: string } | null}
 */
function useReturnLink() {
  const [searchParams] = useSearchParams()
  const to = safeRedirectPath(searchParams.get('returnTo'))
  if (!to) return null
  return { to, label: to === '/checkout' ? 'Back to checkout' : 'Go back' }
}

export function AddressesPage() {
  const { user } = useAuth()
  const returnLink = useReturnLink()
  const toast = useToast()
  const addresses = useAddresses()
  const createAddress = useCreateAddress()
  const updateAddress = useUpdateAddress()
  const deleteAddress = useDeleteAddress()
  const setDefault = useSetDefaultAddress()
  const [editor, setEditor] = useState(null)
  const [toDelete, setToDelete] = useState(null)
  const [deleteError, setDeleteError] = useState(null)

  const list = addresses.data ?? []
  const atLimit = list.length >= MAX_ADDRESSES
  const busyId = setDefault.isPending ? setDefault.variables : null

  function makeDefault(address) {
    setDefault.mutate(address.id, {
      onSuccess: () => toast.success(`${address.label} is now your default address.`),
      onError: (error) => toast.error(errorMessage(error)),
    })
  }

  return (
    <div className="space-y-6">
      {returnLink && (
        <Link
          to={returnLink.to}
          className="inline-flex items-center gap-1.5 rounded-lg text-sm font-semibold text-emerald-700 hover:text-emerald-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
        >
          <Icon name="arrowLeft" className="size-4" />
          {returnLink.label}
        </Link>
      )}
      <PageHeader
        eyebrow="Your account"
        title="Delivery addresses"
        description={`Save up to ${MAX_ADDRESSES} addresses. Your default address is used at checkout.`}
        illustration={<MapPinIllustration />}
        actions={
          <Button onClick={() => setEditor({ mode: 'create' })} disabled={atLimit || addresses.isPending}>
            <Icon name="plus" className="size-4" />
            Add address
          </Button>
        }
      />

      {atLimit && (
        <Alert tone="info" role="status">
          You have saved the maximum of {MAX_ADDRESSES} addresses. Delete one to add another.
        </Alert>
      )}

      {addresses.isPending ? (
        <div className="flex min-h-[30vh] flex-col items-center justify-center gap-3 text-sm text-slate-500">
          <Spinner size="lg" label="Loading addresses" />
          {hasErrorCode(addresses.failureReason, 'CUSTOMER_PROFILE_NOT_FOUND') && <p>Setting up your account…</p>}
        </div>
      ) : addresses.isError ? (
        <ErrorState
          title="Could not load your addresses"
          message={addresses.error.message}
          correlationId={addresses.error instanceof ApiError ? addresses.error.correlationId : null}
          onRetry={() => void addresses.refetch()}
        />
      ) : list.length === 0 ? (
        <EmptyState
          size="lg"
          illustration={<DeliveryScooterIllustration className="animate-float" />}
          title="No saved addresses yet"
          description="Add where you want your groceries and meals delivered."
          action={
            <Button onClick={() => setEditor({ mode: 'create' })}>
              <Icon name="plus" className="size-4" />
              Add your first address
            </Button>
          }
        />
      ) : (
        <div className="animate-fade-up grid gap-4 sm:grid-cols-2">
          {list.map((address) => (
            <AddressCard
              key={address.id}
              address={address}
              busy={busyId === address.id}
              onEdit={() => setEditor({ mode: 'edit', address })}
              onDelete={() => {
                setDeleteError(null)
                setToDelete(address)
              }}
              onMakeDefault={() => makeDefault(address)}
            />
          ))}
          {!atLimit && (
            <button
              type="button"
              onClick={() => setEditor({ mode: 'create' })}
              className="group flex min-h-48 flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-slate-300 bg-white/60 p-5 text-sm font-semibold text-slate-600 transition hover:border-emerald-400 hover:bg-emerald-50/60 hover:text-emerald-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
            >
              <span
                aria-hidden="true"
                className="flex size-11 items-center justify-center rounded-full bg-slate-100 text-slate-500 transition-colors group-hover:bg-emerald-600 group-hover:text-white"
              >
                <Icon name="plus" className="size-5" />
              </span>
              Add a new address
              <span className="text-xs font-normal text-slate-500">Work, family, a friend’s place…</span>
            </button>
          )}
        </div>
      )}

      <Dialog
        open={editor !== null}
        onClose={() => setEditor(null)}
        title={editor?.mode === 'edit' ? `Edit ${editor.address.label}` : 'Add a delivery address'}
        size="lg"
      >
        {editor && (
          <AddressForm
            defaultValues={
              editor.mode === 'edit'
                ? addressToFormValues(editor.address)
                : emptyAddressValues({ name: user?.fullName, phone: user?.phone })
            }
            forceDefault={editor.mode === 'create' && list.length === 0}
            submitLabel={editor.mode === 'edit' ? 'Save address' : 'Add address'}
            onCancel={() => setEditor(null)}
            onSubmit={async (request) => {
              if (editor.mode === 'edit') {
                await updateAddress.mutateAsync({ id: editor.address.id, body: request })
                toast.success('Address updated.')
              } else {
                await createAddress.mutateAsync(request)
                toast.success('Address added.')
              }
              setEditor(null)
            }}
          />
        )}
      </Dialog>

      <ConfirmDialog
        open={toDelete !== null}
        title={`Delete ${toDelete?.label ?? 'address'}?`}
        confirmLabel="Delete address"
        tone="danger"
        pending={deleteAddress.isPending}
        error={deleteError}
        onClose={() => setToDelete(null)}
        onConfirm={() => {
          if (!toDelete) return
          deleteAddress.mutate(toDelete.id, {
            onSuccess: () => {
              toast.success('Address deleted.')
              setToDelete(null)
            },
            onError: (error) => setDeleteError(errorMessage(error)),
          })
        }}
      >
        <p>
          This removes the address permanently.
          {toDelete?.isDefault && list.length > 1 && ' Your most recently added address becomes the new default.'}
        </p>
      </ConfirmDialog>
    </div>
  )
}
