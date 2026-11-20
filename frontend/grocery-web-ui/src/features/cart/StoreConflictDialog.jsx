import { ConfirmDialog } from '../../shared/components/ui'

/** @typedef {import('./hooks').StoreConflictState} StoreConflictState */

/**
 * "Your cart has items from <store>. Start a new cart?" — shown for `409 CART_STORE_CONFLICT`.
 * @param {StoreConflictState} props
 */
export function StoreConflictDialog({ open, currentStoreName, itemName, pending, onConfirm, onClose }) {
  return (
    <ConfirmDialog
      open={open}
      title="Start a new cart?"
      confirmLabel="Start new cart"
      cancelLabel="Keep current cart"
      pending={pending}
      onConfirm={onConfirm}
      onClose={onClose}
    >
      <p>
        Your cart has items from <strong className="font-semibold text-slate-900">{currentStoreName}</strong>. Start a
        new cart?
      </p>
      <p>A cart holds items from one store at a time. Starting a new cart removes those items and adds {itemName}.</p>
    </ConfirmDialog>
  )
}
