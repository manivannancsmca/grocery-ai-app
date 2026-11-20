import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { HealthService } from '../system/api'

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
] as const satisfies readonly HealthService[]
export type DeadLetterService = (typeof DEAD_LETTER_SERVICES)[number]

export function isDeadLetterService(value: unknown): value is DeadLetterService {
  return typeof value === 'string' && (DEAD_LETTER_SERVICES as readonly string[]).includes(value)
}

export const DEAD_LETTER_STATUSES = ['PENDING', 'REPLAY_FAILED', 'REPLAYED', 'DISCARDED'] as const
export type DeadLetterStatus = (typeof DEAD_LETTER_STATUSES)[number]

/** Still to be dealt with: replay and discard apply (a closed one answers 409 DEAD_LETTER_CLOSED). */
export const OPEN_DEAD_LETTER_STATUSES: readonly DeadLetterStatus[] = ['PENDING', 'REPLAY_FAILED']

export function isOpenDeadLetter(status: string): boolean {
  return (OPEN_DEAD_LETTER_STATUSES as readonly string[]).includes(status)
}

/** `DeadLetterView` — id = `"<dltTopic>:<partition>:<offset>"`. */
export interface DeadLetterView {
  id: string
  dltTopic: string
  originalTopic: string
  originalPartition: number
  originalOffset: number
  consumerGroup: string
  key: string | null
  /** Null when the record could not be decoded at all. */
  eventId: string | null
  eventType: string | null
  eventVersion: number | null
  occurredAt: string | null
  /** The DLT record's timestamp. */
  deadLetteredAt: string
  exceptionClass: string | null
  exceptionMessage: string | null
  /** Decoded through the Avro deserializer, ≤ 500 chars. */
  payloadPreview: string | null
  status: DeadLetterStatus
  attempts: number
  lastError: string | null
  lastActionBy: string | null
  lastActionAt: string | null
}

/** `GET …/dead-letters/{id}`: the view plus the decoded payload and the first 4 KB of the stack trace. */
export interface DeadLetterDetail extends DeadLetterView {
  payload?: unknown
  stackTrace?: string | null
}

export interface DeadLetterListParams {
  /** One status, or several comma-separated (`PENDING,REPLAY_FAILED`); absent = every status. */
  status?: DeadLetterStatus | string
  consumerGroup?: string
  eventType?: string
  page?: number
  /** ≤ 100. */
  size?: number
}

export interface ReplayAllRequest {
  consumerGroup?: string
  eventType?: string
}

/** PENDING and REPLAY_FAILED only, oldest first, at most 100 per call. */
export interface ReplayAllResult {
  attempted: number
  replayed: number
  failed: number
}

export const DEAD_LETTER_LIMITS = { discardReason: 300, pageSize: 100, replayAllBatch: 100 } as const

export const DEAD_LETTER_ERRORS = { notFound: 'DEAD_LETTER_NOT_FOUND', closed: 'DEAD_LETTER_CLOSED' } as const

/** Kafka Connect connector / task states. */
export const CONNECTOR_STATES = ['RUNNING', 'FAILED', 'PAUSED', 'UNASSIGNED', 'RESTARTING', 'STOPPED'] as const
export type ConnectorState = (typeof CONNECTOR_STATES)[number]

interface ConnectStateEntry {
  state: ConnectorState | (string & {})
  worker_id?: string
  /** Java stack trace, present on FAILED. */
  trace?: string
}

/** Kafka Connect's `/connectors?expand=status`, passed through by the gateway as is. */
export type ConnectorsResponse = Record<
  string,
  {
    status?: {
      name?: string
      connector?: ConnectStateEntry
      tasks?: (ConnectStateEntry & { id: number })[]
      type?: string
    }
  }
>

export interface ConnectorTaskHealth {
  id: number
  state: string
  workerId: string | null
  trace: string | null
}

/** One connector, normalised for the UI. */
export interface ConnectorHealth {
  name: string
  type: string | null
  state: string
  workerId: string | null
  trace: string | null
  tasks: ConnectorTaskHealth[]
}

