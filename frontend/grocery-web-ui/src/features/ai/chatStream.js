import { CORRELATION_ID_HEADER, newCorrelationId } from '../../shared/api/correlation'
import { ApiError, isProblemDetail } from '../../shared/api/problemDetail'
import { getAccessToken, markSuspended, refreshSession } from '../../shared/auth/session'
import { createSseParser } from '../../shared/realtime/sseParser'
import { chatUrl } from './api'
import { toPageLinks } from './pageLinks'

/** @typedef {import('../../shared/api/problemDetail').ProblemDetail} ProblemDetail */
/** @typedef {import('../../shared/realtime/sseParser').SseEvent} SseEvent */
/** @typedef {import('./api').ApprovalView} ApprovalView */
/** @typedef {import('./api').KnowledgeSource} KnowledgeSource */
/** @typedef {import('./api').ToolActivity} ToolActivity */

/**
 * The streamed chat (contract §2): `POST /api/ai/agents/{agent}/chat` answers `text/event-stream`. `EventSource`
 * can neither POST nor send `Authorization`, so — like the notification stream (`shared/realtime/sseClient.js`) —
 * the body of a `fetch` is read and fed to the shared incremental SSE parser. Unlike that stream this one is a
 * single request: no reconnects, no Last-Event-ID. It is cancelled with an AbortSignal.
 *
 * - `Authorization: Bearer <current access token>`; `401` → the app's shared refresh, then one retry.
 * - A non-2xx answer rejects with the app's `ApiError` (ProblemDetail: `AI_RATE_LIMITED`, `CONVERSATION_NOT_FOUND`, …).
 * - Each event is handed over as soon as its block is complete, so tokens render while the answer is written.
 */

/**
 * One parsed stream event.
 * @typedef {(
 *   | { type: 'conversation', conversationId: string, agent: string | null }
 *   | { type: 'tool', activity: ToolActivity }
 *   | { type: 'approval', approval: ApprovalView }
 *   | { type: 'token', text: string }
 *   | { type: 'done', messageId: string | null, finishReason: string | null }
 *   | { type: 'error', code: string, message: string }
 *   | { type: 'notice', code: string, message: string }
 *   | { type: 'retry', code: string, message: string }
 *   | { type: 'sources', sources: KnowledgeSource[] }
 * )} ChatStreamEvent
 */

/**
 * How the turn ended: `done` / `error` (the server's last event), `aborted` (cancelled here) or `interrupted`
 * (the stream ended without either — a dropped connection).
 * @typedef {'done' | 'error' | 'aborted' | 'interrupted'} ChatOutcome
 */

/**
 * @typedef {object} StreamChatOptions
 * @property {string} agent
 * @property {string | null} [conversationId]
 * @property {string} message
 * @property {(event: ChatStreamEvent) => void} onEvent
 * @property {AbortSignal} [signal]
 * @property {() => string | null} [getAccessToken] Defaults: the session's in-memory token and shared refresh.
 * @property {() => Promise<unknown>} [refreshAccessToken]
 * @property {(message: string | null) => void} [onAccountSuspended] `403 ACCOUNT_SUSPENDED`; defaults to the session's suspended flow.
 * @property {(input: string, init: RequestInit) => Promise<Response>} [fetch] Defaults to the global `fetch`, looked up per call.
 */

/** The friendly text for a stream `error` without a message of its own. */
export const DEFAULT_STREAM_ERROR_MESSAGE = 'The assistant is not available right now. Everything else works as usual.'

/** An answer stating figures that no tool returned (live `notice` UNVERIFIED_FIGURES, or `unverified` in history). */
export const UNVERIFIED_ANSWER_MESSAGE =
  "This answer was not checked against the app's data: no information was looked up for it. Ask again, or open the related page to see the real figures."

/** The live `retry` event's text: the answer streamed so far is being checked again. */
export const RETRY_MESSAGE = 'Checking that with the app…'

/**
 * @param {string} json
 * @returns {Record<string, unknown> | null}
 */
function parseObject(json) {
  try {
    const value = JSON.parse(json)
    return typeof value === 'object' && value !== null && !Array.isArray(value) ? value : null
  } catch {
    return null
  }
}

