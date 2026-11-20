import { CORRELATION_ID_HEADER, newCorrelationId } from '../../shared/api/correlation'
import { ApiError, isProblemDetail, type ProblemDetail } from '../../shared/api/problemDetail'
import { getAccessToken, markSuspended, refreshSession } from '../../shared/auth/session'
import { createSseParser, type SseEvent } from '../../shared/realtime/sseParser'
import { type ApprovalView, chatUrl, type ToolActivity } from './api'
import { toPageLinks } from './pageLinks'

/**
 * The streamed chat (contract §2): `POST /api/ai/agents/{agent}/chat` answers `text/event-stream`. `EventSource`
 * can neither POST nor send `Authorization`, so — like the notification stream (`shared/realtime/sseClient.ts`) —
 * the body of a `fetch` is read and fed to the shared incremental SSE parser. Unlike that stream this one is a
 * single request: no reconnects, no Last-Event-ID. It is cancelled with an AbortSignal.
 *
 * - `Authorization: Bearer <current access token>`; `401` → the app's shared refresh, then one retry.
 * - A non-2xx answer rejects with the app's `ApiError` (ProblemDetail: `AI_RATE_LIMITED`, `CONVERSATION_NOT_FOUND`, …).
 * - Each event is handed over as soon as its block is complete, so tokens render while the answer is written.
 */

/** One parsed stream event. */
export type ChatStreamEvent =
  | { type: 'conversation'; conversationId: string; agent: string | null }
  | { type: 'tool'; activity: ToolActivity }
  | { type: 'approval'; approval: ApprovalView }
  | { type: 'token'; text: string }
  | { type: 'done'; messageId: string | null; finishReason: string | null }
  | { type: 'error'; code: string; message: string }
  /** Something to know about the complete answer, e.g. `UNVERIFIED_FIGURES` (figures no tool returned). */
  | { type: 'notice'; code: string; message: string }
  /**
   * The text streamed so far is dropped: the model answered without looking anything up although it had to (`ACTION`,
   * `UNSEEN` or `FIGURES`) and is asked once more. The new answer's events follow.
   */
  | { type: 'retry'; code: string; message: string }

/**
 * How the turn ended: `done` / `error` (the server's last event), `aborted` (cancelled here) or `interrupted`
 * (the stream ended without either — a dropped connection).
 */
export type ChatOutcome = 'done' | 'error' | 'aborted' | 'interrupted'

export interface StreamChatOptions {
  agent: string
  conversationId?: string | null
  message: string
  onEvent: (event: ChatStreamEvent) => void
  signal?: AbortSignal
  /** Defaults: the session's in-memory token and shared refresh. */
  getAccessToken?: () => string | null
  refreshAccessToken?: () => Promise<unknown>
  /** `403 ACCOUNT_SUSPENDED`; defaults to the session's suspended flow. */
  onAccountSuspended?: (message: string | null) => void
  /** Defaults to the global `fetch`, looked up per call. */
  fetch?: (input: string, init: RequestInit) => Promise<Response>
}

/** The friendly text for a stream `error` without a message of its own. */
export const DEFAULT_STREAM_ERROR_MESSAGE = 'The assistant is not available right now. Everything else works as usual.'

/** An answer stating figures that no tool returned (live `notice` UNVERIFIED_FIGURES, or `unverified` in history). */
export const UNVERIFIED_ANSWER_MESSAGE =
  "This answer was not checked against the app's data: no information was looked up for it. Ask again, or open the related page to see the real figures."

/** The live `retry` event's text: the answer streamed so far is being checked again. */
export const RETRY_MESSAGE = 'Checking that with the app…'

