/**
 * Incremental parser for the `text/event-stream` format (WHATWG HTML, "Server-sent events" §9.2.6).
 *
 * Feed it decoded text in chunks of any size: a chunk may end in the middle of a line, a field name, a
 * multi-byte character (decode with `TextDecoder(..., { stream: true })` first) or between the `\r` and
 * `\n` of a CRLF. Lines end with `\n`, `\r\n` or `\r`. A blank line dispatches the event.
 */

/**
 * One dispatched server-sent event.
 * @typedef {object} SseEvent
 * @property {string} type The `event:` field; `message` when the block had none.
 * @property {string} data The `data:` lines of the block joined with `\n`.
 * @property {string | null} id The block's own `id:` field, or null when it had none.
 * @property {string} lastEventId The stream's last event ID at dispatch time (an `id:` stays in effect for later events, as in EventSource).
 */

/**
 * @typedef {object} SseParserHandlers
 * @property {(event: SseEvent) => void} onEvent
 * @property {(ms: number) => void} [onRetry] A valid `retry:` field: the server's reconnection time in milliseconds.
 * @property {(text: string) => void} [onComment] A comment line (`: hb`), without the leading colon.
 */

/**
 * @typedef {object} SseParser
 * @property {(chunk: string) => void} push Parses the next piece of the stream.
 */

const LF = 10
const CR = 13

/**
 * @param {SseParserHandlers} handlers
 * @returns {SseParser}
 */
export function createSseParser(handlers) {
  /** Text after the last line break seen so far (an incomplete line). */
  let pending = ''
  /** The previous chunk ended with `\r`: a `\n` at the start of the next one belongs to the same CRLF. */
  let skipLeadingLf = false
  let started = false

  let eventType = ''
  let data = ''
  let blockId = null
  let lastEventId = ''

  function dispatch() {
    const id = blockId
    blockId = null
    // An event without data is not dispatched (an `id:` in it still updated lastEventId).
    if (data === '') {
      eventType = ''
      return
    }
    const event = {
      type: eventType === '' ? 'message' : eventType,
      data: data.endsWith('\n') ? data.slice(0, -1) : data,
      id,
      lastEventId,
    }
    eventType = ''
    data = ''
    handlers.onEvent(event)
  }

  function processLine(line) {
    if (line === '') {
      dispatch()
      return
    }
    if (line.startsWith(':')) {
      handlers.onComment?.(line.slice(1))
      return
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    switch (field) {
      case 'event':
        eventType = value
        break
      case 'data':
        data += `${value}\n`
        break
      case 'id':
        // An id containing NULL is ignored entirely.
        if (!value.includes('\0')) {
          lastEventId = value
          blockId = value
        }
        break
      case 'retry':
        if (/^\d+$/.test(value)) handlers.onRetry?.(Number(value))
        break
      default:
        // Unknown fields are ignored.
        break
    }
  }

  function push(chunk) {
    let text = chunk
    if (text.length === 0) return
    if (!started) {
      started = true
      if (text.charCodeAt(0) === 0xfeff) text = text.slice(1)
    }
    if (skipLeadingLf) {
      skipLeadingLf = false
      if (text.charCodeAt(0) === LF) text = text.slice(1)
    }

    const buffer = pending + text
    let lineStart = 0
    // `pending` holds no line break, so scanning can start where the new text begins.
    for (let index = pending.length; index < buffer.length; index++) {
      const code = buffer.charCodeAt(index)
      if (code !== LF && code !== CR) continue
      processLine(buffer.slice(lineStart, index))
      if (code === CR) {
        if (index + 1 < buffer.length) {
          if (buffer.charCodeAt(index + 1) === LF) index++
        } else {
          skipLeadingLf = true
        }
      }
      lineStart = index + 1
    }
    pending = buffer.slice(lineStart)
  }

  return { push }
}