/**
 * @param {unknown} value
 * @returns {string | null}
 */
function text(value) {
  return typeof value === 'string' && value.length > 0 ? value : null
}

/**
 * @param {Record<string, unknown>} raw
 * @returns {ToolActivity | null}
 */
function toToolActivity(raw) {
  const tool = text(raw.tool)
  const status = text(raw.status)
  if (!tool || !status) return null
  return {
    invocationId: text(raw.invocationId) ?? `${tool}-${status}`,
    tool,
    label: text(raw.label) ?? tool,
    status,
    summary: text(raw.summary),
    links: toPageLinks(raw.links),
  }
}

/**
 * @param {Record<string, unknown>} raw
 * @returns {ApprovalView | null}
 */
function toApproval(raw) {
  if (!text(raw.id) || !text(raw.status)) return null
  const summary = typeof raw.summary === 'object' && raw.summary !== null ? raw.summary : {}
  return {
    ...raw,
    approverRoles: Array.isArray(raw.approverRoles) ? raw.approverRoles : [],
    summary: {
      title: text(summary.title) ?? 'Approval request',
      lines: Array.isArray(summary.lines) ? summary.lines : [],
      amount: typeof summary.amount === 'number' ? summary.amount : null,
      currency: text(summary.currency),
    },
  }
}

/**
 * One SSE event → a typed chat event, or null (unknown event type or malformed `data`: ignored, never thrown).
 * @param {SseEvent} event
 * @returns {ChatStreamEvent | null}
 */
export function parseChatEvent(event) {
  const raw = parseObject(event.data)
  if (!raw) return null
  switch (event.type) {
    case 'conversation': {
      const conversationId = text(raw.conversationId)
      return conversationId ? { type: 'conversation', conversationId, agent: text(raw.agent) } : null
    }
    case 'tool': {
      const activity = toToolActivity(raw)
      return activity ? { type: 'tool', activity } : null
    }
    case 'approval': {
      const approval = toApproval(raw)
      return approval ? { type: 'approval', approval } : null
    }
    case 'token':
      return typeof raw.text === 'string' ? { type: 'token', text: raw.text } : null
    case 'done':
      return { type: 'done', messageId: text(raw.messageId), finishReason: text(raw.finishReason) }
    case 'error':
      return {
        type: 'error',
        code: text(raw.code) ?? 'AI_ERROR',
        message: text(raw.message) ?? DEFAULT_STREAM_ERROR_MESSAGE,
      }
    case 'notice': {
      const code = text(raw.code)
      return code ? { type: 'notice', code, message: text(raw.message) ?? UNVERIFIED_ANSWER_MESSAGE } : null
    }
    case 'retry':
      return { type: 'retry', code: text(raw.code) ?? 'RETRY', message: text(raw.message) ?? RETRY_MESSAGE }
    case 'sources': {
      const sources = toSources(raw.sources)
      return sources.length > 0 ? { type: 'sources', sources } : null
    }
    default:
      return null
  }
}

/**
 * The well-formed entries of a `sources` list (others are left out).
 * @param {unknown} value
 * @returns {KnowledgeSource[]}
 */
export function toSources(value) {
  if (!Array.isArray(value)) return []
  return value.flatMap((entry) => {
    if (typeof entry !== 'object' || entry === null) return []
    const documentId = text(entry.documentId)
    const title = text(entry.title)
    return documentId && title ? [{ documentId, title, category: text(entry.category) ?? '' }] : []
  })
}

/**
 * @param {unknown} error
 * @returns {boolean}
 */
function isAbort(error) {
  return error instanceof DOMException
    ? error.name === 'AbortError'
    : error instanceof Error && error.name === 'AbortError'
}

/**
 * @param {Response} response
 * @returns {Promise<ProblemDetail | null>}
 */
async function readProblem(response) {
  try {
    const body = JSON.parse(await response.text())
    return isProblemDetail(body) ? body : null
  } catch {
    return null
  }
}

/**
 * Seconds of a `Retry-After` header (429), or null.
 * @param {Response} response
 * @returns {number | null}
 */
function retryAfterSeconds(response) {
  const value = response.headers.get('retry-after')
  const seconds = value === null ? Number.NaN : Number(value)
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null
}

