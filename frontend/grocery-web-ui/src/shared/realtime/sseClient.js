import { CORRELATION_ID_HEADER, newCorrelationId } from '../api/correlation'
import { parseSessionReason, STREAM_EVENTS } from './messages'
import { createSseParser } from './sseParser'

/** @typedef {import('./messages').RealtimeStatus} RealtimeStatus */
/** @typedef {import('./sseParser').SseEvent} SseEvent */

/**
 * Fetch-based Server-Sent Events client for `GET /api/notifications/stream` (ADR-0005, Phase 7 contract §2).
 * `EventSource` cannot send `Authorization`, so the stream is read from a `fetch` body instead.
 *
 * - Sends `Authorization: Bearer <current access token>`, `Accept: text/event-stream` and `Last-Event-ID`
 *   (the last notification id seen), so the server replays what was missed.
 * - Reconnects with exponential backoff and ±20 % jitter: 1 s, 2 s, 4 s … capped at 30 s. A `retry:` from the
 *   server replaces the 1 s base. The backoff starts over once a connection has stayed open for a minute.
 * - Heartbeat watchdog: no bytes for 45 s (the server sends `: hb` every 20 s) → drop and reconnect.
 * - `401` → one shared token refresh (the app's), then reconnect at once. A failed refresh ends the session
 *   through the session layer, which stops this client.
 * - `session` events: `TOKEN_EXPIRED` → refresh and reconnect at once; `REVOKED` → `onRevoked`;
 *   `REPLACED` → stop and `onReplaced` (another tab took the connection).
 * - The stream stays open while the tab is hidden (the bell and toasts stay current; the server caps a user at
 *   5 streams). When the tab becomes visible again, or the browser comes back online, a pending backoff wait
 *   is skipped and the client reconnects at once.
 */

export const NOTIFICATION_STREAM_URL = '/api/notifications/stream'

/**
 * @typedef {object} ReconnectTiming
 * @property {number} initialDelayMs First reconnect delay when the server sent no `retry:`.
 * @property {number} maxDelayMs
 * @property {number} minDelayMs Floor for a tiny `retry:` value.
 * @property {number} healthyAfterMs A connection open this long resets the backoff.
 * @property {number} heartbeatTimeoutMs Silence (no bytes, not even a heartbeat) after which the connection is considered dead.
 * @property {number} offlineAfterFailures Failed attempts in a row after which the status reads `offline` (retries go on in the background).
 */

/** @type {ReconnectTiming} */
export const DEFAULT_RECONNECT_TIMING = {
  initialDelayMs: 1_000,
  maxDelayMs: 30_000,
  minDelayMs: 250,
  healthyAfterMs: 60_000,
  heartbeatTimeoutMs: 45_000,
  offlineAfterFailures: 3,
}

/**
 * Delay before reconnect number `attempt` (0-based): `base × 2^attempt`, ±20 % jitter, within [min, max].
 * @param {number} attempt
 * @param {number} baseMs
 * @param {ReconnectTiming} timing
 * @param {() => number} random
 * @returns {number}
 */
export function reconnectDelay(attempt, baseMs, timing, random) {
  const exponential = Math.min(timing.maxDelayMs, baseMs * 2 ** attempt)
  const jittered = exponential * (0.8 + 0.4 * random())
  return Math.round(Math.max(timing.minDelayMs, Math.min(timing.maxDelayMs, jittered)))
}

/**
 * Where the last seen event id is kept between connections (and page reloads).
 * @typedef {object} LastEventIdStore
 * @property {() => string | null} get
 * @property {(id: string) => void} set
 */

/**
 * @typedef {object} SseClientOptions
 * @property {string} [url]
 * @property {() => string | null} getAccessToken The in-memory access token; null when signed out.
 * @property {() => Promise<unknown>} refreshAccessToken The app's shared refresh. Rejects when the session cannot be renewed.
 * @property {LastEventIdStore} lastEventId
 * @property {(event: SseEvent) => void} onEvent Every event except `session` (handled here): `notification`, `signal`, …
 * @property {(status: RealtimeStatus) => void} [onStatusChange]
 * @property {(message: string | null) => void} [onRevoked] `session` REVOKED, or `403 ACCOUNT_SUSPENDED` on connect (with the server's message).
 * @property {() => void} [onReplaced] `session` REPLACED: the client has stopped; `start()` takes the connection back.
 * @property {(input: string, init: RequestInit) => Promise<Response>} [fetch] Defaults to the global `fetch`, looked up on every connect.
 * @property {() => number} [random] Jitter source (tests pass a constant).
 * @property {Partial<ReconnectTiming>} [timing]
 */

