import { isAxiosError } from 'axios'
import { httpClient } from '../../shared/api/httpClient'
import { ApiError, isProblemDetail } from '../../shared/api/problemDetail'
import { cleanParams, segment } from '../../shared/api/types'
import { isAdminRole } from '../../shared/auth/roles'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../../shared/auth/roles').RoleArea} RoleArea */

/**
 * Phase 11 — agentic AI and human approval (ai-orchestrator-service, contract
 * `docs/api/phase-11-agentic-ai-and-approvals.md`). Chat answers are streamed (`chatStream.js`); everything else
 * is plain REST through the shared client.
 */

/**
 * `shopping` (CUSTOMER) and `admin-bi` (any admin role); Phase 12 adds `vendor` (VENDOR), `restaurant`
 * (RESTAURANT_OWNER) and `delivery` (DELIVERY_PARTNER, DELIVERY_MANAGER); customers also have `payment` and `support`
 * (docs/api/payment-and-support-agents.md) and switch between their three assistants in the panel.
 * @typedef {(
 *   | 'shopping'
 *   | 'admin-bi'
 *   | 'vendor'
 *   | 'restaurant'
 *   | 'delivery'
 *   | 'payment'
 *   | 'support'
 *   | (string & {})
 * )} AgentId
 */

/**
 * `GET /api/ai/agents` — only the agents the caller may use.
 * @typedef {object} AgentView
 * @property {AgentId} id
 * @property {string} name
 * @property {string} description
 * @property {string[]} examplePrompts
 */

/**
 * The agent each area is meant to use; the server's list decides whether the user may.
 * @type {Partial<Record<RoleArea, string>>}
 */
const PREFERRED_AGENT = {
  CUSTOMER: 'shopping',
  ADMIN: 'admin-bi',
  VENDOR: 'vendor',
  RESTAURANT_OWNER: 'restaurant',
  DELIVERY_PARTNER: 'delivery',
}

/**
 * @typedef {object} PickAgentOptions
 * @property {readonly string[]} [roles] The user's roles: a delivery manager without another admin role starts with the `delivery` agent.
 * @property {string | null} [chosen] The agent the user switched to in the panel (when the server offers more than one).
 */

/**
 * The chosen agent, else the area's own agent when the server offers it, else the first one it offers (null: no assistant).
 * @param {readonly AgentView[] | undefined} agents
 * @param {RoleArea | null} area
 * @param {PickAgentOptions} [options={}]
 * @returns {AgentView | null}
 */
export function pickAgent(agents, area, options = {}) {
  if (!agents || agents.length === 0) return null
  const chosen = options.chosen ? agents.find((agent) => agent.id === options.chosen) : undefined
  if (chosen) return chosen
  const roles = options.roles ?? []
  const deliveryManagerOnly =
    area === 'ADMIN' &&
    roles.includes('DELIVERY_MANAGER') &&
    !roles.some((role) => role !== 'DELIVERY_MANAGER' && isAdminRole(role))
  const preferred = deliveryManagerOnly ? 'delivery' : area ? PREFERRED_AGENT[area] : undefined
  return (
    agents.find((agent) => agent.id === preferred) ??
    agents.find((agent) => agent.id === (area ? PREFERRED_AGENT[area] : undefined)) ??
    agents[0] ??
    null
  )
}

/**
 * `GET /api/ai/status` — the model probe (cached 30 s by the server).
 * @typedef {object} AiStatus
 * @property {string} provider
 * @property {string} model
 * @property {boolean} available
 * @property {string} checkedAt
 */

export const TOOL_STATUSES = ['STARTED', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'APPROVAL_REQUIRED']
/** @typedef {(typeof TOOL_STATUSES)[number]} ToolStatus */

/**
 * A page of this app an answer points to: "View my cart" → `/cart` (server: `link/Pages.java`).
 * @typedef {object} PageLink
 * @property {string} label
 * @property {string} path An app path with its query string, e.g. `/search?q=rice`.
 */

/**
 * `tool` stream event and `messages[].tools[]` — one tool call as the chat shows it.
 * @typedef {object} ToolActivity
 * @property {string} invocationId
 * @property {string} tool The tool's name (`addToCart`).
 * @property {string} label Human-readable, from the server ("Searched products").
 * @property {ToolStatus | (string & {})} status
 * @property {string | null} summary
 * @property {PageLink[]} [links] Pages that show what a successful call found (buttons under the answer); absent from older servers.
 */

