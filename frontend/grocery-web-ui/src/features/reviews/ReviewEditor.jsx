import { useId, useState } from 'react'
import { useToast } from '../../shared/components/toast/useToast'
import { Badge, Button, ConfirmDialog } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDate, formatDateTime } from '../../shared/utils/format'
import { useCreateReview, useDeleteReview, useUpdateReview } from './hooks'
import {
  canEditReview,
  editableUntil,
  RATING_WORDS,
  reviewErrorMessage,
  reviewStatusHint,
  reviewStatusLabel,
  reviewStatusTone,
  targetDisplayName,
  targetTypeLabel,
} from './labels'
import { ReviewForm } from './ReviewForm'
import { Stars } from './StarRating'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').ReviewView} ReviewView */

/**
 * The store's public reply under a review.
 * @param {{ reply: { text: string, repliedAt: string }, storeName?: string | null }} props
 */
export function ReviewReplyBlock({ reply, storeName }) {
  return (
    <div className="mt-3 flex gap-2.5 rounded-xl bg-emerald-50/70 px-3.5 py-3 text-sm ring-1 ring-emerald-100">
      <Icon name="chat" className="mt-0.5 size-4" />
      <div className="min-w-0">
        <p className="text-xs font-semibold text-emerald-900">
          Reply from {storeName || 'the store'}
          <span className="font-normal text-emerald-800/80"> · {formatDate(reply.repliedAt)}</span>
        </p>
        <p className="mt-0.5 whitespace-pre-line break-words text-slate-700">{reply.text}</p>
      </div>
    </div>
  )
}

/**
 * The picture next to what is being reviewed.
 * @type {Record<string, IconName>}
 */
const TARGET_ICONS = {
  PRODUCT: 'bag',
  MENU_ITEM: 'cutlery',
  VENDOR: 'store',
  RESTAURANT: 'store',
  DELIVERY_PARTNER: 'scooter',
}

/**
 * @param {ReviewView} review
 * @param {boolean} edited
 * @returns {string}
 */
function successMessage(review, edited) {
  if (review.status === 'FLAGGED') return 'Thanks! Your review will appear once our team has checked it.'
  return edited ? 'Your review was updated.' : 'Thanks for your review!'
}

/**
 * @typedef {object} ReviewEditorProps
 * @property {string} orderId
 * @property {string} targetType
 * @property {string} targetId
 * @property {string | null | undefined} targetName
 * @property {ReviewView | null} review The customer's review of this target, or null when there is none yet.
 * @property {number} now Reference time for the 7-day edit window.
 * @property {ReactNode} [context] Extra line under the target's name (e.g. "Order GA-… · delivered …").
 * @property {'h2' | 'h3'} [headingLevel] Heading level of the target's name.
 * @property {boolean} [allowCreate] False on "My reviews": a deleted review is not offered to be written again there.
 */

/**
 * One reviewable target: the stars-and-text form when it has no review yet; otherwise the review with its
 * status (under review, not published), the store's reply, "Edit" (7 days) and "Delete".
 * @param {ReviewEditorProps} props
 */