/**
 * @param {Response} response
 * @param {string} correlationId
 * @returns {Promise<ApiError>}
 */
async function httpError(response, correlationId) {
  const problem = await readProblem(response)
  const retryAfter = retryAfterSeconds(response)
  const withRetry =
    problem && retryAfter !== null && problem.retryAfter === undefined ? { ...problem, retryAfter } : problem
  const message = problem?.detail ?? problem?.title ?? `Request failed with status ${response.status}.`
  return new ApiError(message, {
    kind: 'http',
    status: response.status,
    problem: withRetry ?? (retryAfter !== null ? { retryAfter } : null),
    correlationId:
      response.headers.get(CORRELATION_ID_HEADER) ??
      (typeof problem?.correlationId === 'string' ? problem.correlationId : correlationId),
  })
}

/**
 * @param {Response} response
 * @returns {void}
 */
function discardBody(response) {
  response.body?.cancel().catch(() => undefined)
}

/**
 * Sends one message and reads the answer's events until the stream ends. Resolves with how it ended; rejects with
 * an `ApiError` when the request itself was refused (HTTP error, network failure).
 * @param {StreamChatOptions} options
 * @returns {Promise<ChatOutcome>}
 */
export async function streamChat(options) {
  const readToken = options.getAccessToken ?? getAccessToken
  const refresh = options.refreshAccessToken ?? refreshSession
  const fetchImpl = options.fetch ?? ((input, init) => globalThis.fetch(input, init))
  const correlationId = newCorrelationId()
  const body = JSON.stringify(
    options.conversationId
      ? { conversationId: options.conversationId, message: options.message }
      : { message: options.message },
  )

  async function send(token) {
    const headers = {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
      [CORRELATION_ID_HEADER]: correlationId,
    }
    if (token) headers.Authorization = `Bearer ${token}`
    return fetchImpl(chatUrl(options.agent), {
      method: 'POST',
      headers,
      body,
      signal: options.signal,
      cache: 'no-store',
      credentials: 'same-origin',
    })
  }

  let response
  try {
    const token = readToken()
    response = await send(token)
    if (response.status === 401 && token !== null) {
      discardBody(response)
      // Another request may have renewed the token meanwhile; otherwise join the shared refresh.
      if (readToken() === token) await refresh()
      response = await send(readToken())
    }
  } catch (error) {
    if (isAbort(error) || options.signal?.aborted) return 'aborted'
    if (error instanceof ApiError) throw error
    throw new ApiError('Cannot reach the assistant. Check your connection and try again.', {
      kind: 'network',
      status: null,
      problem: null,
      correlationId,
      cause: error,
    })
  }

  if (!response.ok) {
    const error = await httpError(response, correlationId)
    if (error.status === 403 && error.errorCode === 'ACCOUNT_SUSPENDED')
      (options.onAccountSuspended ?? markSuspended)(error.problem?.detail ?? null)
    throw error
  }
  const contentType = (response.headers.get('content-type') ?? '').toLowerCase()
  if (!response.body || !contentType.includes('text/event-stream')) {
    discardBody(response)
    throw new ApiError('The assistant sent an unexpected answer.', {
      kind: 'unknown',
      status: response.status,
      problem: null,
      correlationId,
    })
  }

  /** Set by the last event (`done` / `error`); later bytes are ignored. */
  const turn = { outcome: null }
  const parser = createSseParser({
    onEvent: (sseEvent) => {
      if (turn.outcome !== null) return
      const event = parseChatEvent(sseEvent)
      if (!event) return
      if (event.type === 'done' || event.type === 'error') turn.outcome = event.type
      try {
        options.onEvent(event)
      } catch {
        // A failing handler must never break the stream.
      }
    },
  })

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      parser.push(decoder.decode(value, { stream: true }))
      if (turn.outcome !== null) break
    }
    parser.push(decoder.decode())
  } catch (error) {
    if (turn.outcome !== null) return turn.outcome
    return isAbort(error) || options.signal?.aborted ? 'aborted' : 'interrupted'
  } finally {
    reader.cancel().catch(() => undefined)
  }
  if (turn.outcome === null && options.signal?.aborted) return 'aborted'
  return turn.outcome ?? 'interrupted'
}
