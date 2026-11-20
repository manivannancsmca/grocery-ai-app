import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/**
 * Phase 10 operations: dead letters of every consuming service (contract §1) and Debezium connector health (§7).
 * Reads: any admin. Replay / discard / replay-all and connector restart: SUPER_ADMIN, OPS_ADMIN.
 */

/** Services with Kafka consumers, in display order; the gateway routes `/api/admin/ops/<service>/dead-letters/**`. */
export const DEAD_LETTER_SERVICES = [
  'identity',
  'customer',
  'vendor',
  'restaurant',
  'delivery',
  'catalog-command',
  'catalog-query',
  'inventory',
  'pricing',
  'cart',
  'order',
  'payment',
  'notification',
  // Phase 12.
  'review',
  'reporting',
]
/** @typedef {(typeof DEAD_LETTER_SERVICES)[number]} DeadLetterService */

/**
 * @param {unknown} value
 * @returns {value is DeadLetterService}
 */
export function isDeadLetterService(value) {
  return typeof value === 'string' && DEAD_LETTER_SERVICES.includes(value)
}

export const DEAD_LETTER_STATUSES = ['PENDING', 'REPLAY_FAILED', 'REPLAYED', 'DISCARDED']
/** @typedef {(typeof DEAD_LETTER_STATUSES)[number]} DeadLetterStatus */

/**
 * Still to be dealt with: replay and discard apply (a closed one answers 409 DEAD_LETTER_CLOSED).
 * @type {readonly DeadLetterStatus[]}
 */
export const OPEN_DEAD_LETTER_STATUSES = ['PENDING', 'REPLAY_FAILED']

/**
 * @param {string} status
 * @returns {boolean}
 */
export function isOpenDeadLetter(status) {
  return OPEN_DEAD_LETTER_STATUSES.includes(status)
}

/**
 * `DeadLetterView` — id = `"<dltTopic>:<partition>:<offset>"`.
 * @typedef {object} DeadLetterView
 * @property {string} id
 * @property {string} dltTopic
 * @property {string} originalTopic
 * @property {number} originalPartition
 * @property {number} originalOffset
 * @property {string} consumerGroup
 * @property {string | null} key
 * @property {string | null} eventId Null when the record could not be decoded at all.
 * @property {string | null} eventType
 * @property {number | null} eventVersion
 * @property {string | null} occurredAt
 * @property {string} deadLetteredAt The DLT record's timestamp.
 * @property {string | null} exceptionClass
 * @property {string | null} exceptionMessage
 * @property {string | null} payloadPreview Decoded through the Avro deserializer, ≤ 500 chars.
 * @property {DeadLetterStatus} status
 * @property {number} attempts
 * @property {string | null} lastError
 * @property {string | null} lastActionBy
 * @property {string | null} lastActionAt
 */

/**
 * `GET …/dead-letters/{id}`: the view plus the decoded payload and the first 4 KB of the stack trace.
 * @typedef {DeadLetterView & { payload?: unknown, stackTrace?: string | null }} DeadLetterDetail
 */

/**
 * @typedef {object} DeadLetterListParams
 * @property {DeadLetterStatus | string} [status] One status, or several comma-separated (`PENDING,REPLAY_FAILED`); absent = every status.
 * @property {string} [consumerGroup]
 * @property {string} [eventType]
 * @property {number} [page]
 * @property {number} [size] ≤ 100.
 */

/**
 * @typedef {object} ReplayAllRequest
 * @property {string} [consumerGroup]
 * @property {string} [eventType]
 */

/**
 * PENDING and REPLAY_FAILED only, oldest first, at most 100 per call.
 * @typedef {object} ReplayAllResult
 * @property {number} attempted
 * @property {number} replayed
 * @property {number} failed
 */

export const DEAD_LETTER_LIMITS = { discardReason: 300, pageSize: 100, replayAllBatch: 100 }

export const DEAD_LETTER_ERRORS = { notFound: 'DEAD_LETTER_NOT_FOUND', closed: 'DEAD_LETTER_CLOSED' }

/** Kafka Connect connector / task states. */
export const CONNECTOR_STATES = ['RUNNING', 'FAILED', 'PAUSED', 'UNASSIGNED', 'RESTARTING', 'STOPPED']
/** @typedef {(typeof CONNECTOR_STATES)[number]} ConnectorState */

/**
 * @typedef {object} ConnectStateEntry
 * @property {ConnectorState | (string & {})} state
 * @property {string} [worker_id]
 * @property {string} [trace] Java stack trace, present on FAILED.
 */

/**
 * Kafka Connect's `/connectors?expand=status`, passed through by the gateway as is.
 * @typedef {Record<string, { status?: { name?: string, connector?: ConnectStateEntry, tasks?: (ConnectStateEntry & { id: number })[], type?: string } }>} ConnectorsResponse
 */

/**
 * @typedef {object} ConnectorTaskHealth
 * @property {number} id
 * @property {string} state
 * @property {string | null} workerId
 * @property {string | null} trace
 */

