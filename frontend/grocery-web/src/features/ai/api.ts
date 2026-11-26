import { isAxiosError } from 'axios'
import { httpClient } from '../../shared/api/httpClient'
import { ApiError, isProblemDetail } from '../../shared/api/problemDetail'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import { isAdminRole, type RoleArea } from '../../shared/auth/roles'

/**
 * Phase 11 — agentic AI and human approval (ai-orchestrator-service, contract
 * `docs/api/phase-11-agentic-ai-and-approvals.md`). Chat answers are streamed (`chatStream.ts`); everything else
 * is plain REST through the shared client.
 */

/**
 * `shopping` (CUSTOMER) and `admin-bi` (any admin role); Phase 12 adds `vendor` (VENDOR), `restaurant`
 * (RESTAURANT_OWNER) and `delivery` (DELIVERY_PARTNER, DELIVERY_MANAGER); customers also have `payment` and `support`
 * (docs/api/payment-and-support-agents.md) and switch between their three assistants in the panel.
 */
export type AgentId = 'shopping' | 'admin-bi' | 'vendor' | 'restaurant' | 'delivery' | 'payment' | 'support' | (string & {})

/** `GET /api/ai/agents` — only the agents the caller may use. */
export interface AgentView {
  id: AgentId
  name: string
  description: string
  examplePrompts: string[]
}

/** The agent each area is meant to use; the server's list decides whether the user may. */
const PREFERRED_AGENT: Partial<Record<RoleArea, string>> = {
  CUSTOMER: 'shopping',
  ADMIN: 'admin-bi',
  VENDOR: 'vendor',
  RESTAURANT_OWNER: 'restaurant',
  DELIVERY_PARTNER: 'delivery',
}

interface PickAgentOptions {
  /** The user's roles: a delivery manager without another admin role starts with the `delivery` agent. */
  roles?: readonly string[]
  /** The agent the user switched to in the panel (when the server offers more than one). */
  chosen?: string | null
}

/** The chosen agent, else the area's own agent when the server offers it, else the first one it offers (null: no assistant). */
export function pickAgent(agents: readonly AgentView[] | undefined, area: RoleArea | null, options: PickAgentOptions = {}): AgentView | null {
  if (!agents || agents.length === 0) return null
  const chosen = options.chosen ? agents.find((agent) => agent.id === options.chosen) : undefined
  if (chosen) return chosen
  const roles = options.roles ?? []
  const deliveryManagerOnly =
    area === 'ADMIN' && roles.includes('DELIVERY_MANAGER') && !roles.some((role) => role !== 'DELIVERY_MANAGER' && isAdminRole(role))
  const preferred = deliveryManagerOnly ? 'delivery' : area ? PREFERRED_AGENT[area] : undefined
  return agents.find((agent) => agent.id === preferred) ?? agents.find((agent) => agent.id === (area ? PREFERRED_AGENT[area] : undefined)) ?? agents[0] ?? null
}

/** `GET /api/ai/status` — the model probe (cached 30 s by the server). */
export interface AiStatus {
  provider: string
  model: string
  available: boolean
  checkedAt: string
}

export const TOOL_STATUSES = ['STARTED', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'APPROVAL_REQUIRED'] as const
export type ToolStatus = (typeof TOOL_STATUSES)[number]

/** A page of this app an answer points to: "View my cart" → `/cart` (server: `link/Pages.java`). */
export interface PageLink {
  label: string
  /** An app path with its query string, e.g. `/search?q=rice`. */
  path: string
}

/** `tool` stream event and `messages[].tools[]` — one tool call as the chat shows it. */
export interface ToolActivity {
  invocationId: string
  /** The tool's name (`addToCart`). */
  tool: string
  /** Human-readable, from the server ("Searched products"). */
  label: string
  status: ToolStatus | (string & {})
  summary: string | null
  /** Pages that show what a successful call found (buttons under the answer); absent from older servers. */
  links?: PageLink[]
}

export const APPROVAL_STATUSES = [
  'PENDING_APPROVAL',
  'APPROVED',
  'EXECUTING',
  'COMPLETED',
  'FAILED',
  'REJECTED',
  'EXPIRED',
  'CANCELLED',
] as const
export type ApprovalStatus = (typeof APPROVAL_STATUSES)[number]

export function isApprovalStatus(value: unknown): value is ApprovalStatus {
  return typeof value === 'string' && (APPROVAL_STATUSES as readonly string[]).includes(value)
}

