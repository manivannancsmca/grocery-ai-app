import { humanizeToken } from '../../shared/utils/format'
import { SERVICE_LABELS } from '../system/api'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('./api').ConnectorState} ConnectorState */
/** @typedef {import('./api').DeadLetterService} DeadLetterService */
/** @typedef {import('./api').DeadLetterStatus} DeadLetterStatus */

/** @type {Record<DeadLetterStatus, string>} */
export const DEAD_LETTER_STATUS_LABELS = {
  PENDING: 'Pending',
  REPLAY_FAILED: 'Replay failed',
  REPLAYED: 'Replayed',
  DISCARDED: 'Discarded',
}

/** @type {Record<DeadLetterStatus, BadgeTone>} */
export const DEAD_LETTER_STATUS_TONES = {
  PENDING: 'amber',
  REPLAY_FAILED: 'red',
  REPLAYED: 'green',
  DISCARDED: 'gray',
}

/**
 * @param {string} status
 * @returns {string}
 */
export function deadLetterStatusLabel(status) {
  return status in DEAD_LETTER_STATUS_LABELS ? DEAD_LETTER_STATUS_LABELS[status] : humanizeToken(status)
}

/**
 * @param {string} status
 * @returns {BadgeTone}
 */
export function deadLetterStatusTone(status) {
  return status in DEAD_LETTER_STATUS_TONES ? DEAD_LETTER_STATUS_TONES[status] : 'gray'
}

/**
 * @param {DeadLetterService} service
 * @returns {string}
 */
export function serviceName(service) {
  return SERVICE_LABELS[service].name
}

/** @type {Record<ConnectorState, string>} */
export const CONNECTOR_STATE_LABELS = {
  RUNNING: 'Running',
  FAILED: 'Failed',
  PAUSED: 'Paused',
  UNASSIGNED: 'Unassigned',
  RESTARTING: 'Restarting',
  STOPPED: 'Stopped',
}

/** @type {Record<ConnectorState, BadgeTone>} */
export const CONNECTOR_STATE_TONES = {
  RUNNING: 'green',
  FAILED: 'red',
  PAUSED: 'amber',
  UNASSIGNED: 'gray',
  RESTARTING: 'blue',
  STOPPED: 'gray',
}

/**
 * @param {string} state
 * @returns {string}
 */
export function connectorStateLabel(state) {
  return state in CONNECTOR_STATE_LABELS ? CONNECTOR_STATE_LABELS[state] : humanizeToken(state)
}

/**
 * @param {string} state
 * @returns {BadgeTone}
 */
export function connectorStateTone(state) {
  return state in CONNECTOR_STATE_TONES ? CONNECTOR_STATE_TONES[state] : 'gray'
}

/**
 * `com.grocery.common.kafka.InvalidEventException` → `InvalidEventException`.
 * @param {string | null | undefined} className
 * @returns {string | null}
 */
export function shortClassName(className) {
  if (!className) return null
  return className.slice(className.lastIndexOf('.') + 1) || className
}

/**
 * The decoded payload as indented JSON; a JSON string is parsed first, anything else is shown as it is.
 * @param {unknown} payload
 * @returns {string | null}
 */
export function prettyPayload(payload) {
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