/**
 * @typedef {object} SseClient
 * @property {() => void} start Opens the stream (no-op while running).
 * @property {() => void} stop Closes the stream and cancels every timer; the status becomes `offline`.
 * @property {() => void} retryNow Skips a pending backoff wait.
 * @property {() => RealtimeStatus} getStatus
 */

/** @typedef {ReturnType<typeof setTimeout>} Timer */

/**
 * @typedef {object} ProblemInfo
 * @property {string | null} errorCode
 * @property {string | null} detail
 */

/**
 * @param {Response} response
 * @returns {Promise<ProblemInfo>}
 */
async function readProblem(response) {
  try {
    const body = JSON.parse(await response.text())
    const record = typeof body === 'object' && body !== null ? body : {}
    return {
      errorCode: typeof record.errorCode === 'string' ? record.errorCode : null,
      detail: typeof record.detail === 'string' && record.detail.length > 0 ? record.detail : null,
    }
  } catch {
    return { errorCode: null, detail: null }
  }
}

/**
 * @param {Response} response
 * @returns {void}
 */
function discardBody(response) {
  response.body?.cancel().catch(() => undefined)
}

/**
 * @param {Response} response
 * @returns {boolean}
 */
function isEventStream(response) {
  return (response.headers.get('content-type') ?? '').toLowerCase().includes('text/event-stream')
}

/**
 * @param {SseClientOptions} options
 * @returns {SseClient}
 */
