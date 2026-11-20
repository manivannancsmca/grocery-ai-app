import { waitFor } from '@testing-library/react'

const encoder = new TextEncoder()

function fakeResponse(status, contentType, body, text) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'content-type': contentType }),
    body,
    text: async () => text,
  }
}

function abortError() {
  return new DOMException('The operation was aborted.', 'AbortError')
}

/**
 * Replaces the global `fetch` with an in-memory SSE server for `/api/notifications/stream`, so no test ever
 * opens a real stream. Every connection is recorded; by default it answers 200 and stays open (silent).
 * Any other `fetch` rejects (the app's REST calls go through axios / `mockApi`).
 */
export function mockEventStream(reply = () => ({ kind: 'open' })) {
  const requests = []
  const streams = []

  const fetchMock = (input, init = {}) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if (!url.includes('/notifications/stream')) {
      return Promise.reject(new TypeError(`Unmocked fetch: ${url}`))
    }
    const request = { url, headers: new Headers(init.headers), signal: init.signal ?? null }
    const index = requests.length
    requests.push(request)
    const answer = reply(request, index)

    if (answer.kind === 'network-error') return Promise.reject(new TypeError('Failed to fetch'))
    if (answer.kind === 'hang') {
      return new Promise((_resolve, reject) => {
        request.signal?.addEventListener('abort', () => reject(abortError()))
      })
    }
    if (answer.kind === 'status') {
      const text = answer.body === undefined ? '' : JSON.stringify(answer.body)
      return Promise.resolve(fakeResponse(answer.status, answer.contentType ?? 'application/problem+json', null, text))
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
      controller.enqueue(encoder.encode(text))
    }
    const send = ({ event, id, data }) => {
      const json = typeof data === 'string' ? data : JSON.stringify(data)
      const lines = [
        event ? `event: ${event}` : null,
        id ? `id: ${id}` : null,
        ...json.split('\n').map((line) => `data: ${line}`),
      ]
      write(`${lines.filter((line) => line !== null).join('\n')}\n\n`)
    }
    const stream = {
      request,
      write,
      send,
      notification: (notification) => send({ event: 'notification', id: notification.id, data: notification }),
      signal: (signal) => send({ event: 'signal', data: { at: '2026-11-11T10:00:00.123Z', ...signal } }),
      session: (reason) => send({ event: 'session', data: { reason } }),
      heartbeat: () => write(': hb\n\n'),
      close: () => {
        if (finished) return
        finished = true
        controller.close()
      },
      fail: () => {
        if (finished) return
        finished = true
        controller.error(new TypeError('network error'))
      },
      get aborted() {
        return aborted
      },
    }
    streams.push(stream)
    // The contract: the first line is `retry: 3000`.
    write('retry: 3000\n\n')
    return Promise.resolve(fakeResponse(200, 'text/event-stream', body, ''))
  }

  globalThis.fetch = fetchMock

  return {
    fetch: fetchMock,
    requests,
    streams,
    /** Waits until stream number `index` (0-based) has been opened. */
    async connection(index = 0) {
      await waitFor(() => {
        if (!streams[index]) throw new Error(`Stream #${index} was not opened (opened: ${streams.length})`)
      })
      return streams[index]
    },
    /** The most recently opened stream. */
    latest() {
      const stream = streams.at(-1)
      if (!stream) throw new Error('No stream was opened')
      return stream
    },
  }
}
