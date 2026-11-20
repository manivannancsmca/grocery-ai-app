import { keepPreviousData, useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo } from 'react'
import {
  DEAD_LETTER_LIMITS,
  DEAD_LETTER_SERVICES,
  DEAD_LETTER_STATUSES,
  discardDeadLetter,
  fetchConnectors,
  fetchDeadLetter,
  listDeadLetters,
  opsKeys,
  replayAllDeadLetters,
  replayDeadLetter,
  restartConnector,
} from './api'

/** @typedef {import('./api').DeadLetterService} DeadLetterService */
/** @typedef {import('./api').DeadLetterStatus} DeadLetterStatus */
/** @typedef {import('./api').DeadLetterView} DeadLetterView */

/** No live signal exists for dead letters or connectors: modest polling instead. */
export const DEAD_LETTER_POLL_MS = 30_000
/** The connect-watchdog checks every 30 s; the page checks twice as often. */
export const CONNECTOR_POLL_MS = 15_000
/** The dashboard's pipeline card. */
export const PIPELINE_POLL_MS = 60_000

/**
 * @typedef {object} DeadLetterFilters
 * @property {DeadLetterService | null} service null = every service.
 * @property {readonly DeadLetterStatus[]} statuses At least one; all four = no status filter.
 * @property {string} eventType Exact values, as the services filter them.
 * @property {string} consumerGroup
 */

/**
 * A dead letter with the service that owns it (ids are unique per service only).
 * @typedef {DeadLetterView & { service: DeadLetterService }} DeadLetterRow
 */

/**
 * @typedef {object} ServiceDeadLetters
 * @property {DeadLetterRow[]} rows
 * @property {number} total Matching dead letters at the service (more than `rows` when the newest 100 per status were cut).
 */

/**
 * @typedef {object} ServiceDeadLetterState
 * @property {DeadLetterService} service
 * @property {'loading' | 'ok' | 'unavailable'} status
 * @property {number} count Rows loaded from this service.
 * @property {number} total
 * @property {Error | null} error
 */

/**
 * @typedef {object} DeadLetterOverview
 * @property {DeadLetterRow[]} rows Every loaded row of every reachable service, newest first.
 * @property {ServiceDeadLetterState[]} services
 * @property {ServiceDeadLetterState[]} unavailable
 * @property {ServiceDeadLetterState[]} truncated Services whose list was cut at the page size.
 * @property {boolean} loading Nothing loaded yet from any service.
 * @property {boolean} fetching
 * @property {boolean} changingFilters Rows of the previous filters are shown while the new ones load.
 * @property {number} updatedAt Newest successful load, ms.
 * @property {() => void} refetch
 */

/**
 * One request per service: the selected statuses comma-separated; all four (or none) → no status filter.
 * @param {DeadLetterService} service
 * @param {Omit<DeadLetterFilters, 'service'>} filters
 * @returns {Promise<ServiceDeadLetters>}
 */
async function loadServiceDeadLetters(service, filters) {
  const allStatuses = filters.statuses.length === 0 || filters.statuses.length === DEAD_LETTER_STATUSES.length
  const page = await listDeadLetters(service, {
    status: allStatuses
      ? undefined
      : DEAD_LETTER_STATUSES.filter((status) => filters.statuses.includes(status)).join(','),
    eventType: filters.eventType || undefined,
    consumerGroup: filters.consumerGroup || undefined,
    page: 0,
    size: DEAD_LETTER_LIMITS.pageSize,
  })
  return { rows: page.content.map((view) => ({ ...view, service })), total: page.totalElements }
}

/**
 * @param {string | null | undefined} value
 * @returns {number}
 */
function timeOf(value) {
  const time = value ? Date.parse(value) : Number.NaN
  return Number.isNaN(time) ? 0 : time
}

/**
 * Newest dead-lettered first; ties keep the services' order.
 * @param {readonly DeadLetterRow[][]} lists
 * @returns {DeadLetterRow[]}
 */
export function mergeNewestFirst(lists) {
  return lists.flat().sort((a, b) => timeOf(b.deadLetteredAt) - timeOf(a.deadLetteredAt))
}

/**
 * Phase 10 §1: the dead letters of every service (or one), fetched in parallel through the gateway and merged
 * newest first. A service that fails or cannot be reached is reported as unavailable; the others still show.
 * @param {DeadLetterFilters} filters
 * @returns {DeadLetterOverview}
 */
