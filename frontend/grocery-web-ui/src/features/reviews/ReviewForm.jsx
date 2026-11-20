import { useState } from 'react'
import { Alert, Button, Spinner, TextareaField, TextField } from '../../shared/components/ui'
import { REVIEW_LIMITS } from './api'
import { StarInput } from './StarRating'

/**
 * @typedef {object} ReviewFormValues
 * @property {number} rating
 * @property {string} [title] Trimmed; undefined when empty.
 * @property {string} [comment]
 */

/**
 * @typedef {object} ReviewFormProps
 * @property {string} ratingLabel The rating group's name, e.g. "Your rating for Basmati Rice".
 * @property {{ rating: number | null, title: string | null, comment: string | null }} [initial]
 * @property {string} submitLabel
 * @property {boolean} pending
 * @property {string | null} [error] The last refusal, shown above the buttons.
 * @property {(values: ReviewFormValues) => void} onSubmit
 * @property {() => void} [onCancel]
 */

/**
 * Stars (required), an optional title (≤ 80) and comment (≤ 1000).
 * @param {ReviewFormProps} props
 */
export function ReviewForm({ ratingLabel, initial, submitLabel, pending, error, onSubmit, onCancel }) {
  const [rating, setRating] = useState(initial?.rating ?? null)
  const [title, setTitle] = useState(initial?.title ?? '')
  const [comment, setComment] = useState(initial?.comment ?? '')
  const [submitted, setSubmitted] = useState(false)

  const ratingError = submitted && rating === null ? 'Choose a rating from 1 to 5 stars.' : undefined
  const titleError =
    title.trim().length > REVIEW_LIMITS.title ? `Use at most ${REVIEW_LIMITS.title} characters.` : undefined
  const commentError =
    comment.trim().length > REVIEW_LIMITS.comment ? `Use at most ${REVIEW_LIMITS.comment} characters.` : undefined

  function submit(event) {
    event.preventDefault()
    setSubmitted(true)
    if (rating === null || titleError || commentError || pending) return
    const trimmedTitle = title.trim()
    const trimmedComment = comment.trim()
    onSubmit({
      rating,
      ...(trimmedTitle ? { title: trimmedTitle } : {}),
      ...(trimmedComment ? { comment: trimmedComment } : {}),
    })
  }

  // The optional text appears once a star is chosen (or when editing), so a first look is just the stars.
  const showText = rating !== null || Boolean(initial?.title) || Boolean(initial?.comment)

  const stars = (
    <StarInput
      value={rating}
      onChange={setRating}
      label={ratingLabel}
      hideLabel
      error={ratingError}
      disabled={pending}
    />
  )
  const actions = (
    <div className="flex flex-wrap justify-end gap-2">
      {onCancel && (
        <Button variant="secondary" onClick={onCancel} disabled={pending}>
          Cancel
        </Button>
      )}
      <Button type="submit" disabled={pending}>
        {pending && <Spinner size="sm" className="[&_svg]:text-white" />}
        {submitLabel}
      </Button>
    </div>
  )
  const failure = error && (
    <Alert tone="danger" role="alert">
      {error}
    </Alert>
  )

  // The stars keep their place in the tree (no remount, so focus stays on the chosen star); until one is chosen the
  // button shares their row.
  return (
    <form noValidate onSubmit={submit} className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        {stars}
        {!showText && actions}
      </div>
      {showText && (
        <div className="animate-fade-up space-y-3">
          <TextField
            label={
              <>
                Title <span className="font-normal text-slate-500">(optional)</span>
              </>
            }
            value={title}
            maxLength={REVIEW_LIMITS.title}
            placeholder="Sum it up in a few words"
            error={titleError}
            disabled={pending}
            onChange={(event) => setTitle(event.target.value)}
          />
          <TextareaField
            label={
              <>
                Your review <span className="font-normal text-slate-500">(optional)</span>
              </>
            }
            rows={3}
            value={comment}
            maxLength={REVIEW_LIMITS.comment}
            placeholder="What did you like or dislike?"
            hint={`${comment.trim().length}/${REVIEW_LIMITS.comment} · No links, phone numbers or email addresses, please.`}
            error={commentError}
            disabled={pending}
            onChange={(event) => setComment(event.target.value)}
          />
        </div>
      )}
      {failure}
      {showText && actions}
    </form>
  )
}