export const opsKeys = {
  all: ['admin', 'ops'] as const,
  deadLetters: () => [...opsKeys.all, 'dead-letters'] as const,
  /** Every query of one service (lists, details, counts) — refreshed after its replays and discards. */
  service: (service: DeadLetterService) => [...opsKeys.deadLetters(), service] as const,
  list: (service: DeadLetterService, filters: object) => [...opsKeys.service(service), 'list', filters] as const,
  detail: (service: DeadLetterService, id: string) => [...opsKeys.service(service), 'detail', id] as const,
  pendingCount: (service: DeadLetterService) => [...opsKeys.service(service), 'pending-count'] as const,
  connectors: () => [...opsKeys.all, 'connectors'] as const,
}

const deadLettersBase = (service: DeadLetterService) => `/admin/ops/${service}/dead-letters`

/** Newest first; only this service's own consumer groups. */
export async function listDeadLetters(service: DeadLetterService, params: DeadLetterListParams): Promise<PageResponse<DeadLetterView>> {
  return (await httpClient.get<PageResponse<DeadLetterView>>(deadLettersBase(service), { params: cleanParams(params) })).data
}

/** 404 DEAD_LETTER_NOT_FOUND. */
export async function fetchDeadLetter(service: DeadLetterService, id: string): Promise<DeadLetterDetail> {
  return (await httpClient.get<DeadLetterDetail>(`${deadLettersBase(service)}/${segment(id)}`)).data
}

/** The outcome is in the answer: REPLAYED, or REPLAY_FAILED with `lastError`. 409 DEAD_LETTER_CLOSED (`currentStatus`). */
export async function replayDeadLetter(service: DeadLetterService, id: string): Promise<DeadLetterView> {
  return (await httpClient.post<DeadLetterView>(`${deadLettersBase(service)}/${segment(id)}/replay`, undefined, { timeout: 30_000 })).data
}

/** Reason required, ≤ 300 → DISCARDED. 409 DEAD_LETTER_CLOSED (`currentStatus`). */
export async function discardDeadLetter(service: DeadLetterService, id: string, reason: string): Promise<DeadLetterView> {
  return (await httpClient.post<DeadLetterView>(`${deadLettersBase(service)}/${segment(id)}/discard`, { reason })).data
}

/** Replays the open dead letters (optionally of one consumer group / event type) synchronously, so allow time. */
export async function replayAllDeadLetters(service: DeadLetterService, body: ReplayAllRequest): Promise<ReplayAllResult> {
  return (await httpClient.post<ReplayAllResult>(`${deadLettersBase(service)}/replay-all`, cleanParams(body), { timeout: 60_000 })).data
}

/** Kafka Connect's states of every connector and task. */
export async function fetchConnectors(): Promise<ConnectorHealth[]> {
  return toConnectorHealth((await httpClient.get<ConnectorsResponse>('/admin/system/connectors')).data)
}

/** Restarts the connector's failed instance and tasks (`includeTasks=true&onlyFailed=true`, added by the gateway). */
export async function restartConnector(name: string): Promise<void> {
  await httpClient.post(`/admin/system/connectors/${segment(name)}/restart`)
}

/** Kafka Connect's map → a list sorted by name; a missing status reads UNASSIGNED. */
export function toConnectorHealth(response: ConnectorsResponse | null | undefined): ConnectorHealth[] {
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
          .map((task) => ({ id: task.id, state: task.state, workerId: task.worker_id ?? null, trace: task.trace ?? null }))
          .sort((a, b) => a.id - b.id),
      }
    })
    .sort((a, b) => a.name.localeCompare(b.name))
}

/** No events flow unless the connector AND every task run (a RUNNING connector with no task is stuck too). */
export function isConnectorHealthy(connector: ConnectorHealth): boolean {
  return connector.state === 'RUNNING' && connector.tasks.length > 0 && connector.tasks.every((task) => task.state === 'RUNNING')
}

/** Restart applies: the connector or one of its tasks FAILED. */
export function hasFailure(connector: ConnectorHealth): boolean {
  return connector.state === 'FAILED' || connector.tasks.some((task) => task.state === 'FAILED')
}
