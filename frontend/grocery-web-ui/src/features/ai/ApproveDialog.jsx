/** The dialog an admin uses to approve an AI request, with an optional comment. */

import { useId, useState } from 'react'
import { errorMessage } from '../../shared/api/problemDetail'
import { Alert, Button, Dialog, Spinner, TextareaField } from '../../shared/components/ui'
import { AI_LIMITS } from './api'

/**
 * @typedef {object} ApproveFormProps
 * @property {() => void} onClose
 * @property {(comment: string) => Promise<unknown>} onSubmit Resolves when done (the parent closes the dialog); rejects to show the error here.
 */

/**
 * The optional comment (≤ 300); a new form (empty) every time the dialog opens.
 * @param {ApproveFormProps} props
 */
function ApproveForm({ onClose, onSubmit }) {
  const fieldId = useId()
  const [comment, setComment] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)

  async function submit(event) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await onSubmit(comment.trim())
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} className="space-y-4">
      <TextareaField
        id={fieldId}
        label="Comment (optional)"
        rows={3}
        maxLength={AI_LIMITS.comment}
        value={comment}
        onChange={(event) => setComment(event.target.value)}
      />
      {error && (
        <Alert tone="danger" role="alert">
          {error}
        </Alert>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={submitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={submitting}>
          {submitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Approve and run
        </Button>
      </div>
    </form>
  )
}

/**
 * @typedef {ApproveFormProps & { open: boolean, title: string, busy: boolean }} ApproveDialogProps
 * - `busy`: The approval is running: the dialog cannot be dismissed.
 */

/**
 * Approve with an optional comment.
 * @param {ApproveDialogProps} props
 */
export function ApproveDialog({ open, title, busy, onClose, onSubmit }) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      dismissible={!busy}
      size="sm"
      title="Approve this request?"
      description={`${title}. Approving runs it now, with your authority.`}
    >
      <ApproveForm onClose={onClose} onSubmit={onSubmit} />
    </Dialog>
  )
}