export const APPROVAL_ACTIONS = [
  'PLACE_ORDER',
  'CANCEL_ORDER',
  'REFUND_PAYMENT',
  'ADMIN_CANCEL_ORDER',
  'SUSPEND_VENDOR',
  'SUSPEND_RESTAURANT',
  // Phase 12 (customer, vendor, restaurant and delivery assistants).
  'REQUEST_RETURN',
  'ADJUST_STOCK',
  'CHANGE_PRICE',
  'SEND_PURCHASE_ORDER',
  'SET_MENU_ITEM_AVAILABILITY',
  'ACCEPT_STORE_ORDER',
  'REJECT_STORE_ORDER',
  'REASSIGN_DELIVERY',
] as const
export type ApprovalAction = (typeof APPROVAL_ACTIONS)[number]

/** Decided by the customer the action is for (their own purchase): "Confirm" / "Not now". */
export const CUSTOMER_ACTIONS: readonly string[] = ['PLACE_ORDER', 'CANCEL_ORDER']

export function isCustomerAction(action: string): boolean {
  return CUSTOMER_ACTIONS.includes(action)
}

/** Phase 12: decided by the vendor or restaurant owner who asked for it (`approverUserId` = requester): "Confirm" / "Not now". */
export const STORE_SELF_ACTIONS: readonly string[] = [
  'ADJUST_STOCK',
  'CHANGE_PRICE',
  'SEND_PURCHASE_ORDER',
  'SET_MENU_ITEM_AVAILABILITY',
  'ACCEPT_STORE_ORDER',
  'REJECT_STORE_ORDER',
]

/** The person who asked decides it themselves (customer or store actions) — never an admin. */
export function isSelfDecidedAction(action: string): boolean {
  return isCustomerAction(action) || action === 'REQUEST_RETURN' || STORE_SELF_ACTIONS.includes(action)
}

export interface ApprovalSummaryLine {
  label: string
  value: string
}

/** Rendered by the server from the stored parameters — never by the model. */
export interface ApprovalSummary {
  title: string
  lines: ApprovalSummaryLine[]
  amount: number | null
  currency: string | null
}

/** Why the target refused the approved action (`TOTAL_CHANGED`, `CART_CHANGED`, `APPROVAL_BODY_MISMATCH`, …). */
export interface ApprovalFailure {
  code: string
  message: string
  httpStatus: number | null
}

/** `ApprovalView` (contract §4). */
export interface ApprovalView {
  id: string
  action: ApprovalAction | (string & {})
  status: ApprovalStatus | (string & {})
  agent: AgentId
  conversationId: string | null
  requestedBy: string
  /** The customer the action is for (absent for admin actions). */
  onBehalfOf: string | null
  /** A specific decider (the customer), or null when roles decide. */
  approverUserId: string | null
  approverRoles: string[]
  /** The requester may not decide it. */
  fourEyes: boolean
  summary: ApprovalSummary
  /** The model's own explanation ("The assistant's note"), ≤ 500. */
  rationale: string | null
  /** First 8 hex characters of the SHA-256 of the exact request body. */
  fingerprint: string
  /** The caller may approve or reject it now. */
  canDecide: boolean
  createdAt: string
  expiresAt: string
  decidedBy: string | null
  decidedAt: string | null
  comment: string | null
  executedAt: string | null
  /** COMPLETED: what the target answered, e.g. `{ orderId, orderNumber }`. */
  result: Record<string, unknown> | null
  failure: ApprovalFailure | null
}

export type MessageRole = 'USER' | 'ASSISTANT'

/**
 * A help-center document an answer was given (knowledge base, docs/rag/RAG-API-REFERENCE.md): the `sources` stream
 * event and `messages[].sources`. Only its title and category — never its text or anything about the index.
 */
export interface KnowledgeSource {
  documentId: string
  title: string
  category: string
}

export interface ConversationMessage {
  id: string
  role: MessageRole | (string & {})
  content: string
  createdAt: string
  tools: ToolActivity[]
  approvals: ApprovalView[]
  /** An answer that stated figures no tool returned (not checked against the app's data); absent from older servers. */
  unverified?: boolean
  /** The help-center documents the answer was given; absent from older servers. */
  sources?: KnowledgeSource[]
}