export const APPROVAL_STATUSES = [
  'PENDING_APPROVAL',
  'APPROVED',
  'EXECUTING',
  'COMPLETED',
  'FAILED',
  'REJECTED',
  'EXPIRED',
  'CANCELLED',
]
/** @typedef {(typeof APPROVAL_STATUSES)[number]} ApprovalStatus */

/**
 * @param {unknown} value
 * @returns {value is ApprovalStatus}
 */
export function isApprovalStatus(value) {
  return typeof value === 'string' && APPROVAL_STATUSES.includes(value)
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
]
/** @typedef {(typeof APPROVAL_ACTIONS)[number]} ApprovalAction */

/**
 * Decided by the customer the action is for (their own purchase): "Confirm" / "Not now".
 * @type {readonly string[]}
 */
export const CUSTOMER_ACTIONS = ['PLACE_ORDER', 'CANCEL_ORDER']

/**
 * @param {string} action
 * @returns {boolean}
 */
export function isCustomerAction(action) {
  return CUSTOMER_ACTIONS.includes(action)
}

/**
 * Phase 12: decided by the vendor or restaurant owner who asked for it (`approverUserId` = requester): "Confirm" / "Not now".
 * @type {readonly string[]}
 */
export const STORE_SELF_ACTIONS = [
  'ADJUST_STOCK',
  'CHANGE_PRICE',
  'SEND_PURCHASE_ORDER',
  'SET_MENU_ITEM_AVAILABILITY',
  'ACCEPT_STORE_ORDER',
  'REJECT_STORE_ORDER',
]

/**
 * The person who asked decides it themselves (customer or store actions) — never an admin.
 * @param {string} action
 * @returns {boolean}
 */
export function isSelfDecidedAction(action) {
  return isCustomerAction(action) || action === 'REQUEST_RETURN' || STORE_SELF_ACTIONS.includes(action)
}

/**
 * @typedef {object} ApprovalSummaryLine
 * @property {string} label
 * @property {string} value
 */

/**
 * Rendered by the server from the stored parameters — never by the model.
 * @typedef {object} ApprovalSummary
 * @property {string} title
 * @property {ApprovalSummaryLine[]} lines
 * @property {number | null} amount
 * @property {string | null} currency
 */

/**
 * Why the target refused the approved action (`TOTAL_CHANGED`, `CART_CHANGED`, `APPROVAL_BODY_MISMATCH`, …).
 * @typedef {object} ApprovalFailure
 * @property {string} code
 * @property {string} message
 * @property {number | null} httpStatus
 */

/**
 * `ApprovalView` (contract §4).
 * @typedef {object} ApprovalView
 * @property {string} id
 * @property {ApprovalAction | (string & {})} action
 * @property {ApprovalStatus | (string & {})} status
 * @property {AgentId} agent
 * @property {string | null} conversationId
 * @property {string} requestedBy
 * @property {string | null} onBehalfOf The customer the action is for (absent for admin actions).
 * @property {string | null} approverUserId A specific decider (the customer), or null when roles decide.
 * @property {string[]} approverRoles
 * @property {boolean} fourEyes The requester may not decide it.
 * @property {ApprovalSummary} summary
 * @property {string | null} rationale The model's own explanation ("The assistant's note"), ≤ 500.
 * @property {string} fingerprint First 8 hex characters of the SHA-256 of the exact request body.
 * @property {boolean} canDecide The caller may approve or reject it now.
 * @property {string} createdAt
 * @property {string} expiresAt
 * @property {string | null} decidedBy
 * @property {string | null} decidedAt
 * @property {string | null} comment
 * @property {string | null} executedAt
 * @property {Record<string, unknown> | null} result COMPLETED: what the target answered, e.g. `{ orderId, orderNumber }`.
 * @property {ApprovalFailure | null} failure
 */

/** @typedef {'USER' | 'ASSISTANT'} MessageRole */

/**
 * @typedef {object} ConversationMessage
 * @property {string} id
 * @property {MessageRole | (string & {})} role
 * @property {string} content
 * @property {string} createdAt
 * @property {ToolActivity[]} tools
 * @property {ApprovalView[]} approvals
 * @property {boolean} [unverified] An answer that stated figures no tool returned (not checked against the app's data); absent from older servers.
 */

/**
 * @typedef {object} ConversationSummary
 * @property {string} id
 * @property {AgentId} agent
 * @property {string} title The first message, ≤ 80 characters.
 * @property {string} createdAt
 * @property {string} updatedAt
 */

/**
 * @typedef {object} ConversationDetail
 * @property {string} id
 * @property {AgentId} agent
 * @property {string} title
 * @property {ConversationMessage[]} messages
 */