function parseObject(json: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(json)
    return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

function toToolActivity(raw: Record<string, unknown>): ToolActivity | null {
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

function toApproval(raw: Record<string, unknown>): ApprovalView | null {
  if (!text(raw.id) || !text(raw.status)) return null
  const summary = typeof raw.summary === 'object' && raw.summary !== null ? (raw.summary as Record<string, unknown>) : {}
  return {
    ...(raw as unknown as ApprovalView),
    approverRoles: Array.isArray(raw.approverRoles) ? (raw.approverRoles as string[]) : [],
    summary: {
      title: text(summary.title) ?? 'Approval request',
      lines: Array.isArray(summary.lines) ? (summary.lines as ApprovalView['summary']['lines']) : [],
      amount: typeof summary.amount === 'number' ? summary.amount : null,
      currency: text(summary.currency),
    },
  }
}

/** One SSE event → a typed chat event, or null (unknown event type or malformed `data`: ignored, never thrown). */
export function parseChatEvent(event: SseEvent): ChatStreamEvent | null {
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
      return { type: 'error', code: text(raw.code) ?? 'AI_ERROR', message: text(raw.message) ?? DEFAULT_STREAM_ERROR_MESSAGE }
    case 'notice': {
      const code = text(raw.code)
      return code ? { type: 'notice', code, message: text(raw.message) ?? UNVERIFIED_ANSWER_MESSAGE } : null
    }
    case 'retry':
      return { type: 'retry', code: text(raw.code) ?? 'RETRY', message: text(raw.message) ?? RETRY_MESSAGE }
    default:
      return null
  }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException ? error.name === 'AbortError' : error instanceof Error && error.name === 'AbortError'
}

async function readProblem(response: Response): Promise<ProblemDetail | null> {
  try {
    const body: unknown = JSON.parse(await response.text())
    return isProblemDetail(body) ? body : null
  } catch {
    return null
  }
}

/** Seconds of a `Retry-After` header (429), or null. */
function retryAfterSeconds(response: Response): number | null {
  const value = response.headers.get('retry-after')
  const seconds = value === null ? Number.NaN : Number(value)
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null
}

async function httpError(response: Response, correlationId: string): Promise<ApiError> {
  const problem = await readProblem(response)
  const retryAfter = retryAfterSeconds(response)
  const withRetry = problem && retryAfter !== null && problem.retryAfter === undefined ? { ...problem, retryAfter } : problem
  const message = problem?.detail ?? problem?.title ?? `Request failed with status ${response.status}.`
  return new ApiError(message, {
    kind: 'http',
    status: response.status,
    problem: withRetry ?? (retryAfter !== null ? { retryAfter } : null),
    correlationId: response.headers.get(CORRELATION_ID_HEADER) ?? (typeof problem?.correlationId === 'string' ? problem.correlationId : correlationId),
  })
}

function discardBody(response: Response): void {
  response.body?.cancel().catch(() => undefined)
}

/**
 * Sends one message and reads the answer's events until the stream ends. Resolves with how it ended; rejects with
 * an `ApiError` when the request itself was refused (HTTP error, network failure).
 */
export async function streamChat(options: StreamChatOptions): Promise<ChatOutcome> {
  const readToken = options.getAccessToken ?? getAccessToken
  const refresh = options.refreshAccessToken ?? refreshSession
  const fetchImpl = options.fetch ?? ((input: string, init: RequestInit) => globalThis.fetch(input, init))
  const correlationId = newCorrelationId()
  const body = JSON.stringify(options.conversationId ? { conversationId: options.conversationId, message: options.message } : { message: options.message })

  async function send(token: string | null): Promise<Response> {
    const headers: Record<string, string> = {
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

  let response: Response
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
    if (error.status === 403 && error.errorCode === 'ACCOUNT_SUSPENDED') (options.onAccountSuspended ?? markSuspended)(error.problem?.detail ?? null)
    throw error
  }
  const contentType = (response.headers.get('content-type') ?? '').toLowerCase()
  if (!response.body || !contentType.includes('text/event-stream')) {
    discardBody(response)
    throw new ApiError('The assistant sent an unexpected answer.', { kind: 'unknown', status: response.status, problem: null, correlationId })
  }

  /** Set by the last event (`done` / `error`); later bytes are ignored. */
  const turn: { outcome: ChatOutcome | null } = { outcome: null }
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