export interface ConversationSummary {
  id: string
  agent: AgentId
  /** The first message, ≤ 80 characters. */
  title: string
  createdAt: string
  updatedAt: string
}

export interface ConversationDetail {
  id: string
  agent: AgentId
  title: string
  messages: ConversationMessage[]
}

/** `InsightReport` (contract §6) — the model's structured output, or the rules fallback (`provider: "rules"`). */
export interface InsightKpi {
  name: string
  value: string | number
  note?: string | null
}

export interface InsightReport {
  date: string
  headline: string
  kpis: InsightKpi[]
  observations: string[]
  risks: string[]
  suggestedActions: string[]
  generatedBy: { provider: string; model: string | null }
  generatedAt: string
}

/** The provider name of the rule-based fallback report. */
export const RULES_PROVIDER = 'rules'

/** Audited statuses of a tool call (STARTED is never stored). */
export const INVOCATION_STATUSES = ['SUCCEEDED', 'FAILED', 'BLOCKED', 'APPROVAL_REQUIRED'] as const
export type InvocationStatus = (typeof INVOCATION_STATUSES)[number]

/**
 * One row of `ai_tool_invocation` (contract §3 "Every call is audited"). Field names follow the contract's
 * column list; `tool` is null for a blocked agent access (`AGENT_NOT_ALLOWED`).
 */
export interface ToolInvocationView {
  id: string
  conversationId: string | null
  userId: string
  roles: string[]
  agent: AgentId
  tool: string | null
  /** The model's arguments (JSON object, or its text). */
  arguments: unknown
  status: InvocationStatus | (string & {})
  /** BLOCKED: `TOOL_NOT_ALLOWED`, `ROLE_NOT_ALLOWED`, `INVALID_ARGUMENTS`, `AGENT_NOT_ALLOWED`; FAILED: the API's code. */
  reason: string | null
  /** ≤ 500. */
  resultSummary: string | null
  httpStatus: number | null
  latencyMs: number | null
  approvalId: string | null
  createdAt: string
}

export interface ToolInvocationParams {
  agent?: string
  userId?: string
  tool?: string
  status?: string
  /** Days (`YYYY-MM-DD`, India time), inclusive. */
  from?: string
  to?: string
  page?: number
  size?: number
}

/** `GET /api/admin/ai/summary?date=` (contract §6). */
export interface AiSummary {
  date: string
  conversations: number
  messages: number
  toolCalls: number
  blocked: number
  failed: number
  approvals: {
    requested: number
    completed: number
    rejected: number
    expired: number
    failed: number
    cancelled: number
    pending: number
  }
  modelErrors: number
  avgModelLatencyMs: number | null
  provider: string
  model: string | null
  available: boolean
}

export type ApprovalScope = 'mine' | 'to-decide'

export interface ApprovalListParams {
  /** `mine` (default) = requested by me or for me; `to-decide` = PENDING_APPROVAL ones I may decide. */
  scope?: ApprovalScope
  status?: ApprovalStatus
  page?: number
  size?: number
}

export const AI_LIMITS = { message: 2000, comment: 300 } as const

export const AI_ERRORS = {
  agentNotFound: 'AGENT_NOT_FOUND',
  agentNotAllowed: 'AGENT_NOT_ALLOWED',
  conversationNotFound: 'CONVERSATION_NOT_FOUND',
  rateLimited: 'AI_RATE_LIMITED',
  approvalNotFound: 'APPROVAL_NOT_FOUND',
  approvalForbidden: 'APPROVAL_FORBIDDEN',
  fourEyesRequired: 'FOUR_EYES_REQUIRED',
  approvalNotPending: 'APPROVAL_NOT_PENDING',
} as const

/** `error` stream event codes. */
export const AI_STREAM_ERRORS = ['AI_UNAVAILABLE', 'AI_TIMEOUT', 'AI_TOOL_LIMIT', 'AI_ERROR'] as const

export const aiKeys = {
  all: ['ai'] as const,
  agents: () => [...aiKeys.all, 'agents'] as const,
  status: () => [...aiKeys.all, 'status'] as const,
  conversations: () => [...aiKeys.all, 'conversations'] as const,
  conversationList: (agent: string) => [...aiKeys.conversations(), 'list', agent] as const,
  conversation: (id: string) => [...aiKeys.conversations(), 'detail', id] as const,
  voiceStatus: () => [...aiKeys.all, 'voice-status'] as const,
  admin: () => [...aiKeys.all, 'admin'] as const,
  invocations: (params: ToolInvocationParams) => [...aiKeys.admin(), 'invocations', params] as const,
  summaries: () => [...aiKeys.admin(), 'summary'] as const,
  summary: (date: string | null) => [...aiKeys.summaries(), date] as const,
}

