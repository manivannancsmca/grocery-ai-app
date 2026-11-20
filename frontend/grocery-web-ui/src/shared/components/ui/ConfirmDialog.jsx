import { Alert } from './Alert'
import { Button } from './Button'
import { Dialog } from './Dialog'
import { Spinner } from './Spinner'

/** @typedef {import('react').ReactNode} ReactNode */

/**
 * @typedef {object} ConfirmDialogProps
 * @property {boolean} open
 * @property {ReactNode} title
 * @property {ReactNode} [children]
 * @property {string} confirmLabel
 * @property {string} [cancelLabel]
 * @property {'primary' | 'danger'} [tone]
 * @property {boolean} [pending]
 * @property {string | null} [error] Error from the last attempt, shown inside the dialog.
 * @property {() => void} onConfirm
 * @property {() => void} onClose
 */

/** @param {ConfirmDialogProps} props */
export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel,
  cancelLabel = 'Cancel',
  tone = 'primary',
  pending = false,
  error,
  onConfirm,
  onClose,
}) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={title}
      size="sm"
      dismissible={!pending}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={pending}>
            {cancelLabel}
          </Button>
          <Button variant={tone} onClick={onConfirm} disabled={pending}>
            {pending && <Spinner size="sm" className="[&_svg]:text-white" />}
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="space-y-3 text-sm text-slate-600">
        {children}
        {error && (
          <Alert tone="danger" role="alert">
            {error}
          </Alert>
        )}
      </div>
    </Dialog>
  )
}
