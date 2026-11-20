import type { BadgeTone } from '../../shared/components/ui'
import { humanizeToken } from '../../shared/utils/format'
import { SERVICE_LABELS } from '../system/api'
import type { ConnectorState, DeadLetterService, DeadLetterStatus } from './api'

export const DEAD_LETTER_STATUS_LABELS: Record<DeadLetterStatus, string> = {
  PENDING: 'Pending',
  REPLAY_FAILED: 'Replay failed',
  REPLAYED: 'Replayed',
  DISCARDED: 'Discarded',
}

export const DEAD_LETTER_STATUS_TONES: Record<DeadLetterStatus, BadgeTone> = {
  PENDING: 'amber',
  REPLAY_FAILED: 'red',
  REPLAYED: 'green',
  DISCARDED: 'gray',
}

export function deadLetterStatusLabel(status: string): string {
  return status in DEAD_LETTER_STATUS_LABELS ? DEAD_LETTER_STATUS_LABELS[status as DeadLetterStatus] : humanizeToken(status)
}

export function deadLetterStatusTone(status: string): BadgeTone {
  return status in DEAD_LETTER_STATUS_TONES ? DEAD_LETTER_STATUS_TONES[status as DeadLetterStatus] : 'gray'
}

export function serviceName(service: DeadLetterService): string {
  return SERVICE_LABELS[service].name
}

export const CONNECTOR_STATE_LABELS: Record<ConnectorState, string> = {
  RUNNING: 'Running',
  FAILED: 'Failed',
  PAUSED: 'Paused',
  UNASSIGNED: 'Unassigned',
  RESTARTING: 'Restarting',
  STOPPED: 'Stopped',
}

export const CONNECTOR_STATE_TONES: Record<ConnectorState, BadgeTone> = {
  RUNNING: 'green',
  FAILED: 'red',
  PAUSED: 'amber',
  UNASSIGNED: 'gray',
  RESTARTING: 'blue',
  STOPPED: 'gray',
}

export function connectorStateLabel(state: string): string {
  return state in CONNECTOR_STATE_LABELS ? CONNECTOR_STATE_LABELS[state as ConnectorState] : humanizeToken(state)
}

export function connectorStateTone(state: string): BadgeTone {
  return state in CONNECTOR_STATE_TONES ? CONNECTOR_STATE_TONES[state as ConnectorState] : 'gray'
}

/** `com.grocery.common.kafka.InvalidEventException` → `InvalidEventException`. */
export function shortClassName(className: string | null | undefined): string | null {
  if (!className) return null
  return className.slice(className.lastIndexOf('.') + 1) || className
}

/** The decoded payload as indented JSON; a JSON string is parsed first, anything else is shown as it is. */
export function prettyPayload(payload: unknown): string | null {
  if (payload === undefined || payload === null) return null
  if (typeof payload === 'string') {
    try {
      return JSON.stringify(JSON.parse(payload), null, 2)
    } catch {
      return payload
    }
  }
  try {
    return JSON.stringify(payload, null, 2)
  } catch {
    return String(payload)
  }
}