/** Approval requests (lists and single ones, including the cards inside the chat). */
export const approvalKeys = {
  all: ['ai-approvals'] as const,
  lists: () => [...approvalKeys.all, 'list'] as const,
  list: (params: ApprovalListParams) => [...approvalKeys.lists(), params] as const,
  details: () => [...approvalKeys.all, 'detail'] as const,
  detail: (id: string) => [...approvalKeys.details(), id] as const,
}

/** The chat endpoint (streamed — read with `streamChat`, not axios). */
export function chatUrl(agent: string): string {
  return `/api/ai/agents/${segment(agent)}/chat`
}

export async function fetchAgents(): Promise<AgentView[]> {
  const data = (await httpClient.get<AgentView[]>('/ai/agents')).data
  return Array.isArray(data) ? data.map((agent) => ({ ...agent, examplePrompts: Array.isArray(agent.examplePrompts) ? agent.examplePrompts : [] })) : []
}

export async function fetchAiStatus(): Promise<AiStatus> {
  return (await httpClient.get<AiStatus>('/ai/status')).data
}

/** Own conversations of one agent, newest first. */
export async function listConversations(agent: string, page = 0, size = 20): Promise<PageResponse<ConversationSummary>> {
  return (await httpClient.get<PageResponse<ConversationSummary>>('/ai/conversations', { params: { agent, page, size } })).data
}

/** 404 CONVERSATION_NOT_FOUND. */
export async function fetchConversation(id: string): Promise<ConversationDetail> {
  const data = (await httpClient.get<ConversationDetail>(`/ai/conversations/${segment(id)}`)).data
  return {
    ...data,
    messages: (data.messages ?? []).map((message) => ({ ...message, tools: message.tools ?? [], approvals: message.approvals ?? [] })),
  }
}

/** 204 — messages and memory are deleted; the audit and the approvals are kept. */
export async function deleteConversation(id: string): Promise<void> {
  await httpClient.delete(`/ai/conversations/${segment(id)}`)
}

/** Only the owner may rename; the server trims the title and accepts 1–80 characters (400 VALIDATION_FAILED). */
export async function renameConversation(id: string, title: string): Promise<ConversationSummary> {
  return (await httpClient.patch<ConversationSummary>(`/ai/conversations/${segment(id)}`, { title })).data
}

// ---- voice -------------------------------------------------------------------------------------------------------

/**
 * Who handles voice (`GET /api/ai/voice/status`): `SERVER` — record here, the server turns speech into text and text
 * into speech; `BROWSER` — use the browser's own speech recognition and synthesis; `OFF` — no voice.
 */
export type VoiceMode = 'SERVER' | 'BROWSER' | 'OFF'

export interface VoiceStatus {
  mode: VoiceMode
  /** The server transcribes recordings (`SERVER` only). */
  transcription: boolean
  /** The server reads answers aloud (`SERVER` only). */
  speech: boolean
  maxRecordingSeconds: number
  maxAudioBytes: number
  maxSpeechCharacters: number
}

export const VOICE_OFF: VoiceStatus = { mode: 'OFF', transcription: false, speech: false, maxRecordingSeconds: 60, maxAudioBytes: 10_485_760, maxSpeechCharacters: 4000 }

export const VOICE_ERRORS = {
  disabled: 'VOICE_DISABLED',
  notAllowed: 'VOICE_NOT_ALLOWED',
  empty: 'AUDIO_EMPTY',
  tooLarge: 'AUDIO_TOO_LARGE',
  unsupported: 'UNSUPPORTED_AUDIO_FORMAT',
  unreadable: 'AUDIO_UNREADABLE',
  noSpeech: 'NO_SPEECH_DETECTED',
  unavailable: 'VOICE_UNAVAILABLE',
  timeout: 'VOICE_TIMEOUT',
  rateLimited: 'VOICE_RATE_LIMITED',
} as const

export async function fetchVoiceStatus(): Promise<VoiceStatus> {
  const data = (await httpClient.get<VoiceStatus>('/ai/voice/status')).data
  return data && (data.mode === 'SERVER' || data.mode === 'BROWSER' || data.mode === 'OFF') ? { ...VOICE_OFF, ...data } : VOICE_OFF
}

