import { describe, expect, it, vi } from 'vitest'
import { ApiError, problemNumber } from '../../shared/api/problemDetail'
import { placeOrderApproval } from '../../test/aiFixtures'
import { mockChatStream } from '../../test/mockChatStream'
import { parseChatEvent, streamChat } from './chatStream'

function collect() {
  const events = []
  return { events, onEvent: (event) => events.push(event) }
}

const token = () => 'token-1'

describe('streamChat — the POST text/event-stream client', () => {
  it('posts the message with the bearer token and reads events incrementally, even split mid-line and mid-JSON', async () => {
    const chat = mockChatStream()
    const { events, onEvent } = collect()
    const turn = streamChat({
      agent: 'shopping',
      conversationId: 'c1',
      message: 'add 2 rice',
      onEvent,
      getAccessToken: token,
    })

    const stream = await chat.connection()
    expect(stream.request.method).toBe('POST')
    expect(stream.request.url).toBe('/api/ai/agents/shopping/chat')
    expect(stream.request.headers.get('Authorization')).toBe('Bearer token-1')
    expect(stream.request.headers.get('Accept')).toBe('text/event-stream')
    expect(stream.request.headers.get('Content-Type')).toBe('application/json')
    expect(stream.request.headers.get('X-Correlation-Id')).toBeTruthy()
    expect(stream.request.body).toEqual({ conversationId: 'c1', message: 'add 2 rice' })

    stream.conversation('c1')
    stream.write('event: tool\ndata: {"invocationId":"i1","tool":"addToCart","lab')
    await vi.waitFor(() => expect(events).toHaveLength(1))
    stream.write('el":"Added to cart","status":"SUCCEEDED","summary":"2 × Smart Rice"}\n')
    stream.write('\nevent: tok')
    stream.write('en\ndata: {"text":"Added ₹"}\r\n\r\n')
    await vi.waitFor(() => expect(events).toHaveLength(3))
    stream.token('200 of rice.')
    stream.done('m2')

    await expect(turn).resolves.toBe('done')
    expect(events).toEqual([
      { type: 'conversation', conversationId: 'c1', agent: 'shopping' },
      {
        type: 'tool',
        activity: {
          invocationId: 'i1',
          tool: 'addToCart',
          label: 'Added to cart',
          status: 'SUCCEEDED',
          summary: '2 × Smart Rice',
          links: [],
        },
      },
      { type: 'token', text: 'Added ₹' },
      { type: 'token', text: '200 of rice.' },
      { type: 'done', messageId: 'm2', finishReason: 'STOP' },
    ])
  })

  it('omits conversationId for a new conversation, ignores unknown events and malformed data, and stops after `error`', async () => {
    const chat = mockChatStream()
    const { events, onEvent } = collect()
    const turn = streamChat({ agent: 'admin-bi', message: 'stats', onEvent, getAccessToken: token })
    const stream = await chat.connection()
    expect(stream.request.body).toEqual({ message: 'stats' })

    stream.send('thinking', { text: 'hmm' })
    stream.write('event: token\ndata: not json\n\n')
    stream.write(': keep-alive comment\n\n')
    stream.error('AI_UNAVAILABLE', 'The assistant is not available right now. Everything else works as usual.')
    stream.token('ignored after the last event')

    await expect(turn).resolves.toBe('error')
    expect(events).toEqual([
      {
        type: 'error',
        code: 'AI_UNAVAILABLE',
        message: 'The assistant is not available right now. Everything else works as usual.',
      },
    ])
  })

  it('reports a stream that ends without done/error as interrupted', async () => {
    const chat = mockChatStream()
    const { onEvent } = collect()
    const turn = streamChat({ agent: 'shopping', message: 'cart', onEvent, getAccessToken: token })
    const stream = await chat.connection()
    stream.token('Your cart has')
    stream.close()
    await expect(turn).resolves.toBe('interrupted')
  })

  it('resolves `aborted` when the caller cancels', async () => {
    const chat = mockChatStream()
    const controller = new AbortController()
    const { events, onEvent } = collect()
    const turn = streamChat({
      agent: 'shopping',
      message: 'cart',
      onEvent,
      signal: controller.signal,
      getAccessToken: token,
    })
    const stream = await chat.connection()
    stream.token('Your cart')
    await vi.waitFor(() => expect(events).toHaveLength(1))
    controller.abort()
    await expect(turn).resolves.toBe('aborted')
    expect(stream.aborted).toBe(true)
  })

  it('renews the token once on 401 and retries with the new one', async () => {
    let current = 'old-token'
    const refresh = vi.fn(async () => {
      current = 'new-token'
    })
    const chat = mockChatStream((_request, index) =>
      index === 0
        ? { kind: 'status', status: 401, body: { status: 401, errorCode: 'TOKEN_EXPIRED', detail: 'Expired' } }
        : { kind: 'open' },
    )
    const { onEvent } = collect()
    const turn = streamChat({
      agent: 'shopping',
      message: 'hi',
      onEvent,
      getAccessToken: () => current,
      refreshAccessToken: refresh,
    })
    const stream = await chat.connection(0)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(chat.requests.map((request) => request.headers.get('Authorization'))).toEqual([
      'Bearer old-token',
      'Bearer new-token',
    ])
    stream.done()
    await expect(turn).resolves.toBe('done')
  })

  it('rejects a refused request with the ApiError of its ProblemDetail (429 with Retry-After)', async () => {
    mockChatStream(() => ({
      kind: 'status',
      status: 429,
      headers: { 'Retry-After': '600' },
      body: { status: 429, errorCode: 'AI_RATE_LIMITED', detail: 'Too many messages' },
    }))
    const error = await streamChat({
      agent: 'shopping',
      message: 'hi',
      onEvent: () => undefined,
      getAccessToken: token,
    }).catch((failure) => failure)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(429)
    expect(error.errorCode).toBe('AI_RATE_LIMITED')
    expect(problemNumber(error, 'retryAfter')).toBe(600)
  })

  it('rejects a network failure as a network ApiError', async () => {
    mockChatStream(() => ({ kind: 'network-error' }))
    const error = await streamChat({
      agent: 'shopping',
      message: 'hi',
      onEvent: () => undefined,
      getAccessToken: token,
    }).catch((failure) => failure)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.kind).toBe('network')
  })
})

