import { zodResolver } from '@hookform/resolvers/zod'
import { type ReactNode, useId, useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { errorMessage } from '../api/problemDetail'
import { Alert, Button, Dialog, Spinner, TextareaField } from './ui'

const DEFAULT_MAX_REASON = 500
const DEFAULT_REQUIRED_MESSAGE = 'Enter a reason. It is shown to the person affected.'

function reasonSchema(max: number, requiredMessage: string = DEFAULT_REQUIRED_MESSAGE) {
  return z.object({
    reason: z.string().trim().min(1, requiredMessage).max(max, `Use at most ${max} characters.`),
  })
}

type ReasonValues = z.infer<ReturnType<typeof reasonSchema>>

interface ReasonDialogProps {
  open: boolean
  title: ReactNode
  description?: ReactNode
  confirmLabel: string
  /** Label of the button that closes the dialog without acting (default "Cancel"). */
  cancelLabel?: string
  tone?: 'primary' | 'danger'
  /** Longest reason the endpoint accepts (default 500). */
  maxLength?: number
  /** Error for an empty reason (default: "Enter a reason. It is shown to the person affected."). */
  requiredMessage?: string
  /** Resolves when the action succeeded (the parent then closes the dialog); rejects to show the error. */
  onSubmit: (reason: string) => Promise<unknown>
  onClose: () => void
}

/** Dialog that requires a reason (reject, suspend). */
export function ReasonDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel,
  tone = 'danger',
  maxLength = DEFAULT_MAX_REASON,
  requiredMessage,
  onSubmit,
  onClose,
}: ReasonDialogProps) {
  return (
    <Dialog open={open} onClose={onClose} title={title} description={description} size="sm">
      <ReasonForm
        confirmLabel={confirmLabel}
        cancelLabel={cancelLabel}
        tone={tone}
        maxLength={maxLength}
        requiredMessage={requiredMessage}
        onSubmit={onSubmit}
        onClose={onClose}
      />
    </Dialog>
  )
}

function ReasonForm({
  confirmLabel,
  cancelLabel = 'Cancel',
  tone,
  maxLength = DEFAULT_MAX_REASON,
  requiredMessage,
  onSubmit,
  onClose,
}: Omit<ReasonDialogProps, 'open' | 'title' | 'description'>) {
  const fieldId = useId()
  const [schema] = useState(() => reasonSchema(maxLength, requiredMessage))
  const [submitError, setSubmitError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ReasonValues>({ resolver: zodResolver(schema), defaultValues: { reason: '' } })

  const submit = handleSubmit(async ({ reason }) => {
    setSubmitError(null)
    try {
      await onSubmit(reason.trim())
    } catch (error) {
      setSubmitError(errorMessage(error))
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      <TextareaField
        id={fieldId}
        label="Reason"
        rows={4}
        required
        maxLength={maxLength}
        error={errors.reason?.message}
        {...register('reason')}
      />
      {submitError && (
        <Alert tone="danger" role="alert">
          {submitError}
        </Alert>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          {cancelLabel}
        </Button>
        <Button type="submit" variant={tone} disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {confirmLabel}
        </Button>
      </div>
    </form>
  )
}