/** A recording's file name for the upload; the server checks the real format from the bytes. */
function recordingName(type: string): string {
  if (type.includes('ogg')) return 'question.ogg'
  if (type.includes('mp4') || type.includes('aac')) return 'question.m4a'
  if (type.includes('wav')) return 'question.wav'
  return 'question.webm'
}

/** Speech to text (`SERVER` mode): the recognised words, to put into the chat input. */
export async function transcribeAudio(audio: Blob, signal?: AbortSignal): Promise<string> {
  const form = new FormData()
  form.append('audio', audio, recordingName(audio.type))
  const data = (await httpClient.post<{ text: string }>('/ai/voice/transcriptions', form, { signal, timeout: 90_000 })).data
  return typeof data?.text === 'string' ? data.text : ''
}

/** Text to speech (`SERVER` mode): MP3 audio of an answer (markdown is read as plain words by the server). */
export async function synthesizeSpeech(text: string, signal?: AbortSignal): Promise<Blob> {
  try {
    const response = await httpClient.post<Blob>('/ai/voice/speech', { text }, { signal, timeout: 90_000, responseType: 'blob', headers: { Accept: 'audio/mpeg, application/problem+json' } })
    return response.data
  } catch (error) {
    throw await withBlobProblem(error)
  }
}

/** An error answer to a `blob` request arrives as a Blob: read its ProblemDetail, so the `errorCode` is known. */
async function withBlobProblem(error: unknown): Promise<unknown> {
  if (!(error instanceof ApiError) || error.problem || !isAxiosError(error.cause)) return error
  const data: unknown = error.cause.response?.data
  if (!(data instanceof Blob)) return error
  try {
    const parsed: unknown = JSON.parse(await data.text())
    if (isProblemDetail(parsed)) {
      return new ApiError(parsed.detail ?? error.message, { kind: error.kind, status: error.status, problem: parsed, correlationId: error.correlationId, cause: error.cause })
    }
  } catch {
    // not JSON: keep the error as it is
  }
  return error
}

/** The server gathers the day's data itself; the model may take a while on CPU (120 s), then rules take over. */
export async function generateInsights(date?: string): Promise<InsightReport> {
  return (await httpClient.post<InsightReport>('/ai/agents/admin-bi/insights', cleanParams({ date }), { timeout: 150_000 })).data
}

export async function listApprovals(params: ApprovalListParams): Promise<PageResponse<ApprovalView>> {
  return (await httpClient.get<PageResponse<ApprovalView>>('/approvals', { params: cleanParams(params) })).data
}

/** 404 APPROVAL_NOT_FOUND. */
export async function fetchApproval(id: string): Promise<ApprovalView> {
  return (await httpClient.get<ApprovalView>(`/approvals/${segment(id)}`)).data
}

/**
 * Runs the action synchronously with the approver's authority; the answer is COMPLETED (with `result`) or FAILED
 * (with `failure`). 403 APPROVAL_FORBIDDEN / FOUR_EYES_REQUIRED, 409 APPROVAL_NOT_PENDING (`currentStatus`).
 */
export async function approveApproval(id: string, comment?: string): Promise<ApprovalView> {
  return (await httpClient.post<ApprovalView>(`/approvals/${segment(id)}/approve`, cleanParams({ comment }), { timeout: 60_000 })).data
}

/** Comment required, ≤ 300 → REJECTED. */
export async function rejectApproval(id: string, comment: string): Promise<ApprovalView> {
  return (await httpClient.post<ApprovalView>(`/approvals/${segment(id)}/reject`, { comment })).data
}

/** The requester (or the customer it is for) → CANCELLED. */
export async function cancelApproval(id: string): Promise<ApprovalView> {
  return (await httpClient.post<ApprovalView>(`/approvals/${segment(id)}/cancel`)).data
}

export async function listToolInvocations(params: ToolInvocationParams): Promise<PageResponse<ToolInvocationView>> {
  return (await httpClient.get<PageResponse<ToolInvocationView>>('/admin/ai/tool-invocations', { params: cleanParams(params) })).data
}

export async function fetchAiSummary(date: string | null): Promise<AiSummary> {
  return (await httpClient.get<AiSummary>('/admin/ai/summary', { params: cleanParams({ date: date ?? undefined }) })).data
}
