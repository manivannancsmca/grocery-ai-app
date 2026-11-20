import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { provisioningRetryDelay, retryWhileProvisioning } from '../../shared/api/retry'
import { useAuth } from '../../shared/auth/useAuth'
import { useToast } from '../../shared/components/toast/useToast'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
/** @typedef {import('@tanstack/react-query').QueryKey} QueryKey */
/** @typedef {import('react-hook-form').FieldValues} FieldValues */
/** @typedef {import('zod').z} z */
/** @typedef {import('./api').StakeholderSelfApi} StakeholderSelfApi */
/** @typedef {import('./types').StakeholderLifecycle} StakeholderLifecycle */

/** While a submission waits for an admin, poll so the page (and the token claims) update on approval. */
export const PENDING_POLL_INTERVAL_MS = 30_000

/**
 * @template TView
 * @typedef {object} StakeholderProfileOptions
 * @property {QueryKey} queryKey
 * @property {() => Promise<TView>} queryFn
 * @property {string} notFoundCode e.g. `VENDOR_NOT_FOUND` — retried for a few seconds right after registration.
 */

/**
 * @template {StakeholderLifecycle} TView
 * @param {StakeholderProfileOptions<TView>} options
 */
export function useStakeholderProfile({ queryKey, queryFn, notFoundCode }) {
  return useQuery({
    queryKey,
    queryFn,
    retry: retryWhileProvisioning(notFoundCode),
    retryDelay: provisioningRetryDelay,
    refetchOnWindowFocus: true,
    refetchInterval: (query) => (query.state.data?.status === 'PENDING_APPROVAL' ? PENDING_POLL_INTERVAL_MS : false),
  })
}

const CLAIM_SYNC_ATTEMPTS = 5
const CLAIM_SYNC_RETRY_MS = 3_000

/**
 * After approval the access token must carry `vendor_id` / `restaurant_id`. Identity learns about the
 * approval from a Kafka event, so the claim can lag a moment: refresh now, then retry a few times.
 * @param {Pick<StakeholderLifecycle, 'id' | 'status'> | undefined} profile
 * @param {'vendorId' | 'restaurantId'} claim
 * @returns {void}
 */
export function useApprovalClaimSync(profile, claim) {
  const { user, refresh } = useAuth()
  const [attempt, setAttempt] = useState(0)
  const needsSync = profile?.status === 'APPROVED' && user !== null && user[claim] !== profile.id

  useEffect(() => {
    if (!needsSync || attempt >= CLAIM_SYNC_ATTEMPTS) return
    const timer = setTimeout(
      () => {
        refresh()
          .catch(() => undefined)
          .finally(() => setAttempt((count) => count + 1))
      },
      attempt === 0 ? 0 : CLAIM_SYNC_RETRY_MS,
    )
    return () => clearTimeout(timer)
  }, [needsSync, attempt, refresh])
}

/** @typedef {'draft' | 'submit'} OnboardingAction */

/**
 * @template {FieldValues} TValues
 * @template TRequest
 * @template TView
 * @typedef {object} OnboardingFormOptions
 * @property {z.ZodType<TValues, TValues>} draftSchema Format checks only — everything may be empty while editing.
 * @property {z.ZodType<TValues, TValues>} submitSchema Draft rules plus the fields the contract requires before submitting.
 * @property {TValues} defaultValues
 * @property {(values: TValues) => TRequest} toRequest
 * @property {StakeholderSelfApi<TView, TRequest>} api
 * @property {QueryKey} queryKey
 * @property {string} entityLabel Display name used in toasts ("Business profile saved").
 */

/**
 * Form state for the vendor / restaurant / delivery-partner onboarding forms:
 * - "Save draft" validates formats only, then `PUT …/me`.
 * - "Submit for approval" validates required fields, saves (`PUT`), then `POST …/me/submit`.
 * - `422 PROFILE_INCOMPLETE` / `400 VALIDATION_FAILED` field errors land on the matching inputs.
 * @template {FieldValues} TValues
 * @template TRequest
 * @template {StakeholderLifecycle} TView
 * @param {OnboardingFormOptions<TValues, TRequest, TView>} options
 */
export function useOnboardingForm({ draftSchema, submitSchema, defaultValues, toRequest, api, queryKey, entityLabel }) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [formError, setFormError] = useState(null)
  const [pendingAction, setPendingAction] = useState(null)

  // The resolver is chosen per button press; the ref is only read when validation runs, not during render.
  const modeRef = useRef('draft')
  const [resolver] = useState(() => {
    // zodResolver's overloads cannot infer through the generic schema type; the schemas' input and
    // output are both TValues, so this cast is exact.
    const draft = zodResolver(draftSchema)
    const submit = zodResolver(submitSchema)
    const combined = (values, context, options) =>
      (modeRef.current === 'submit' ? submit : draft)(values, context, options)
    return combined
  })

  const form = useForm({ resolver, defaultValues: defaultValues })

  const save = useMutation({ mutationFn: (body) => api.updateMine(body) })
  const submit = useMutation({ mutationFn: () => api.submitMine() })

  function handleError(error, action) {
    if (hasErrorCode(error, 'PROFILE_LOCKED', 'INVALID_STATUS_TRANSITION')) {
      setFormError('This profile can no longer be edited — its status changed. The page has been refreshed.')
      void queryClient.invalidateQueries({ queryKey })
      return
    }
    const { unmatched, placedOnFields } = applyServerFieldErrors(error, form.setError, defaultValues)
    if (hasErrorCode(error, 'PROFILE_INCOMPLETE')) {
      setFormError(['Complete the highlighted fields before submitting for approval.', ...unmatched].join(' '))
      return
    }
    if (!placedOnFields || unmatched.length > 0) {
      const prefix = action === 'submit' ? 'Could not submit: ' : 'Could not save: '
      setFormError(prefix + (unmatched.length > 0 ? unmatched.join(' ') : errorMessage(error)))
    }
  }

  async function run(action, values) {
    setFormError(null)
    setPendingAction(action)
    try {
      const saved = await save.mutateAsync(toRequest(values))
      queryClient.setQueryData(queryKey, saved)
      if (action === 'draft') {
        form.reset(values)
        toast.success(`${entityLabel} saved as draft.`)
        return
      }
      const submitted = await submit.mutateAsync()
      queryClient.setQueryData(queryKey, submitted)
      toast.success(`${entityLabel} submitted for approval.`)
    } catch (error) {
      handleError(error, action)
    } finally {
      setPendingAction(null)
    }
  }

  function trigger(action) {
    modeRef.current = action
    setFormError(null)
    return form.handleSubmit(
      (values) => run(action, values),
      () => {
        if (action === 'submit') setFormError('Complete the highlighted fields before submitting for approval.')
      },
    )()
  }

  return {
    form,
    formError,
    pendingAction,
    saveDraft: () => trigger('draft'),
    submitForApproval: () => trigger('submit'),
  }
}