/**
 * `InsightReport` (contract §6) — the model's structured output, or the rules fallback (`provider: "rules"`).
 * @typedef {object} InsightKpi
 * @property {string} name
 * @property {string | number} value
 * @property {string | null} [note]
 */

/**
 * @typedef {object} InsightReport
 * @property {string} date
 * @property {string} headline
 * @property {InsightKpi[]} kpis
 * @property {string[]} observations
 * @property {string[]} risks
 * @property {string[]} suggestedActions
 * @property {{ provider: string, model: string | null }} generatedBy
 * @property {string} generatedAt
 */

/** The provider name of the rule-based fallback report. */
export const RULES_PROVIDER = 'rules'

/** Audited statuses of a tool call (STARTED is never stored). */
export const INVOCATION_STATUSES = ['SUCCEEDED', 'FAILED', 'BLOCKED', 'APPROVAL_REQUIRED']
/** @typedef {(typeof INVOCATION_STATUSES)[number]} InvocationStatus */

/**
 * One row of `ai_tool_invocation` (contract §3 "Every call is audited"). Field names follow the contract's
 * column list; `tool` is null for a blocked agent access (`AGENT_NOT_ALLOWED`).
 * @typedef {object} ToolInvocationView
 * @property {string} id
 * @property {string | null} conversationId
 * @property {string} userId
 * @property {string[]} roles
 * @property {AgentId} agent
 * @property {string | null} tool
 * @property {unknown} arguments The model's arguments (JSON object, or its text).
 * @property {InvocationStatus | (string & {})} status
 * @property {string | null} reason BLOCKED: `TOOL_NOT_ALLOWED`, `ROLE_NOT_ALLOWED`, `INVALID_ARGUMENTS`, `AGENT_NOT_ALLOWED`; FAILED: the API's code.
 * @property {string | null} resultSummary ≤ 500.
 * @property {number | null} httpStatus
 * @property {number | null} latencyMs
 * @property {string | null} approvalId
 * @property {string} createdAt
 */

/**
 * @typedef {object} ToolInvocationParams
 * @property {string} [agent]
 * @property {string} [userId]
 * @property {string} [tool]
 * @property {string} [status]
 * @property {string} [from] Days (`YYYY-MM-DD`, India time), inclusive.
 * @property {string} [to]
 * @property {number} [page]
 * @property {number} [size]
 */

/**
 * `GET /api/admin/ai/summary?date=` (contract §6).
 * @typedef {object} AiSummary
 * @property {string} date
 * @property {number} conversations
 * @property {number} messages
 * @property {number} toolCalls
 * @property {number} blocked
 * @property {number} failed
 * @property {{ requested: number, completed: number, rejected: number, expired: number, failed: number, cancelled: number, pending: number }} approvals
 * @property {number} modelErrors
 * @property {number | null} avgModelLatencyMs
 * @property {string} provider
 * @property {string | null} model
 * @property {boolean} available
 */

/** @typedef {'mine' | 'to-decide'} ApprovalScope */

/**
 * @typedef {object} ApprovalListParams
 * @property {ApprovalScope} [scope] `mine` (default) = requested by me or for me; `to-decide` = PENDING_APPROVAL ones I may decide.
 * @property {ApprovalStatus} [status]
 * @property {number} [page]
 * @property {number} [size]
 */

export const AI_LIMITS = { message: 2000, comment: 300 }

export const AI_ERRORS = {
  agentNotFound: 'AGENT_NOT_FOUND',
  agentNotAllowed: 'AGENT_NOT_ALLOWED',
  conversationNotFound: 'CONVERSATION_NOT_FOUND',
  rateLimited: 'AI_RATE_LIMITED',
  approvalNotFound: 'APPROVAL_NOT_FOUND',
  approvalForbidden: 'APPROVAL_FORBIDDEN',
  fourEyesRequired: 'FOUR_EYES_REQUIRED',
  approvalNotPending: 'APPROVAL_NOT_PENDING',
}

/** `error` stream event codes. */
export const AI_STREAM_ERRORS = ['AI_UNAVAILABLE', 'AI_TIMEOUT', 'AI_TOOL_LIMIT', 'AI_ERROR']