export function ReviewEditor({
  orderId,
  targetType,
  targetId,
  targetName,
  review,
  now,
  context,
  headingLevel = 'h3',
  allowCreate = true,
}) {
  const headingId = useId()
  const toast = useToast()
  const create = useCreateReview()
  const update = useUpdateReview()
  const remove = useDeleteReview()
  // The answer of the last write, shown until the refetched list catches up (undefined = use `review`).
  const [saved, setSaved] = useState(undefined)
  const [editing, setEditing] = useState(false)
  const [formError, setFormError] = useState(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleteError, setDeleteError] = useState(null)

  const current = saved !== undefined ? saved : review
  const name = targetDisplayName(targetType, targetName)
  const Heading = headingLevel
  const ratingLabel = `Your rating for ${name}`

  function submitNew(values) {
    setFormError(null)
    create.mutate(
      { orderId, targetType, targetId, ...values },
      {
        onSuccess: (created) => {
          setSaved(created)
          toast.success(successMessage(created, false))
        },
        onError: (error) => setFormError(reviewErrorMessage(error)),
      },
    )
  }

  function submitEdit(existing, values) {
    setFormError(null)
    update.mutate(
      { id: existing.id, body: values },
      {
        onSuccess: (updated) => {
          setSaved(updated)
          setEditing(false)
          toast.success(successMessage(updated, true))
        },
        onError: (error) => setFormError(reviewErrorMessage(error)),
      },
    )
  }

  function confirmRemove(existing) {
    setDeleteError(null)
    remove.mutate(existing, {
      onSuccess: () => {
        setSaved(null)
        setConfirmDelete(false)
        setEditing(false)
        toast.success('Your review was deleted.')
      },
      onError: (error) => setDeleteError(reviewErrorMessage(error)),
    })
  }

  let body
  if (!current && !allowCreate) {
    body = <p className="text-sm text-slate-500">Your review was deleted.</p>
  } else if (!current) {
    body = (
      <ReviewForm
        ratingLabel={ratingLabel}
        submitLabel="Submit review"
        pending={create.isPending}
        error={formError}
        onSubmit={submitNew}
      />
    )
  } else if (editing) {
    body = (
      <ReviewForm
        ratingLabel={ratingLabel}
        initial={current}
        submitLabel="Save changes"
        pending={update.isPending}
        error={formError}
        onSubmit={(values) => submitEdit(current, values)}
        onCancel={() => {
          setFormError(null)
          setEditing(false)
        }}
      />
    )
  } else {
    const editable = canEditReview(current, now)
    const hint = reviewStatusHint(current.status)
    body = (
      <div>
        <div className="flex flex-wrap items-center gap-2">
          <Stars value={current.rating} size="md" />
          <span aria-hidden="true" className="text-sm font-semibold text-slate-700">
            {RATING_WORDS[current.rating] ?? ''}
          </span>
          {current.edited && <span className="text-xs text-slate-500">· Edited</span>}
        </div>
        {current.title && <p className="mt-2.5 text-sm font-semibold break-words text-slate-900">{current.title}</p>}
        {current.comment && (
          <p className="mt-1 text-sm leading-relaxed whitespace-pre-line break-words text-slate-700">
            {current.comment}
          </p>
        )}
        <p className="mt-2 text-xs text-slate-500">Written {formatDateTime(current.createdAt)}</p>
        {hint && (
          <p className="mt-3 flex items-start gap-2 rounded-xl bg-slate-50 px-3.5 py-2.5 text-sm text-slate-600 ring-1 ring-slate-100">
            <Icon name="info" className="mt-0.5 size-4" />
            <span>
              {hint}
              {current.moderationReason && (
                <span className="block text-slate-500">Reason: {current.moderationReason}</span>
              )}
            </span>
          </p>
        )}
        {current.reply && <ReviewReplyBlock reply={current.reply} />}
        <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
          {editable && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => setEditing(true)}
              aria-label={`Edit your review of ${name}`}
            >
              <Icon name="pencil" className="size-4" />
              Edit
            </Button>
          )}
          <Button
            variant="ghost"
            size="sm"
            className="text-red-600 hover:bg-red-50 hover:text-red-700"
            onClick={() => {
              setDeleteError(null)
              setConfirmDelete(true)
            }}
            aria-label={`Delete your review of ${name}`}
          >
            <Icon name="trash" className="size-4" />
            Delete
          </Button>
          {current.status !== 'REMOVED' && (
            <span className="ml-auto text-xs text-slate-500">
              {editable
                ? `You can edit it until ${formatDate(new Date(editableUntil(current)).toISOString())}.`
                : 'The 7-day editing period is over.'}
            </span>
          )}
        </div>
        <ConfirmDialog
          open={confirmDelete}
          title="Delete your review?"
          confirmLabel="Delete review"
          cancelLabel="Keep it"
          tone="danger"
          pending={remove.isPending}
          error={deleteError}
          onClose={() => setConfirmDelete(false)}
          onConfirm={() => confirmRemove(current)}
        >
          <p>
            Your review of {name} will be removed for good. You can write a new one while the order can still be
            reviewed.
          </p>
        </ConfirmDialog>
      </div>
    )
  }

  return (
    <section
      aria-labelledby={headingId}
      className={cn(
        'rounded-2xl border bg-white p-5 shadow-sm transition-shadow hover:shadow-md',
        current ? 'border-slate-200/80' : 'border-emerald-200/70 ring-1 ring-emerald-50',
      )}
    >
      <div className="mb-4 flex items-start gap-3">
        <span aria-hidden="true" className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-emerald-50">
          <Icon name={TARGET_ICONS[targetType] ?? 'bag'} className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-xs font-semibold tracking-wide text-slate-500 uppercase">{targetTypeLabel(targetType)}</p>
          <Heading id={headingId} className="text-base font-semibold break-words text-slate-900">
            {name}
          </Heading>
          {context && <p className="mt-0.5 text-xs text-slate-500">{context}</p>}
        </div>
        {current && !editing && (
          <Badge tone={reviewStatusTone(current.status)} dot className="shrink-0 px-2.5 py-1 font-semibold">
            {reviewStatusLabel(current.status)}
          </Badge>
        )}
      </div>
      {body}
    </section>
  )
}
