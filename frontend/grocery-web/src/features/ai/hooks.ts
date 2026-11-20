import { keepPreviousData, type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { hasErrorCode } from '../../shared/api/problemDetail'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import {
  AI_ERRORS,
  aiKeys,
  approvalKeys,
  approveApproval,
  type ApprovalListParams,
  type ApprovalView,
  cancelApproval,
  type ConversationDetail,
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
  type ToolInvocationParams,
} from './api'
import { keysChangedBy } from './labels'

/** The model probe is cached 30 s by the server; the open panel asks as often. */
export const AI_STATUS_POLL_MS = 30_000
/** Approvals refresh on `AI_APPROVALS_CHANGED`; while the live stream is down they are polled instead. */
export const APPROVALS_FALLBACK_POLL_MS = 30_000
/** A card's approval from the chat stream is current; the live signal (or an action) refreshes it. */
export const APPROVAL_FRESH_MS = 30_000

/** Which agents the caller may use (CUSTOMER → `shopping`, admins → `admin-bi`, others → none). Once per session. */
export function useAgents(enabled: boolean) {
  return useQuery({ queryKey: aiKeys.agents(), queryFn: fetchAgents, enabled, staleTime: 5 * 60_000 })
}

/** `available: false` → the assistant's offline state. Asked only while the panel is open. */
export function useAiStatus(enabled: boolean) {
  return useQuery({ queryKey: aiKeys.status(), queryFn: fetchAiStatus, enabled, refetchInterval: enabled ? AI_STATUS_POLL_MS : false })
}

export function useConversations(agent: string, enabled: boolean) {
  return useQuery({ queryKey: aiKeys.conversationList(agent), queryFn: () => listConversations(agent), enabled })
}

/** Renames one of the user's own conversations; the lists and the open conversation show the new title. */
export function useRenameConversation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) => renameConversation(id, title),
    onSuccess: (summary) =>
      queryClient.setQueryData<ConversationDetail>(aiKeys.conversation(summary.id), (detail) => (detail ? { ...detail, title: summary.title } : detail)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: [...aiKeys.conversations(), 'list'] }),
  })
}

/** Who handles voice for this user (server, browser or off). Asked once per session; an error means no voice. */
export function useVoiceStatus(enabled: boolean) {
  return useQuery({ queryKey: aiKeys.voiceStatus(), queryFn: fetchVoiceStatus, enabled, staleTime: 10 * 60_000, retry: false })
}

export function useDeleteConversation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => deleteConversation(id),
    onSuccess: (_data, id) => queryClient.removeQueries({ queryKey: aiKeys.conversation(id) }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: aiKeys.conversations() }),
  })
}

export function useApprovals(params: ApprovalListParams, enabled = true) {
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
 */
export function useApproval(id: string, initial?: ApprovalView) {
  return useQuery({
    queryKey: approvalKeys.detail(id),
    queryFn: () => fetchApproval(id),
    initialData: initial,
    staleTime: initial ? APPROVAL_FRESH_MS : 0,
  })
}

/** Puts an approval the server sent (stream, history, a decision) into the cache its card reads. */
export function storeApproval(queryClient: QueryClient, approval: ApprovalView): void {
  queryClient.setQueryData(approvalKeys.detail(approval.id), approval)
}

/** After a decision: the answer goes into the card, lists refresh, and a finished action refreshes what it changed. */
async function afterDecision(queryClient: QueryClient, approval: ApprovalView): Promise<void> {
  storeApproval(queryClient, approval)
  const keys = approval.status === 'COMPLETED' ? keysChangedBy(approval.action) : []
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: approvalKeys.lists() }),
    ...keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })),
  ])
}

/** A 409 APPROVAL_NOT_PENDING (or 404) means the card is out of date: re-read it. */
async function afterRefusal(queryClient: QueryClient, id: string, error: unknown): Promise<void> {
  if (hasErrorCode(error, AI_ERRORS.approvalNotPending, AI_ERRORS.approvalNotFound)) {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: approvalKeys.detail(id) }),
      queryClient.invalidateQueries({ queryKey: approvalKeys.lists() }),
    ])
  }
}

export type DecisionKind = 'approve' | 'reject' | 'cancel'

export interface DecisionVariables {
  id: string
  kind: DecisionKind
  comment?: string
}

/** Approve (runs the action, answers COMPLETED / FAILED), reject (comment required) or cancel. */
export function useApprovalDecision() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, kind, comment }: DecisionVariables) =>
      kind === 'approve' ? approveApproval(id, comment || undefined) : kind === 'reject' ? rejectApproval(id, comment ?? '') : cancelApproval(id),
    onSuccess: (approval) => afterDecision(queryClient, approval),
    onError: (error, { id }) => afterRefusal(queryClient, id, error),
  })
}

export function useGenerateInsights() {
  return useMutation({ mutationFn: (date?: string) => generateInsights(date) })
}

export function useToolInvocations(params: ToolInvocationParams) {
  return useQuery({
    queryKey: aiKeys.invocations(params),
    queryFn: () => listToolInvocations(params),
    placeholderData: keepPreviousData,
  })
}

export function useAiSummary(date: string | null) {
  return useQuery({ queryKey: aiKeys.summary(date), queryFn: () => fetchAiSummary(date) })
}