export function createSseClient(options) {
  const timing = { ...DEFAULT_RECONNECT_TIMING, ...options.timing }
  const url = options.url ?? NOTIFICATION_STREAM_URL
  const random = options.random ?? Math.random

  let status = 'offline'
  let running = false
  /** Bumped whenever the current connection is abandoned; stale async continuations compare against it. */
  let generation = 0
  let controller = null
  let retryTimer = null
  let watchdogTimer = null
  let healthyTimer = null
  /** Backoff exponent; reset after a healthy minute. */
  let attempt = 0
  /** Failed connection attempts in a row; reset when a stream opens. */
  let failures = 0
  let everLive = false
  let serverRetryMs = null
  /** The previous attempt already answered 401 and refreshed: the next 401 waits for the backoff. */
  let authRetried = false

  function setStatus(next) {
    if (status === next) return
    status = next
    options.onStatusChange?.(next)
  }

  function clearConnectionTimers() {
    if (watchdogTimer !== null) clearTimeout(watchdogTimer)
    if (healthyTimer !== null) clearTimeout(healthyTimer)
    watchdogTimer = null
    healthyTimer = null
  }

  function clearRetryTimer() {
    if (retryTimer !== null) clearTimeout(retryTimer)
    retryTimer = null
  }

  /** Abandons the current connection; its pending callbacks see a newer generation and do nothing. */
  function drop() {
    generation++
    controller?.abort()
    controller = null
    clearConnectionTimers()
  }

  function scheduleReconnect(failed) {
    if (!running) return
    if (failed) failures++
    setStatus(failures >= timing.offlineAfterFailures ? 'offline' : 'reconnecting')
    const delay = reconnectDelay(attempt, serverRetryMs ?? timing.initialDelayMs, timing, random)
    attempt++
    clearRetryTimer()
    retryTimer = setTimeout(() => {
      retryTimer = null
      void connect()
    }, delay)
  }

  function armWatchdog(id) {
    if (watchdogTimer !== null) clearTimeout(watchdogTimer)
    watchdogTimer = setTimeout(() => {
      if (id !== generation) return
      drop()
      scheduleReconnect(false)
    }, timing.heartbeatTimeoutMs)
  }

  function revoke(message) {
    stop()
    options.onRevoked?.(message)
  }

  /** Renews the access token (unless another request already did) and reconnects at once. */
  async function renewAndReconnect(tokenUsed) {
    const id = generation
    try {
      if (options.getAccessToken() === tokenUsed) await options.refreshAccessToken()
    } catch {
      if (!running || id !== generation) return
      // The session is over (the session layer shows the login page) — or the gateway is unreachable: retry later.
      if (options.getAccessToken() === null) stop()
      else scheduleReconnect(true)
      return
    }
    if (!running || id !== generation) return
    void connect()
  }

  function handleSession(reason, tokenUsed) {
    switch (reason) {
      case 'TOKEN_EXPIRED':
        drop()
        setStatus('reconnecting')
        void renewAndReconnect(tokenUsed)
        return
      case 'REVOKED':
        revoke(null)
        return
      case 'REPLACED':
        stop()
        options.onReplaced?.()
        return
      default:
        // Unknown reason: the server closes the stream and the normal reconnect follows.
        return
    }
  }

  async function read(body, id, tokenUsed) {
    const reader = body.getReader()
    const decoder = new TextDecoder()
    const parser = createSseParser({
      onRetry: (ms) => {
        serverRetryMs = ms
      },
      onEvent: (event) => {
        if (id !== generation) return
        if (event.type === STREAM_EVENTS.session) {
          handleSession(parseSessionReason(event.data), tokenUsed)
          return
        }
        if (event.id) options.lastEventId.set(event.id)
        try {
          options.onEvent(event)
        } catch {
          // A failing handler must never break the stream.
        }
      },
    })

    armWatchdog(id)
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (id !== generation) return
        if (done) break
        armWatchdog(id)
        parser.push(decoder.decode(value, { stream: true }))
        if (id !== generation) return
      }
    } catch {
      if (id !== generation) return
    } finally {
      if (id !== generation) reader.cancel().catch(() => undefined)
    }
    // The server closed the stream, or it broke: reconnect with the backoff.
    drop()
    scheduleReconnect(false)
  }

  async function connect() {
    if (!running) return
    clearRetryTimer()
    drop()
    const id = generation

    const token = options.getAccessToken()
    if (token === null) {
      stop()
      return
    }
    if (failures < timing.offlineAfterFailures) setStatus(everLive || failures > 0 ? 'reconnecting' : 'connecting')

    const abort = new AbortController()
    controller = abort
    const headers = {
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
      [CORRELATION_ID_HEADER]: newCorrelationId(),
    }
    const lastEventId = options.lastEventId.get()
    if (lastEventId) headers['Last-Event-ID'] = lastEventId

    let response
    try {
      const fetchImpl = options.fetch ?? ((input, init) => globalThis.fetch(input, init))
      response = await fetchImpl(url, {
        method: 'GET',
        headers,
        signal: abort.signal,
        cache: 'no-store',
        credentials: 'same-origin',
      })
    } catch {
      if (id !== generation) return
      drop()
      scheduleReconnect(true)
      return
    }
    if (id !== generation) {
      discardBody(response)
      return
    }

    if (response.status === 401) {
      discardBody(response)
      drop()
      if (authRetried) {
        authRetried = false
        scheduleReconnect(true)
        return
      }
      authRetried = true
      await renewAndReconnect(token)
      return
    }

    if (response.status === 403) {
      const problem = await readProblem(response)
      if (id !== generation) return
      if (problem.errorCode === 'ACCOUNT_SUSPENDED') {
        revoke(problem.detail)
        return
      }
    }

    if (!response.ok || !response.body || !isEventStream(response)) {
      discardBody(response)
      drop()
      scheduleReconnect(true)
      return
    }

    authRetried = false
    failures = 0
    everLive = true
    setStatus('live')
    healthyTimer = setTimeout(() => {
      attempt = 0
    }, timing.healthyAfterMs)
    await read(response.body, id, token)
  }

  function retryNow() {
    if (!running || retryTimer === null) return
    clearRetryTimer()
    void connect()
  }

  function onOnline() {
    retryNow()
  }

  function onVisibilityChange() {
    if (document.visibilityState === 'visible') retryNow()
  }

  function start() {
    if (running) return
    running = true
    attempt = 0
    failures = 0
    everLive = false
    authRetried = false
    if (typeof window !== 'undefined') {
      window.addEventListener('online', onOnline)
      document.addEventListener('visibilitychange', onVisibilityChange)
    }
    void connect()
  }

  function stop() {
    running = false
    clearRetryTimer()
    drop()
    if (typeof window !== 'undefined') {
      window.removeEventListener('online', onOnline)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
    setStatus('offline')
  }

  return { start, stop, retryNow, getStatus: () => status }
}