export function useDeadLetters(filters) {
  const services = filters.service ? [filters.service] : DEAD_LETTER_SERVICES
  const statusKey = [...filters.statuses].sort().join(',')
  const listFilters = useMemo(
    () => ({
      statuses: statusKey ? statusKey.split(',') : [],
      eventType: filters.eventType.trim(),
      consumerGroup: filters.consumerGroup.trim(),
    }),
    [statusKey, filters.eventType, filters.consumerGroup],
  )

  const results = useQueries({
    queries: services.map((service) => ({
      queryKey: opsKeys.list(service, listFilters),
      queryFn: () => loadServiceDeadLetters(service, listFilters),
      refetchInterval: DEAD_LETTER_POLL_MS,
      placeholderData: keepPreviousData,
      // An unreachable service shows as unavailable at once; the next poll tries again.
      retry: false,
    })),
  })

  const states = services.map((service, index) => {
    const result = results[index]
    if (!result || (result.isPending && !result.data))
      return { service, status: 'loading', count: 0, total: 0, error: null }
    if (result.isError) return { service, status: 'unavailable', count: 0, total: 0, error: result.error }
    return { service, status: 'ok', count: result.data?.rows.length ?? 0, total: result.data?.total ?? 0, error: null }
  })

  const rows = mergeNewestFirst(results.flatMap((result) => (result.isError || !result.data ? [] : [result.data.rows])))

  return {
    rows,
    services: states,
    unavailable: states.filter((state) => state.status === 'unavailable'),
    truncated: states.filter((state) => state.status === 'ok' && state.total > state.count),
    loading: states.every((state) => state.status === 'loading'),
    fetching: results.some((result) => result.isFetching),
    changingFilters: results.some((result) => result.isPlaceholderData),
    updatedAt: Math.max(0, ...results.map((result) => result.dataUpdatedAt)),
    refetch: () => results.forEach((result) => void result.refetch()),
  }
}

/**
 * @param {DeadLetterService} service
 * @param {string} id
 */
export function useDeadLetter(service, id) {
  return useQuery({ queryKey: opsKeys.detail(service, id), queryFn: () => fetchDeadLetter(service, id) })
}

/**
 * @typedef {object} DeadLetterRef
 * @property {DeadLetterService} service
 * @property {string} id
 */

/**
 * Puts the answer into the open drawer, then refreshes everything of that service (also after a 409).
 * @template {DeadLetterRef} TVariables
 * @param {(variables: TVariables) => Promise<DeadLetterView>} action
 */
function useDeadLetterAction(action) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: action,
    onSuccess: (view, { service, id }) =>
      queryClient.setQueryData(opsKeys.detail(service, id), (current) => (current ? { ...current, ...view } : current)),
    onSettled: (_data, _error, { service }) => queryClient.invalidateQueries({ queryKey: opsKeys.service(service) }),
  })
}

export function useReplayDeadLetter() {
  return useDeadLetterAction(({ service, id }) => replayDeadLetter(service, id))
}

export function useDiscardDeadLetter() {
  return useDeadLetterAction(({ service, id, reason }) => discardDeadLetter(service, id, reason))
}

export function useReplayAllDeadLetters() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ service, body }) => replayAllDeadLetters(service, body),
    onSettled: (_data, _error, { service }) => queryClient.invalidateQueries({ queryKey: opsKeys.service(service) }),
  })
}

/**
 * @typedef {object} PendingDeadLetterSummary
 * @property {number} total PENDING dead letters across the services that answered.
 * @property {DeadLetterService[]} unavailable
 * @property {boolean} loading
 */

/**
 * Dashboard: `totalElements` of each service's PENDING list (page size 1).
 * @returns {PendingDeadLetterSummary}
 */
export function usePendingDeadLetterSummary() {
  const results = useQueries({
    queries: DEAD_LETTER_SERVICES.map((service) => ({
      queryKey: opsKeys.pendingCount(service),
      queryFn: async () => (await listDeadLetters(service, { status: 'PENDING', page: 0, size: 1 })).totalElements,
      refetchInterval: PIPELINE_POLL_MS,
      retry: false,
    })),
  })
  return {
    total: results.reduce((sum, result) => sum + (result.data ?? 0), 0),
    unavailable: DEAD_LETTER_SERVICES.filter((_service, index) => results[index]?.isError),
    loading: results.some((result) => result.isPending),
  }
}

/** @param {number} [refetchInterval=CONNECTOR_POLL_MS] */
export function useConnectors(refetchInterval = CONNECTOR_POLL_MS) {
  return useQuery({ queryKey: opsKeys.connectors(), queryFn: fetchConnectors, refetchInterval, retry: false })
}

export function useRestartConnector() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (name) => restartConnector(name),
    onSettled: () => queryClient.invalidateQueries({ queryKey: opsKeys.connectors() }),
  })
}
