import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { type MockStream, mockEventStream, type StreamReply, type StreamRequest } from '../../test/mockEventStream'
import { notificationView } from '../../test/notificationFixtures'
import type { RealtimeStatus } from './messages'
import { createSseClient, DEFAULT_RECONNECT_TIMING, reconnectDelay, type SseClient } from './sseClient'
import type { SseEvent } from './sseParser'

let client: SseClient | null = null

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
})

afterEach(() => {
  client?.stop()
  client = null
  vi.useRealTimers()
})

/** Lets pending promises (fetch, stream reads, the refresh) settle without moving the clock. */
async function settle() {
  for (let index = 0; index < 10; index++) await vi.advanceTimersByTimeAsync(0)
}

async function advance(ms: number) {
  await vi.advanceTimersByTimeAsync(ms)
  await settle()
}

function setup(options: { reply?: (request: StreamRequest, index: number) => StreamReply; lastEventId?: string } = {}) {
  const sse = mockEventStream(options.reply)
  let token: string | null = 'token-1'
  let lastEventId: string | null = options.lastEventId ?? null
  const events: SseEvent[] = []
  const statuses: RealtimeStatus[] = []
  const refresh = vi.fn(async (): Promise<unknown> => {
    token = 'token-2'
    return undefined
  })
  const onRevoked = vi.fn()
  const onReplaced = vi.fn()
  client = createSseClient({
    getAccessToken: () => token,
    refreshAccessToken: refresh,
    lastEventId: {
      get: () => lastEventId,
      set: (id) => {
        lastEventId = id
      },
    },
    onEvent: (event) => events.push(event),
    onStatusChange: (status) => statuses.push(status),
    onRevoked,
    onReplaced,
    fetch: sse.fetch,
    random: () => 0.5, // no jitter: delays are exact
  })
  return {
    sse,
    client,
    events,
    statuses,
    refresh,
    onRevoked,
    onReplaced,
    lastEventId: () => lastEventId,
    setToken: (value: string | null) => {
      token = value
    },
    stream: (index: number) => sse.streams[index] as MockStream,
  }
}

describe('reconnectDelay', () => {
  it('doubles from the base up to 30 s, with ±20 % jitter, never below the floor', () => {
    const timing = DEFAULT_RECONNECT_TIMING
    expect([0, 1, 2, 3, 4, 5, 6].map((attempt) => reconnectDelay(attempt, 1_000, timing, () => 0.5))).toEqual([
      1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000,
    ])
    expect(reconnectDelay(0, 1_000, timing, () => 0)).toBe(800)
    expect(reconnectDelay(0, 1_000, timing, () => 1)).toBe(1_200)
    expect(reconnectDelay(9, 1_000, timing, () => 1)).toBe(30_000)
    expect(reconnectDelay(0, 10, timing, () => 0.5)).toBe(250)
  })
})