export const aiKeys = {
  all: ['ai'],
  agents: () => [...aiKeys.all, 'agents'],
  status: () => [...aiKeys.all, 'status'],
  conversations: () => [...aiKeys.all, 'conversations'],
  conversationList: (agent) => [...aiKeys.conversations(), 'list', agent],
  conversation: (id) => [...aiKeys.conversations(), 'detail', id],
  voiceStatus: () => [...aiKeys.all, 'voice-status'],
  admin: () => [...aiKeys.all, 'admin'],
  invocations: (params) => [...aiKeys.admin(), 'invocations', params],
  summaries: () => [...aiKeys.admin(), 'summary'],
  summary: (date) => [...aiKeys.summaries(), date],
}

/** Approval requests (lists and single ones, including the cards inside the chat). */
export const approvalKeys = {
  all: ['ai-approvals'],
  lists: () => [...approvalKeys.all, 'list'],
  list: (params) => [...approvalKeys.lists(), params],
  details: () => [...approvalKeys.all, 'detail'],
  detail: (id) => [...approvalKeys.details(), id],
}

/**
 * The chat endpoint (streamed — read with `streamChat`, not axios).
 * @param {string} agent
 * @returns {string}
 */
export function chatUrl(agent) {
  return `/api/ai/agents/${segment(agent)}/chat`
}

/** @returns {Promise<AgentView[]>} */
export async function fetchAgents() {
  const data = (await httpClient.get('/ai/agents')).data
  return Array.isArray(data)
    ? data.map((agent) => ({
        ...agent,
        examplePrompts: Array.isArray(agent.examplePrompts) ? agent.examplePrompts : [],
      }))
    : []
}

/** @returns {Promise<AiStatus>} */
export async function fetchAiStatus() {
  return (await httpClient.get('/ai/status')).data
}

/**
 * Own conversations of one agent, newest first.
 * @param {string} agent
 * @param [page=0]
 * @param [size=20]
 * @returns {Promise<PageResponse<ConversationSummary>>}
 */
export async function listConversations(agent, page = 0, size = 20) {
  return (await httpClient.get('/ai/conversations', { params: { agent, page, size } })).data
}

/**
 * 404 CONVERSATION_NOT_FOUND.
 * @param {string} id
 * @returns {Promise<ConversationDetail>}
 */
export async function fetchConversation(id) {
  const data = (await httpClient.get(`/ai/conversations/${segment(id)}`)).data
  return {
    ...data,
    messages: (data.messages ?? []).map((message) => ({
      ...message,
      tools: message.tools ?? [],
      approvals: message.approvals ?? [],
    })),
  }
}

/**
 * 204 — messages and memory are deleted; the audit and the approvals are kept.
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function deleteConversation(id) {
  await httpClient.delete(`/ai/conversations/${segment(id)}`)
}

/**
 * Only the owner may rename; the server trims the title and accepts 1–80 characters (400 VALIDATION_FAILED).
 * @param {string} id
 * @param {string} title
 * @returns {Promise<ConversationSummary>}
 */
export async function renameConversation(id, title) {
  return (await httpClient.patch(`/ai/conversations/${segment(id)}`, { title })).data
}

// ---- voice -------------------------------------------------------------------------------------------------------

/**
 * Who handles voice (`GET /api/ai/voice/status`): `SERVER` — record here, the server turns speech into text and text
 * into speech; `BROWSER` — use the browser's own speech recognition and synthesis; `OFF` — no voice.
 * @typedef {'SERVER' | 'BROWSER' | 'OFF'} VoiceMode
 */

/**
 * @typedef {object} VoiceStatus
 * @property {VoiceMode} mode
 * @property {boolean} transcription The server transcribes recordings (`SERVER` only).
 * @property {boolean} speech The server reads answers aloud (`SERVER` only).
 * @property {number} maxRecordingSeconds
 * @property {number} maxAudioBytes
 * @property {number} maxSpeechCharacters
 */

/** @type {VoiceStatus} */
export const VOICE_OFF = {
  mode: 'OFF',
  transcription: false,
  speech: false,
  maxRecordingSeconds: 60,
  maxAudioBytes: 10_485_760,
  maxSpeechCharacters: 4000,
}

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
}

/** @returns {Promise<VoiceStatus>} */
export async function fetchVoiceStatus() {
  const data = (await httpClient.get('/ai/voice/status')).data
  return data && (data.mode === 'SERVER' || data.mode === 'BROWSER' || data.mode === 'OFF')
    ? { ...VOICE_OFF, ...data }
    : VOICE_OFF
}

/**
 * A recording's file name for the upload; the server checks the real format from the bytes.
 * @param {string} type
 * @returns {string}
 */