describe('parseChatEvent', () => {
  it('reads a retry event, with a default text when the server sends none', () => {
    expect(
      parseChatEvent({
        type: 'retry',
        data: '{"code":"ACTION","message":"Checking that with the app…"}',
        id: null,
        lastEventId: '',
      }),
    ).toEqual({
      type: 'retry',
      code: 'ACTION',
      message: 'Checking that with the app…',
    })
    expect(parseChatEvent({ type: 'retry', data: '{}', id: null, lastEventId: '' })).toEqual({
      type: 'retry',
      code: 'RETRY',
      message: 'Checking that with the app…',
    })
  })

  it('turns an approval event into an ApprovalView and drops payloads without the required fields', () => {
    const approval = placeOrderApproval()
    expect(parseChatEvent({ type: 'approval', data: JSON.stringify(approval), id: null, lastEventId: '' })).toEqual({
      type: 'approval',
      approval,
    })
    expect(parseChatEvent({ type: 'approval', data: '{"title":"x"}', id: null, lastEventId: '' })).toBeNull()
    expect(parseChatEvent({ type: 'tool', data: '{"label":"x"}', id: null, lastEventId: '' })).toBeNull()
    // A tool's page links: well-formed entries only.
    const tool = parseChatEvent({
      type: 'tool',
      data: '{"tool":"getCart","status":"SUCCEEDED","links":[{"label":"View my cart","path":"/cart"},{"label":"x"},"y"]}',
      id: null,
      lastEventId: '',
    })
    expect(tool?.type === 'tool' && tool.activity.links).toEqual([{ label: 'View my cart', path: '/cart' }])
    expect(parseChatEvent({ type: 'conversation', data: '{}', id: null, lastEventId: '' })).toBeNull()
    expect(parseChatEvent({ type: 'error', data: '{}', id: null, lastEventId: '' })).toEqual({
      type: 'error',
      code: 'AI_ERROR',
      message: 'The assistant is not available right now. Everything else works as usual.',
    })
  })
})