describe('SSE client', () => {
  it('sends the bearer token, Accept and Last-Event-ID, and remembers the id of each notification', async () => {
    const t = setup({ lastEventId: '0193-old' })
    t.client.start()
    await settle()

    const request = t.sse.requests[0] as StreamRequest
    expect(request.url).toBe('/api/notifications/stream')
    expect(request.headers.get('Authorization')).toBe('Bearer token-1')
    expect(request.headers.get('Accept')).toBe('text/event-stream')
    expect(request.headers.get('Last-Event-ID')).toBe('0193-old')
    expect(t.statuses).toEqual(['connecting', 'live'])

    // A notification split anywhere across network chunks.
    const stream = t.stream(0)
    stream.write('event: notifi')
    stream.write('cation\r\nid: 0193-new\r\nda')
    stream.write('ta: {"id":"0193-new"}\r\n')
    stream.write('\r\n')
    stream.signal({ type: 'RESYNC', data: {} })
    await settle()

    expect(t.events[0]).toEqual({ type: 'notification', data: '{"id":"0193-new"}', id: '0193-new', lastEventId: '0193-new' })
    expect(t.events[1]).toMatchObject({ type: 'signal', id: null })
    expect(t.lastEventId()).toBe('0193-new')

    // The server ends the stream: the next connection resumes from the last notification (after `retry: 3000`).
    stream.close()
    await advance(2_999)
    expect(t.sse.requests).toHaveLength(1)
    await advance(1)
    expect(t.sse.requests[1]?.headers.get('Last-Event-ID')).toBe('0193-new')
    expect(t.client.getStatus()).toBe('live')
  })

  it('reconnects with exponential backoff (1 s → 30 s) while the server is unreachable, and reads offline after 3 failures', async () => {
    const t = setup({ reply: () => ({ kind: 'network-error' }) })
    t.client.start()
    await settle()
    expect(t.sse.requests).toHaveLength(1)
    expect(t.client.getStatus()).toBe('reconnecting')

    const delays = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000]
    for (const [index, delay] of delays.entries()) {
      await advance(delay - 1)
      expect(t.sse.requests).toHaveLength(index + 1)
      await advance(1)
      expect(t.sse.requests).toHaveLength(index + 2)
    }
    expect(t.statuses).toEqual(['connecting', 'reconnecting', 'offline'])
  })

  it('honours retry: and starts the backoff over once a connection stayed healthy for a minute', async () => {
    const t = setup()
    t.client.start()
    await settle()

    // Streams that end right away back off from the server's `retry: 3000`: 3 s, then 6 s.
    t.stream(0).close()
    await settle()
    expect(t.client.getStatus()).toBe('reconnecting')
    await advance(3_000)
    expect(t.sse.requests).toHaveLength(2)
    t.stream(1).close()
    await advance(5_999)
    expect(t.sse.requests).toHaveLength(2)
    await advance(1)
    expect(t.sse.requests).toHaveLength(3)

    // Healthy for a minute (heartbeats every 20 s): the next drop waits 3 s again, not 12 s.
    for (let second = 0; second < 60; second += 20) {
      await advance(20_000)
      t.stream(2).heartbeat()
    }
    t.stream(2).close()
    await advance(3_000)
    expect(t.sse.requests).toHaveLength(4)
  })

  it('refreshes the token through the app on 401 and reconnects at once', async () => {
    const t = setup({
      reply: (request) =>
        request.headers.get('Authorization') === 'Bearer token-2' ? { kind: 'open' } : { kind: 'status', status: 401, body: { errorCode: 'TOKEN_EXPIRED' } },
    })
    t.client.start()
    await settle()

    expect(t.refresh).toHaveBeenCalledTimes(1)
    expect(t.sse.requests).toHaveLength(2)
    expect(t.sse.requests[1]?.headers.get('Authorization')).toBe('Bearer token-2')
    expect(t.client.getStatus()).toBe('live')
  })

  it('stops when the refresh fails and the session is over (the app shows the login page)', async () => {
    const t = setup({ reply: () => ({ kind: 'status', status: 401 }) })
    t.refresh.mockImplementation(async () => {
      t.setToken(null)
      throw new Error('REFRESH_TOKEN_INVALID')
    })
    t.client.start()
    await settle()

    expect(t.refresh).toHaveBeenCalledTimes(1)
    expect(t.client.getStatus()).toBe('offline')
    await advance(120_000)
    expect(t.sse.requests).toHaveLength(1)
  })

  it('backs off instead of refreshing again when the renewed token is refused too', async () => {
    const t = setup({ reply: () => ({ kind: 'status', status: 401 }) })
    t.client.start()
    await settle()
    expect(t.refresh).toHaveBeenCalledTimes(1)
    expect(t.sse.requests).toHaveLength(2)

    await advance(999)
    expect(t.sse.requests).toHaveLength(2)
    await advance(1)
    // The next attempt may refresh again.
    expect(t.sse.requests.length).toBeGreaterThanOrEqual(3)
    expect(t.refresh).toHaveBeenCalledTimes(2)
  })

  it('session TOKEN_EXPIRED: refreshes and reconnects at once with the last notification id', async () => {
    const t = setup()
    t.client.start()
    await settle()
    const first = t.stream(0)
    first.notification(notificationView({ id: '0193-n1' }))
    first.session('TOKEN_EXPIRED')
    await settle()

    expect(first.aborted).toBe(true)
    expect(t.refresh).toHaveBeenCalledTimes(1)
    expect(t.sse.requests).toHaveLength(2)
    expect(t.sse.requests[1]?.headers.get('Authorization')).toBe('Bearer token-2')
    expect(t.sse.requests[1]?.headers.get('Last-Event-ID')).toBe('0193-n1')
    expect(t.client.getStatus()).toBe('live')
    // The session event itself is not passed on.
    expect(t.events.map((event) => event.type)).toEqual(['notification'])
  })

  it('session REVOKED: hands over to the suspension flow and never reconnects', async () => {
    const t = setup()
    t.client.start()
    await settle()
    t.stream(0).session('REVOKED')
    t.stream(0).signal({ type: 'RESYNC', data: {} })
    await settle()

    expect(t.onRevoked).toHaveBeenCalledWith(null)
    expect(t.client.getStatus()).toBe('offline')
    expect(t.events).toEqual([])
    await advance(120_000)
    expect(t.sse.requests).toHaveLength(1)
  })

  it('treats 403 ACCOUNT_SUSPENDED on connect like REVOKED', async () => {
    const t = setup({
      reply: () => ({ kind: 'status', status: 403, body: { errorCode: 'ACCOUNT_SUSPENDED', detail: 'Suspended: fraud check' } }),
    })
    t.client.start()
    await settle()

    expect(t.onRevoked).toHaveBeenCalledWith('Suspended: fraud check')
    await advance(120_000)
    expect(t.sse.requests).toHaveLength(1)
  })

  it('session REPLACED: stops until start() takes the connection back', async () => {
    const t = setup()
    t.client.start()
    await settle()
    t.stream(0).session('REPLACED')
    await settle()

    expect(t.onReplaced).toHaveBeenCalledTimes(1)
    expect(t.client.getStatus()).toBe('offline')
    await advance(120_000)
    expect(t.sse.requests).toHaveLength(1)

    t.client.start()
    await settle()
    expect(t.sse.requests).toHaveLength(2)
    expect(t.client.getStatus()).toBe('live')
  })

  it('reconnects when nothing (not even a heartbeat) arrived for 45 s', async () => {
    const t = setup()
    t.client.start()
    await settle()

    await advance(44_000)
    t.stream(0).heartbeat()
    await advance(44_000)
    expect(t.stream(0).aborted).toBe(false)
    await advance(1_000)
    expect(t.stream(0).aborted).toBe(true)
    expect(t.client.getStatus()).toBe('reconnecting')

    await advance(3_000)
    expect(t.sse.requests).toHaveLength(2)
    expect(t.client.getStatus()).toBe('live')
  })

  it('skips the backoff wait when the browser comes back online', async () => {
    const t = setup({ reply: (_request, index) => (index === 0 ? { kind: 'network-error' } : { kind: 'open' }) })
    t.client.start()
    await settle()
    expect(t.client.getStatus()).toBe('reconnecting')

    window.dispatchEvent(new Event('online'))
    await settle()
    expect(t.sse.requests).toHaveLength(2)
    expect(t.client.getStatus()).toBe('live')
  })

  it('stop() closes the stream and cancels a pending reconnect', async () => {
    const t = setup()
    t.client.start()
    await settle()
    t.client.stop()
    expect(t.stream(0).aborted).toBe(true)
    expect(t.client.getStatus()).toBe('offline')

    t.client.start()
    await settle()
    t.stream(1).close()
    await settle()
    t.client.stop()
    await advance(120_000)
    expect(t.sse.requests).toHaveLength(2)
  })
})