/**
 * One connector, normalised for the UI.
 * @typedef {object} ConnectorHealth
 * @property {string} name
 * @property {string | null} type
 * @property {string} state
 * @property {string | null} workerId
 * @property {string | null} trace
 * @property {ConnectorTaskHealth[]} tasks
 */

export const opsKeys = {
  all: ['admin', 'ops'],
  deadLetters: () => [...opsKeys.all, 'dead-letters'],
  /** Every query of one service (lists, details, counts) — refreshed after its replays and discards. */
  service: (service) => [...opsKeys.deadLetters(), service],
  list: (service, filters) => [...opsKeys.service(service), 'list', filters],
  detail: (service, id) => [...opsKeys.service(service), 'detail', id],
  pendingCount: (service) => [...opsKeys.service(service), 'pending-count'],
  connectors: () => [...opsKeys.all, 'connectors'],
}

/** @param {DeadLetterService} service */
const deadLettersBase = (service) => `/admin/ops/${service}/dead-letters`

/**
 * Newest first; only this service's own consumer groups.
 * @param {DeadLetterService} service
 * @param {DeadLetterListParams} params
 * @returns {Promise<PageResponse<DeadLetterView>>}
 */
export async function listDeadLetters(service, params) {
  return (await httpClient.get(deadLettersBase(service), { params: cleanParams(params) })).data
}

/**
 * 404 DEAD_LETTER_NOT_FOUND.
 * @param {DeadLetterService} service
 * @param {string} id
 * @returns {Promise<DeadLetterDetail>}
 */
export async function fetchDeadLetter(service, id) {
  return (await httpClient.get(`${deadLettersBase(service)}/${segment(id)}`)).data
}

/**
 * The outcome is in the answer: REPLAYED, or REPLAY_FAILED with `lastError`. 409 DEAD_LETTER_CLOSED (`currentStatus`).
 * @param {DeadLetterService} service
 * @param {string} id
 * @returns {Promise<DeadLetterView>}
 */
export async function replayDeadLetter(service, id) {
  return (await httpClient.post(`${deadLettersBase(service)}/${segment(id)}/replay`, undefined, { timeout: 30_000 }))
    .data
}

/**
 * Reason required, ≤ 300 → DISCARDED. 409 DEAD_LETTER_CLOSED (`currentStatus`).
 * @param {DeadLetterService} service
 * @param {string} id
 * @param {string} reason
 * @returns {Promise<DeadLetterView>}
 */
export async function discardDeadLetter(service, id, reason) {
  return (await httpClient.post(`${deadLettersBase(service)}/${segment(id)}/discard`, { reason })).data
}

/**
 * Replays the open dead letters (optionally of one consumer group / event type) synchronously, so allow time.
 * @param {DeadLetterService} service
 * @param {ReplayAllRequest} body
 * @returns {Promise<ReplayAllResult>}
 */
export async function replayAllDeadLetters(service, body) {
  return (await httpClient.post(`${deadLettersBase(service)}/replay-all`, cleanParams(body), { timeout: 60_000 })).data
}

/**
 * Kafka Connect's states of every connector and task.
 * @returns {Promise<ConnectorHealth[]>}
 */
export async function fetchConnectors() {
  return toConnectorHealth((await httpClient.get('/admin/system/connectors')).data)
}

/**
 * Restarts the connector's failed instance and tasks (`includeTasks=true&onlyFailed=true`, added by the gateway).
 * @param {string} name
 * @returns {Promise<void>}
 */
export async function restartConnector(name) {
  await httpClient.post(`/admin/system/connectors/${segment(name)}/restart`)
}

/**
 * Kafka Connect's map → a list sorted by name; a missing status reads UNASSIGNED.
 * @param {ConnectorsResponse | null | undefined} response
 * @returns {ConnectorHealth[]}
 */
export function toConnectorHealth(response) {
  if (!response || typeof response !== 'object') return []
  return Object.entries(response)
    .map(([name, entry]) => {
      const status = entry.status
      return {
        name: status?.name ?? name,
        type: status?.type ?? null,
        state: status?.connector?.state ?? 'UNASSIGNED',
        workerId: status?.connector?.worker_id ?? null,
        trace: status?.connector?.trace ?? null,
        tasks: (status?.tasks ?? [])
          .map((task) => ({
            id: task.id,
            state: task.state,
            workerId: task.worker_id ?? null,
            trace: task.trace ?? null,
          }))
          .sort((a, b) => a.id - b.id),
      }
    })
    .sort((a, b) => a.name.localeCompare(b.name))
}

/**
 * No events flow unless the connector AND every task run (a RUNNING connector with no task is stuck too).
 * @param {ConnectorHealth} connector
 * @returns {boolean}
 */
export function isConnectorHealthy(connector) {
  return (
    connector.state === 'RUNNING' &&
    connector.tasks.length > 0 &&
    connector.tasks.every((task) => task.state === 'RUNNING')
  )
}

/**
 * Restart applies: the connector or one of its tasks FAILED.
 * @param {ConnectorHealth} connector
 * @returns {boolean}
 */
export function hasFailure(connector) {
  return connector.state === 'FAILED' || connector.tasks.some((task) => task.state === 'FAILED')
}
