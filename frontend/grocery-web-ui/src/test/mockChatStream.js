import { waitFor } from '@testing-library/react'

const CHAT_PATH = /\/api\/ai\/agents\/([^/]+)\/chat$/
const encoder = new TextEncoder()

function fakeResponse(status, headers, body, text) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(headers),
    body,
    text: async () => text,
  }
}

function abortError() {
  return new DOMException('The operation was aborted.', 'AbortError')
}

/**
 * Adds an in-memory chat endpoint to the global `fetch` already installed (the notification stream of
 * `mockEventStream`, set up for every test): call it after any `mockEventStream()` of the test. By default each
 * chat request answers 200 `text/event-stream` and stays open until the test writes events and closes it.
 */
export function mockChatStream(reply = () => ({ kind: 'open' })) {
  const previous = globalThis.fetch
  const requests = []
  const streams = []

  const fetchMock = (input, init = {}) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const match = CHAT_PATH.exec(url)
    if (!match) return previous(input, init)

    const request = {
      url,
      agent: decodeURIComponent(match[1] ?? ''),
      method: init.method ?? 'GET',
      headers: new Headers(init.headers),
      body: typeof init.body === 'string' ? JSON.parse(init.body) : { message: '' },
      signal: init.signal ?? null,
    }
    const index = requests.length
    requests.push(request)
    const answer = reply(request, index)

    if (answer.kind === 'network-error') return Promise.reject(new TypeError('Failed to fetch'))
    if (answer.kind === 'status') {
      const text = answer.body === undefined ? '' : JSON.stringify(answer.body)
      return Promise.resolve(
        fakeResponse(answer.status, { 'content-type': 'application/problem+json', ...answer.headers }, null, text),
      )
    }

    let controller
    let aborted = false
    let finished = false
    const body = new ReadableStream({
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

    const write = (text) => {
      if (finished) return
      try {
        controller.enqueue(encoder.encode(text))
      } catch {
        // The client stopped reading (it cancels the body after the last event).
      }
    }
    const send = (event, data) => write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    let tools = 0
    const handle = {
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

  globalThis.fetch = fetchMock

  return {
    fetch: fetchMock,
    requests,
    streams,
    /** Waits until chat request number `index` (0-based) has been answered with an open stream. */
    async connection(index = 0) {
      await waitFor(() => {
        if (!streams[index]) throw new Error(`Chat stream #${index} was not opened (opened: ${streams.length})`)
      })
      return streams[index]
    },
  }
}
