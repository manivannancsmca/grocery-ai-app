import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { hasErrorCode } from '../../shared/api/problemDetail'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import {
  AI_ERRORS,
  aiKeys,
  approvalKeys,
  approveApproval,
  cancelApproval,
  deleteConversation,
  fetchAgents,
  fetchAiStatus,
  fetchAiSummary,
  fetchApproval,
  fetchVoiceStatus,
  generateInsights,
  listApprovals,
  listConversations,
  listToolInvocations,
  rejectApproval,
  renameConversation,
} from './api'
import { keysChangedBy } from './labels'

/** @typedef {import('@tanstack/react-query').QueryClient} QueryClient */
/** @typedef {import('./api').ApprovalListParams} ApprovalListParams */
/** @typedef {import('./api').ApprovalView} ApprovalView */
/** @typedef {import('./api').ToolInvocationParams} ToolInvocationParams */

/** The model probe is cached 30 s by the server; the open panel asks as often. */
export const AI_STATUS_POLL_MS = 30_000
/** Approvals refresh on `AI_APPROVALS_CHANGED`; while the live stream is down they are polled instead. */
export const APPROVALS_FALLBACK_POLL_MS = 30_000
/** A card's approval from the chat stream is current; the live signal (or an action) refreshes it. */
export const APPROVAL_FRESH_MS = 30_000

/**
 * Which agents the caller may use (CUSTOMER → `shopping`, admins → `admin-bi`, others → none). Once per session.
 * @param {boolean} enabled
 */
export function useAgents(enabled) {
  return useQuery({ queryKey: aiKeys.agents(), queryFn: fetchAgents, enabled, staleTime: 5 * 60_000 })
}

/**
 * `available: false` → the assistant's offline state. Asked only while the panel is open.
 * @param {boolean} enabled
 */
export function useAiStatus(enabled) {
  return useQuery({
    queryKey: aiKeys.status(),
    queryFn: fetchAiStatus,
    enabled,
    refetchInterval: enabled ? AI_STATUS_POLL_MS : false,
  })
}

/**
 * @param {string} agent
 * @param {boolean} enabled
 */
export function useConversations(agent, enabled) {
  return useQuery({ queryKey: aiKeys.conversationList(agent), queryFn: () => listConversations(agent), enabled })
}

/** Renames one of the user's own conversations; the lists and the open conversation show the new title. */
export function useRenameConversation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, title }) => renameConversation(id, title),
    onSuccess: (summary) =>
      queryClient.setQueryData(aiKeys.conversation(summary.id), (detail) =>
        detail ? { ...detail, title: summary.title } : detail,
      ),
    onSettled: () => queryClient.invalidateQueries({ queryKey: [...aiKeys.conversations(), 'list'] }),
  })
}

/**
 * Who handles voice for this user (server, browser or off). Asked once per session; an error means no voice.
 * @param {boolean} enabled
 */
export function useVoiceStatus(enabled) {
  return useQuery({
    queryKey: aiKeys.voiceStatus(),
    queryFn: fetchVoiceStatus,
    enabled,
    staleTime: 10 * 60_000,
    retry: false,
  })
}

export function useDeleteConversation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id) => deleteConversation(id),
    onSuccess: (_data, id) => queryClient.removeQueries({ queryKey: aiKeys.conversation(id) }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: aiKeys.conversations() }),
  })
}

/**
 * @param {ApprovalListParams} params
 * @param [enabled=true]
 */
export function useApprovals(params, enabled = true) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: approvalKeys.list(params),
    queryFn: () => listApprovals(params),
    enabled,
    placeholderData: keepPreviousData,
    refetchInterval: live ? false : APPROVALS_FALLBACK_POLL_MS,
  })
}

/**
 * One approval request. With `initial` (a card in the chat) it shows at once and is only re-read when it is
 * invalidated (`AI_APPROVALS_CHANGED`, a decision); without it (a link `?id=`) it is fetched.
 * @param {string} id
 * @param {ApprovalView} [initial]
 */
export function useApproval(id, initial) {
  return useQuery({
    queryKey: approvalKeys.detail(id),
    queryFn: () => fetchApproval(id),
    initialData: initial,
    staleTime: initial ? APPROVAL_FRESH_MS : 0,
  })
}

/**
 * Puts an approval the server sent (stream, history, a decision) into the cache its card reads.
 * @param {QueryClient} queryClient
 * @param {ApprovalView} approval
 * @returns {void}
 */
export function storeApproval(queryClient, approval) {
  queryClient.setQueryData(approvalKeys.detail(approval.id), approval)
}

/**
 * After a decision: the answer goes into the card, lists refresh, and a finished action refreshes what it changed.
 * @param {QueryClient} queryClient
 * @param {ApprovalView} approval
 * @returns {Promise<void>}
 */
async function afterDecision(queryClient, approval) {
  storeApproval(queryClient, approval)
  const keys = approval.status === 'COMPLETED' ? keysChangedBy(approval.action) : []
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: approvalKeys.lists() }),
    ...keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })),
  ])
}

/**
 * A 409 APPROVAL_NOT_PENDING (or 404) means the card is out of date: re-read it.
 * @param {QueryClient} queryClient
 * @param {string} id
 * @param {unknown} error
 * @returns {Promise<void>}
 */
async function afterRefusal(queryClient, id, error) {
  if (hasErrorCode(error, AI_ERRORS.approvalNotPending, AI_ERRORS.approvalNotFound)) {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: approvalKeys.detail(id) }),
      queryClient.invalidateQueries({ queryKey: approvalKeys.lists() }),
    ])
  }
}

/** @typedef {'approve' | 'reject' | 'cancel'} DecisionKind */

/**
 * @typedef {object} DecisionVariables
 * @property {string} id
 * @property {DecisionKind} kind
 * @property {string} [comment]
 */

/** Approve (runs the action, answers COMPLETED / FAILED), reject (comment required) or cancel. */
export function useApprovalDecision() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, kind, comment }) =>
      kind === 'approve'
        ? approveApproval(id, comment || undefined)
        : kind === 'reject'
          ? rejectApproval(id, comment ?? '')
          : cancelApproval(id),
    onSuccess: (approval) => afterDecision(queryClient, approval),
    onError: (error, { id }) => afterRefusal(queryClient, id, error),
  })
}

export function useGenerateInsights() {
  return useMutation({ mutationFn: (date) => generateInsights(date) })
}

/** @param {ToolInvocationParams} params */
export function useToolInvocations(params) {
  return useQuery({
    queryKey: aiKeys.invocations(params),
    queryFn: () => listToolInvocations(params),
    placeholderData: keepPreviousData,
  })
}

/** @param {string | null} date */
export function useAiSummary(date) {
  return useQuery({ queryKey: aiKeys.summary(date), queryFn: () => fetchAiSummary(date) })
}
