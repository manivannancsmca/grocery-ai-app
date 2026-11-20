import { describe, expect, it } from 'vitest'
import { createSseParser } from './sseParser'

function parse(chunks) {
  const events = []
  const retries = []
  const comments = []
  const parser = createSseParser({
    onEvent: (event) => events.push(event),
    onRetry: (ms) => retries.push(ms),
    onComment: (text) => comments.push(text),
  })
  chunks.forEach((chunk) => parser.push(chunk))
  return { events, retries, comments }
}

const STREAM =
  'retry: 3000\n\n' +
  'event: notification\nid: 0193-a\ndata: {"id":"0193-a","title":"Order confirmed"}\n\n' +
  ': hb\n\n' +
  'event: signal\ndata: {"type":"ORDER_UPDATED"}\n\n'

describe('SSE parser', () => {
  it('parses events, ids, retry and heartbeat comments', () => {
    const { events, retries, comments } = parse([STREAM])

    expect(retries).toEqual([3000])
    expect(comments).toEqual([' hb'])
    expect(events).toEqual([
      { type: 'notification', data: '{"id":"0193-a","title":"Order confirmed"}', id: '0193-a', lastEventId: '0193-a' },
      // A block without `id:` has no own id; the stream's last event id stays in effect (EventSource rule).
      { type: 'signal', data: '{"type":"ORDER_UPDATED"}', id: null, lastEventId: '0193-a' },
    ])
  })

  it('gives the same result however the stream is split (every single character, and every split point)', () => {
    const whole = parse([STREAM]).events
    expect(parse(STREAM.split('')).events).toEqual(whole)
    for (let at = 1; at < STREAM.length; at++) {
      expect(parse([STREAM.slice(0, at), STREAM.slice(at)]).events).toEqual(whole)
    }
  })

  it('accepts CRLF and lone CR line endings, also when a CRLF is split between chunks', () => {
    const crlf = STREAM.replaceAll('\n', '\r\n')
    const cr = STREAM.replaceAll('\n', '\r')
    const expected = parse([STREAM]).events

    expect(parse([crlf]).events).toEqual(expected)
    expect(parse([cr]).events).toEqual(expected)
    expect(parse(crlf.split('')).events).toEqual(expected)
    // "\r" ends one chunk, "\n" starts the next: one line break, not an empty line in between.
    expect(parse(['data: a\r', '\ndata: b\r\n', '\r\n']).events).toEqual([
      { type: 'message', data: 'a\nb', id: null, lastEventId: '' },
    ])
  })

  it('joins multi-line data with "\\n" and drops only the last line break', () => {
    const { events } = parse(['data: first\ndata: second\ndata\ndata:  indented\n\n'])
    expect(events).toEqual([{ type: 'message', data: 'first\nsecond\n\n indented', id: null, lastEventId: '' }])
  })

  it('reads fields without a space after the colon, and lines without a colon as a field name', () => {
    const { events } = parse(['event:signal\ndata:{"a":1}\n\n'])
    expect(events[0]).toMatchObject({ type: 'signal', data: '{"a":1}' })
  })

  it('does not dispatch a block without data, but keeps its id', () => {
    const { events } = parse(['id: 7\n\n', 'event: signal\n\n', 'data: x\n\n'])
    expect(events).toEqual([{ type: 'message', data: 'x', id: null, lastEventId: '7' }])
  })

  it('ignores comments, unknown fields, ids containing NULL and invalid retry values', () => {
    const { events, retries, comments } = parse([': hello\nfoo: bar\nid: bad\0id\nretry: 12s\nretry: -1\ndata: ok\n\n'])
    expect(comments).toEqual([' hello'])
    expect(retries).toEqual([])
    expect(events).toEqual([{ type: 'message', data: 'ok', id: null, lastEventId: '' }])
  })

  it('discards an incomplete event at the end of the stream and strips a leading BOM', () => {
    const { events } = parse(['﻿data: complete\n\n', 'data: never finished\n'])
    expect(events).toEqual([{ type: 'message', data: 'complete', id: null, lastEventId: '' }])
  })

  it('resets the event type after each dispatch', () => {
    const { events } = parse(['event: session\ndata: {"reason":"REPLACED"}\n\ndata: plain\n\n'])
    expect(events.map((event) => event.type)).toEqual(['session', 'message'])
  })
})
