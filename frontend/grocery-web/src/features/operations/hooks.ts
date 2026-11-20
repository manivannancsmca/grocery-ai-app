import { keepPreviousData, useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo } from 'react'
import {
  DEAD_LETTER_LIMITS,
  DEAD_LETTER_SERVICES,
  DEAD_LETTER_STATUSES,
  type DeadLetterDetail,
  type DeadLetterService,
  type DeadLetterStatus,
  type DeadLetterView,
  discardDeadLetter,
  fetchConnectors,
  fetchDeadLetter,
  listDeadLetters,
  opsKeys,
  replayAllDeadLetters,
  type ReplayAllRequest,
  replayDeadLetter,
  restartConnector,
} from './api'

/** No live signal exists for dead letters or connectors: modest polling instead. */
export const DEAD_LETTER_POLL_MS = 30_000
/** The connect-watchdog checks every 30 s; the page checks twice as often. */
export const CONNECTOR_POLL_MS = 15_000
/** The dashboard's pipeline card. */
export const PIPELINE_POLL_MS = 60_000

export interface DeadLetterFilters {
  /** null = every service. */
  service: DeadLetterService | null
  /** At least one; all four = no status filter. */
  statuses: readonly DeadLetterStatus[]
  /** Exact values, as the services filter them. */
  eventType: string
  consumerGroup: string
}

/** A dead letter with the service that owns it (ids are unique per service only). */
export interface DeadLetterRow extends DeadLetterView {
  service: DeadLetterService
}

interface ServiceDeadLetters {
  rows: DeadLetterRow[]
  /** Matching dead letters at the service (more than `rows` when the newest 100 per status were cut). */
  total: number
}

export interface ServiceDeadLetterState {
  service: DeadLetterService
  status: 'loading' | 'ok' | 'unavailable'
  /** Rows loaded from this service. */
  count: number
  total: number
  error: Error | null
}

export interface DeadLetterOverview {
  /** Every loaded row of every reachable service, newest first. */
  rows: DeadLetterRow[]
  services: ServiceDeadLetterState[]
  unavailable: ServiceDeadLetterState[]
  /** Services whose list was cut at the page size. */
  truncated: ServiceDeadLetterState[]
  /** Nothing loaded yet from any service. */
  loading: boolean
  fetching: boolean
  /** Rows of the previous filters are shown while the new ones load. */
  changingFilters: boolean
  /** Newest successful load, ms. */
  updatedAt: number
  refetch: () => void
}

/** One request per service: the selected statuses comma-separated; all four (or none) → no status filter. */
async function loadServiceDeadLetters(
  service: DeadLetterService,
  filters: Omit<DeadLetterFilters, 'service'>,
): Promise<ServiceDeadLetters> {
  const allStatuses = filters.statuses.length === 0 || filters.statuses.length === DEAD_LETTER_STATUSES.length
  const page = await listDeadLetters(service, {
    status: allStatuses ? undefined : DEAD_LETTER_STATUSES.filter((status) => filters.statuses.includes(status)).join(','),
    eventType: filters.eventType || undefined,
    consumerGroup: filters.consumerGroup || undefined,
    page: 0,
    size: DEAD_LETTER_LIMITS.pageSize,
  })
  return { rows: page.content.map((view) => ({ ...view, service })), total: page.totalElements }
}

function timeOf(value: string | null | undefined): number {
  const time = value ? Date.parse(value) : Number.NaN
  return Number.isNaN(time) ? 0 : time
}

/** Newest dead-lettered first; ties keep the services' order. */
export function mergeNewestFirst(lists: readonly DeadLetterRow[][]): DeadLetterRow[] {
  return lists.flat().sort((a, b) => timeOf(b.deadLetteredAt) - timeOf(a.deadLetteredAt))
}

/**
 * Phase 10 §1: the dead letters of every service (or one), fetched in parallel through the gateway and merged
 * newest first. A service that fails or cannot be reached is reported as unavailable; the others still show.
 */
export function useDeadLetters(filters: DeadLetterFilters): DeadLetterOverview {
  const services: readonly DeadLetterService[] = filters.service ? [filters.service] : DEAD_LETTER_SERVICES
  const statusKey = [...filters.statuses].sort().join(',')
  const listFilters = useMemo(
    () => ({
      statuses: statusKey ? (statusKey.split(',') as DeadLetterStatus[]) : [],
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

  const states: ServiceDeadLetterState[] = services.map((service, index) => {
    const result = results[index]
    if (!result || (result.isPending && !result.data)) return { service, status: 'loading', count: 0, total: 0, error: null }
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

export function useDeadLetter(service: DeadLetterService, id: string) {
  return useQuery({ queryKey: opsKeys.detail(service, id), queryFn: () => fetchDeadLetter(service, id) })
}

interface DeadLetterRef {
  service: DeadLetterService
  id: string
}

/** Puts the answer into the open drawer, then refreshes everything of that service (also after a 409). */
function useDeadLetterAction<TVariables extends DeadLetterRef>(action: (variables: TVariables) => Promise<DeadLetterView>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: action,
    onSuccess: (view, { service, id }) =>
      queryClient.setQueryData<DeadLetterDetail>(opsKeys.detail(service, id), (current) => (current ? { ...current, ...view } : current)),
    onSettled: (_data, _error, { service }) => queryClient.invalidateQueries({ queryKey: opsKeys.service(service) }),
  })
}

export function useReplayDeadLetter() {
  return useDeadLetterAction(({ service, id }: DeadLetterRef) => replayDeadLetter(service, id))
}

export function useDiscardDeadLetter() {
  return useDeadLetterAction(({ service, id, reason }: DeadLetterRef & { reason: string }) => discardDeadLetter(service, id, reason))
}

export function useReplayAllDeadLetters() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ service, body }: { service: DeadLetterService; body: ReplayAllRequest }) => replayAllDeadLetters(service, body),
    onSettled: (_data, _error, { service }) => queryClient.invalidateQueries({ queryKey: opsKeys.service(service) }),
  })
}

export interface PendingDeadLetterSummary {
  /** PENDING dead letters across the services that answered. */
  total: number
  unavailable: DeadLetterService[]
  loading: boolean
}

/** Dashboard: `totalElements` of each service's PENDING list (page size 1). */
export function usePendingDeadLetterSummary(): PendingDeadLetterSummary {
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

export function useConnectors(refetchInterval: number = CONNECTOR_POLL_MS) {
  return useQuery({ queryKey: opsKeys.connectors(), queryFn: fetchConnectors, refetchInterval, retry: false })
}

export function useRestartConnector() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => restartConnector(name),
    onSettled: () => queryClient.invalidateQueries({ queryKey: opsKeys.connectors() }),
  })
}
