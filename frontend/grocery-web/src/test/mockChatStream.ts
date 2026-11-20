import { waitFor } from '@testing-library/react'
import type { ApprovalView, ToolActivity } from '../features/ai/api'

/** What the app sent to `POST /api/ai/agents/{agent}/chat`. */
export interface ChatRequest {
  url: string
  agent: string
  method: string
  headers: Headers
  body: { conversationId?: string; message: string }
  signal: AbortSignal | null
}

/** How the fake orchestrator answers chat request number `index`. */
export type ChatReply =
  | { kind: 'open' }
  | { kind: 'status'; status: number; body?: unknown; headers?: Record<string, string> }
  | { kind: 'network-error' }

/** One open chat answer (`text/event-stream`) the test writes to. */
export interface ChatStreamHandle {
  request: ChatRequest
  /** Raw text, chunked exactly as given. */
  write: (text: string) => void
  send: (event: string, data: unknown) => void
  conversation: (conversationId: string, agent?: string) => void
  tool: (activity: Partial<ToolActivity> & Pick<ToolActivity, 'tool' | 'status'>) => void
  approval: (approval: ApprovalView) => void
  token: (text: string) => void
  done: (messageId?: string) => void
  error: (code: string, message: string) => void
  /** The server ends the stream (without `done` if not sent). */
  close: () => void
  readonly aborted: boolean
}

const CHAT_PATH = /\/api\/ai\/agents\/([^/]+)\/chat$/
const encoder = new TextEncoder()

function fakeResponse(status: number, headers: Record<string, string>, body: ReadableStream<Uint8Array> | null, text: string): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(headers),
    body,
    text: async () => text,
  } as unknown as Response
}

function abortError(): Error {
  return new DOMException('The operation was aborted.', 'AbortError')
}

/**
 * Adds an in-memory chat endpoint to the global `fetch` already installed (the notification stream of
 * `mockEventStream`, set up for every test): call it after any `mockEventStream()` of the test. By default each
 * chat request answers 200 `text/event-stream` and stays open until the test writes events and closes it.
 */
export function mockChatStream(reply: (request: ChatRequest, index: number) => ChatReply = () => ({ kind: 'open' })) {
  const previous = globalThis.fetch
  const requests: ChatRequest[] = []
  const streams: ChatStreamHandle[] = []

  const fetchMock = (input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const match = CHAT_PATH.exec(url)
    if (!match) return previous(input, init)

    const request: ChatRequest = {
      url,
      agent: decodeURIComponent(match[1] ?? ''),
      method: init.method ?? 'GET',
      headers: new Headers(init.headers),
      body: typeof init.body === 'string' ? (JSON.parse(init.body) as ChatRequest['body']) : { message: '' },
      signal: init.signal ?? null,
    }
    const index = requests.length
    requests.push(request)
    const answer = reply(request, index)

    if (answer.kind === 'network-error') return Promise.reject(new TypeError('Failed to fetch'))
    if (answer.kind === 'status') {
      const text = answer.body === undefined ? '' : JSON.stringify(answer.body)
      return Promise.resolve(fakeResponse(answer.status, { 'content-type': 'application/problem+json', ...answer.headers }, null, text))
    }

    let controller!: ReadableStreamDefaultController<Uint8Array>
    let aborted = false
    let finished = false
    const body = new ReadableStream<Uint8Array>({
      start(streamController) {
        controller = streamController
      },
    })
    request.signal?.addEventListener('abort', () => {
      aborted = true
      if (finished) return
      finished = true
      try {
        controller.error(abortError())
      } catch {
        // already closed
      }
    })

    const write = (text: string) => {
      if (finished) return
      try {
        controller.enqueue(encoder.encode(text))
      } catch {
        // The client stopped reading (it cancels the body after the last event).
      }
    }
    const send = (event: string, data: unknown) => write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    let tools = 0
    const handle: ChatStreamHandle = {
      request,
      write,
      send,
      conversation: (conversationId, agent = request.agent) => send('conversation', { conversationId, agent }),
      tool: (activity) => {
        tools++
        send('tool', { invocationId: `inv-${tools}`, label: activity.tool, summary: null, ...activity })
      },
      approval: (approval) => send('approval', approval),
      token: (text) => send('token', { text }),
      done: (messageId = 'm-assistant') => send('done', { messageId, finishReason: 'STOP' }),
      error: (code, message) => send('error', { code, message }),
      close: () => {
        if (finished) return
        finished = true
        try {
          controller.close()
        } catch {
          // already cancelled by the client
        }
      },
      get aborted() {
        return aborted
      },
    }
    streams.push(handle)
    return Promise.resolve(fakeResponse(200, { 'content-type': 'text/event-stream' }, body, ''))
  }

  globalThis.fetch = fetchMock as typeof fetch

  return {
    fetch: fetchMock,
    requests,
    streams,
    /** Waits until chat request number `index` (0-based) has been answered with an open stream. */
    async connection(index = 0): Promise<ChatStreamHandle> {
      await waitFor(() => {
        if (!streams[index]) throw new Error(`Chat stream #${index} was not opened (opened: ${streams.length})`)
      })
      return streams[index] as ChatStreamHandle
    },
  }
}
