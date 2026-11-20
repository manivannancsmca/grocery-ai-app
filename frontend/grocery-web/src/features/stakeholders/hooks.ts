import { zodResolver } from '@hookform/resolvers/zod'
import { type QueryKey, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { type DefaultValues, type FieldValues, type Resolver, useForm } from 'react-hook-form'
import type { z } from 'zod'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { provisioningRetryDelay, retryWhileProvisioning } from '../../shared/api/retry'
import { useAuth } from '../../shared/auth/useAuth'
import { useToast } from '../../shared/components/toast/useToast'
import { applyServerFieldErrors } from '../../shared/forms/serverErrors'
import type { StakeholderSelfApi } from './api'
import type { StakeholderLifecycle } from './types'

/** While a submission waits for an admin, poll so the page (and the token claims) update on approval. */
export const PENDING_POLL_INTERVAL_MS = 30_000

interface StakeholderProfileOptions<TView> {
  queryKey: QueryKey
  queryFn: () => Promise<TView>
  /** e.g. `VENDOR_NOT_FOUND` — retried for a few seconds right after registration. */
  notFoundCode: string
}

export function useStakeholderProfile<TView extends StakeholderLifecycle>({
  queryKey,
  queryFn,
  notFoundCode,
}: StakeholderProfileOptions<TView>) {
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
 */
export function useApprovalClaimSync(
  profile: Pick<StakeholderLifecycle, 'id' | 'status'> | undefined,
  claim: 'vendorId' | 'restaurantId',
): void {
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

type OnboardingAction = 'draft' | 'submit'

interface OnboardingFormOptions<TValues extends FieldValues, TRequest, TView> {
  /** Format checks only — everything may be empty while editing. */
  draftSchema: z.ZodType<TValues, TValues>
  /** Draft rules plus the fields the contract requires before submitting. */
  submitSchema: z.ZodType<TValues, TValues>
  defaultValues: TValues
  toRequest: (values: TValues) => TRequest
  api: StakeholderSelfApi<TView, TRequest>
  queryKey: QueryKey
  /** Display name used in toasts ("Business profile saved"). */
  entityLabel: string
}

/**
 * Form state for the vendor / restaurant / delivery-partner onboarding forms:
 * - "Save draft" validates formats only, then `PUT …/me`.
 * - "Submit for approval" validates required fields, saves (`PUT`), then `POST …/me/submit`.
 * - `422 PROFILE_INCOMPLETE` / `400 VALIDATION_FAILED` field errors land on the matching inputs.
 */
export function useOnboardingForm<TValues extends FieldValues, TRequest, TView extends StakeholderLifecycle>({
  draftSchema,
  submitSchema,
  defaultValues,
  toRequest,
  api,
  queryKey,
  entityLabel,
}: OnboardingFormOptions<TValues, TRequest, TView>) {
  const queryClient = useQueryClient()
  const toast = useToast()
  const [formError, setFormError] = useState<string | null>(null)
  const [pendingAction, setPendingAction] = useState<OnboardingAction | null>(null)

  // The resolver is chosen per button press; the ref is only read when validation runs, not during render.
  const modeRef = useRef<OnboardingAction>('draft')
  const [resolver] = useState(() => {
    // zodResolver's overloads cannot infer through the generic schema type; the schemas' input and
    // output are both TValues, so this cast is exact.
    const draft = zodResolver(draftSchema) as unknown as Resolver<TValues>
    const submit = zodResolver(submitSchema) as unknown as Resolver<TValues>
    const combined: Resolver<TValues> = (values, context, options) =>
      (modeRef.current === 'submit' ? submit : draft)(values, context, options)
    return combined
  })

  const form = useForm<TValues>({ resolver, defaultValues: defaultValues as DefaultValues<TValues> })

  const save = useMutation({ mutationFn: (body: TRequest) => api.updateMine(body) })
  const submit = useMutation({ mutationFn: () => api.submitMine() })

  function handleError(error: unknown, action: OnboardingAction) {
    if (hasErrorCode(error, 'PROFILE_LOCKED', 'INVALID_STATUS_TRANSITION')) {
      setFormError('This profile can no longer be edited — its status changed. The page has been refreshed.')
      void queryClient.invalidateQueries({ queryKey })
      return
    }
    const { unmatched, placedOnFields } = applyServerFieldErrors(error, form.setError, defaultValues)
    if (hasErrorCode(error, 'PROFILE_INCOMPLETE')) {
      setFormError(
        ['Complete the highlighted fields before submitting for approval.', ...unmatched].join(' '),
      )
      return
    }
    if (!placedOnFields || unmatched.length > 0) {
      const prefix = action === 'submit' ? 'Could not submit: ' : 'Could not save: '
      setFormError(prefix + (unmatched.length > 0 ? unmatched.join(' ') : errorMessage(error)))
    }
  }

  async function run(action: OnboardingAction, values: TValues) {
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

  function trigger(action: OnboardingAction) {
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
