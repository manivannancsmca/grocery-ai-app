import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { ApiError, errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, Spinner, TextareaField } from '../../shared/components/ui'
import { REVIEW_LIMITS } from '../reviews/api'
import { STORE_REVIEW_ERRORS } from './api'
import { useReplyToReview } from './hooks'

/** @typedef {import('./api').ModeratedReviewView} ModeratedReviewView */

const replySchema = z.object({
  text: z
    .string()
    .trim()
    .min(1, 'Write your reply.')
    .max(REVIEW_LIMITS.reply, `Use at most ${REVIEW_LIMITS.reply} characters.`),
})

/** @typedef {z.infer<typeof replySchema>} ReplyValues */

/**
 * @typedef {object} ReplyDialogProps
 * @property {ModeratedReviewView | null} review The review answered; null closes the dialog.
 * @property {() => void} onClose
 */

/**
 * Write (or edit) the store's one public reply to a review.
 * @param {ReplyDialogProps} props
 */
export function ReplyDialog({ review, onClose }) {
  return (
    <Dialog
      open={review !== null}
      onClose={onClose}
      title={review?.reply ? 'Edit your reply' : 'Reply to this review'}
      description="Your reply is public, under the review. Be polite and specific; it can be edited later."
    >
      {review && <ReplyForm key={review.id} review={review} onClose={onClose} />}
    </Dialog>
  )
}

/** @param {{ review: ModeratedReviewView, onClose: () => void }} props */
function ReplyForm({ review, onClose }) {
  const toast = useToast()
  const reply = useReplyToReview()
  const [formError, setFormError] = useState(null)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm({ resolver: zodResolver(replySchema), defaultValues: { text: review.reply?.text ?? '' } })

  const submit = handleSubmit(async ({ text }) => {
    setFormError(null)
    try {
      await reply.mutateAsync({ id: review.id, text: text.trim() })
      toast.success(review.reply ? 'Reply updated' : 'Reply published')
      onClose()
    } catch (error) {
      setFormError(
        hasErrorCode(error, STORE_REVIEW_ERRORS.notFound) || (error instanceof ApiError && error.status === 404)
          ? 'This review is no longer listed for your store (it may have been removed).'
          : errorMessage(error),
      )
    }
  })

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      <blockquote className="rounded-lg bg-slate-50 px-3 py-2 text-sm text-slate-600">
        <p className="font-medium text-slate-800">
          {review.rating}★ {review.title ?? ''}
        </p>
        {review.comment && <p className="mt-0.5 line-clamp-3">{review.comment}</p>}
      </blockquote>
      <TextareaField
        label="Your reply"
        rows={5}
        required
        maxLength={REVIEW_LIMITS.reply}
        error={errors.text?.message}
        {...register('text')}
      />
      {formError && (
        <Alert tone="danger" role="alert">
          {formError}
        </Alert>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={isSubmitting}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          {review.reply ? 'Save reply' : 'Publish reply'}
        </Button>
      </div>
    </form>
  )
}