function recordingName(type) {
  if (type.includes('ogg')) return 'question.ogg'
  if (type.includes('mp4') || type.includes('aac')) return 'question.m4a'
  if (type.includes('wav')) return 'question.wav'
  return 'question.webm'
}

/**
 * Speech to text (`SERVER` mode): the recognised words, to put into the chat input.
 * @param {Blob} audio
 * @param {AbortSignal} [signal]
 * @returns {Promise<string>}
 */
export async function transcribeAudio(audio, signal) {
  const form = new FormData()
  form.append('audio', audio, recordingName(audio.type))
  const data = (await httpClient.post('/ai/voice/transcriptions', form, { signal, timeout: 90_000 })).data
  return typeof data?.text === 'string' ? data.text : ''
}

/**
 * Text to speech (`SERVER` mode): MP3 audio of an answer (markdown is read as plain words by the server).
 * @param {string} text
 * @param {AbortSignal} [signal]
 * @returns {Promise<Blob>}
 */
export async function synthesizeSpeech(text, signal) {
  try {
    const response = await httpClient.post(
      '/ai/voice/speech',
      { text },
      { signal, timeout: 90_000, responseType: 'blob', headers: { Accept: 'audio/mpeg, application/problem+json' } },
    )
    return response.data
  } catch (error) {
    throw await withBlobProblem(error)
  }
}

/**
 * An error answer to a `blob` request arrives as a Blob: read its ProblemDetail, so the `errorCode` is known.
 * @param {unknown} error
 * @returns {Promise<unknown>}
 */
async function withBlobProblem(error) {
  if (!(error instanceof ApiError) || error.problem || !isAxiosError(error.cause)) return error
  const data = error.cause.response?.data
  if (!(data instanceof Blob)) return error
  try {
    const parsed = JSON.parse(await data.text())
    if (isProblemDetail(parsed)) {
      return new ApiError(parsed.detail ?? error.message, {
        kind: error.kind,
        status: error.status,
        problem: parsed,
        correlationId: error.correlationId,
        cause: error.cause,
      })
    }
  } catch {
    // not JSON: keep the error as it is
  }
  return error
}

/**
 * The server gathers the day's data itself; the model may take a while on CPU (120 s), then rules take over.
 * @param {string} [date]
 * @returns {Promise<InsightReport>}
 */
export async function generateInsights(date) {
  return (await httpClient.post('/ai/agents/admin-bi/insights', cleanParams({ date }), { timeout: 150_000 })).data
}

/**
 * @param {ApprovalListParams} params
 * @returns {Promise<PageResponse<ApprovalView>>}
 */
export async function listApprovals(params) {
  return (await httpClient.get('/approvals', { params: cleanParams(params) })).data
}

/**
 * 404 APPROVAL_NOT_FOUND.
 * @param {string} id
 * @returns {Promise<ApprovalView>}
 */
export async function fetchApproval(id) {
  return (await httpClient.get(`/approvals/${segment(id)}`)).data
}

/**
 * Runs the action synchronously with the approver's authority; the answer is COMPLETED (with `result`) or FAILED
 * (with `failure`). 403 APPROVAL_FORBIDDEN / FOUR_EYES_REQUIRED, 409 APPROVAL_NOT_PENDING (`currentStatus`).
 * @param {string} id
 * @param {string} [comment]
 * @returns {Promise<ApprovalView>}
 */
export async function approveApproval(id, comment) {
  return (await httpClient.post(`/approvals/${segment(id)}/approve`, cleanParams({ comment }), { timeout: 60_000 }))
    .data
}

/**
 * Comment required, ≤ 300 → REJECTED.
 * @param {string} id
 * @param {string} comment
 * @returns {Promise<ApprovalView>}
 */
export async function rejectApproval(id, comment) {
  return (await httpClient.post(`/approvals/${segment(id)}/reject`, { comment })).data
}

/**
 * The requester (or the customer it is for) → CANCELLED.
 * @param {string} id
 * @returns {Promise<ApprovalView>}
 */
export async function cancelApproval(id) {
  return (await httpClient.post(`/approvals/${segment(id)}/cancel`)).data
}

/**
 * @param {ToolInvocationParams} params
 * @returns {Promise<PageResponse<ToolInvocationView>>}
 */
export async function listToolInvocations(params) {
  return (await httpClient.get('/admin/ai/tool-invocations', { params: cleanParams(params) })).data
}

/**
 * @param {string | null} date
 * @returns {Promise<AiSummary>}
 */
export async function fetchAiSummary(date) {
  return (await httpClient.get('/admin/ai/summary', { params: cleanParams({ date: date ?? undefined }) })).data
}
