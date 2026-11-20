import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { hasErrorCode } from '../../shared/api/problemDetail'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import {
  acceptOffer,
  completeDelivery,
  DELIVERY_ERRORS,
  deliveryKeys,
  failDelivery,
  fetchActiveDelivery,
  fetchDeliveryHistorySummary,
  fetchPartnerStats,
  fetchPartnerStatus,
  listDeliveryHistory,
  listOffers,
  pickUpDelivery,
  rejectOffer,
  setAvailability,
} from './api'

/** @typedef {import('./api').DeliveryView} DeliveryView */
/** @typedef {import('./api').FailDeliveryRequest} FailDeliveryRequest */
/** @typedef {import('./api').HistoryParams} HistoryParams */
/** @typedef {import('./api').StatsRange} StatsRange */

/**
 * The partner app follows the live stream: DELIVERY_OFFER / DELIVERY_OFFERS_CHANGED refresh the offers,
 * DELIVERY_UPDATED / PICKUP_READY / DELIVERY_CANCELLED / DELIVERY_REASSIGNED the active delivery (the realtime
 * layer invalidates them). While the stream is down, offers (valid 30 s by default) are polled every 10 s and the rest
 * every 30 s.
 */
export const OFFERS_FALLBACK_POLL_MS = 10_000
export const PARTNER_FALLBACK_POLL_MS = 30_000

/** The partner's status (online, last location, active delivery). A 403 PARTNER_NOT_ACTIVE is not retried (4xx). */
export function usePartnerStatus() {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: deliveryKeys.status(),
    queryFn: fetchPartnerStatus,
    refetchInterval: live ? false : PARTNER_FALLBACK_POLL_MS,
  })
}

/** Online / offline. The answer replaces the cached status; going online also re-reads the offers. */
export function useSetAvailability() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (online) => setAvailability(online),
    onSuccess: (status) => {
      queryClient.setQueryData(deliveryKeys.status(), status)
      if (!status.online) queryClient.setQueryData(deliveryKeys.offers(), [])
      void queryClient.invalidateQueries({ queryKey: deliveryKeys.offers() })
    },
    onError: () => queryClient.invalidateQueries({ queryKey: deliveryKeys.status() }),
  })
}

/**
 * The partner's pending offers (only while online — offline partners get none).
 * @param {boolean} enabled
 */
export function useOffers(enabled) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: deliveryKeys.offers(),
    queryFn: listOffers,
    enabled,
    refetchInterval: live ? false : OFFERS_FALLBACK_POLL_MS,
  })
}

/**
 * @param {ReturnType<typeof useQueryClient>} queryClient
 * @param {string} offerId
 * @returns {void}
 */
function withoutOffer(queryClient, offerId) {
  queryClient.setQueryData(deliveryKeys.offers(), (offers) => offers?.filter((offer) => offer.offerId !== offerId))
}

/**
 * Accept: the delivery becomes the active one at once. A 409 / 404 (somebody else was faster, the offer
 * expired) removes the offer; offers and status are re-read either way.
 */
export function useAcceptOffer() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (offerId) => acceptOffer(offerId),
    onSuccess: (delivery) => {
      queryClient.setQueryData(deliveryKeys.active(), delivery)
      // A partner with an active delivery gets no offers (contract §2).
      queryClient.setQueryData(deliveryKeys.offers(), [])
    },
    onError: (error, offerId) => {
      if (hasErrorCode(error, DELIVERY_ERRORS.offerNotAvailable, DELIVERY_ERRORS.offerNotFound))
        withoutOffer(queryClient, offerId)
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: deliveryKeys.offers() }),
        queryClient.invalidateQueries({ queryKey: deliveryKeys.status() }),
        queryClient.invalidateQueries({ queryKey: deliveryKeys.active() }),
      ]),
  })
}

/** Reject (idempotent): the offer leaves the list at once. */
export function useRejectOffer() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (offerId) => rejectOffer(offerId),
    onSuccess: (_result, offerId) => withoutOffer(queryClient, offerId),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: deliveryKeys.offers() }),
        queryClient.invalidateQueries({ queryKey: deliveryKeys.status() }),
      ]),
  })
}

/** The active delivery, or null. */
export function useActiveDelivery() {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: deliveryKeys.active(),
    queryFn: fetchActiveDelivery,
    refetchInterval: live ? false : PARTNER_FALLBACK_POLL_MS,
  })
}

/** @typedef {{ type: 'pickup' } | { type: 'deliver' } | { type: 'fail', body: FailDeliveryRequest }} DeliveryAction */

/**
 * @param {string} id
 * @param {DeliveryAction} action
 * @returns {Promise<DeliveryView>}
 */
function runAction(id, action) {
  switch (action.type) {
    case 'pickup':
      return pickUpDelivery(id)
    case 'deliver':
      return completeDelivery(id)
    case 'fail':
      return failDelivery(id, action.body)
  }
}

const FINISHED = new Set(['DELIVERED', 'FAILED', 'CANCELLED'])

/**
 * Picked up / delivered / couldn't deliver. A finished delivery leaves the screen at once; the active
 * delivery, the status and the history are re-read afterwards — also after a 409, to show what happened.
 */
export function useDeliveryAction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ deliveryId, action }) => runAction(deliveryId, action),
    onSuccess: (delivery) => {
      queryClient.setQueryData(deliveryKeys.active(), FINISHED.has(delivery.status) ? null : delivery)
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: deliveryKeys.active() }),
        queryClient.invalidateQueries({ queryKey: deliveryKeys.status() }),
        queryClient.invalidateQueries({ queryKey: deliveryKeys.history() }),
      ]),
  })
}

/** @param {HistoryParams} params */
export function useDeliveryHistory(params) {
  return useQuery({
    queryKey: deliveryKeys.historyList(params),
    queryFn: () => listDeliveryHistory(params),
    placeholderData: keepPreviousData,
  })
}

/**
 * The partner's own statistics for the dashboard's charts; the previous range stays on screen while a new one loads.
 * @param {StatsRange} range
 */
export function usePartnerStats(range) {
  return useQuery({
    queryKey: deliveryKeys.stats(range),
    queryFn: () => fetchPartnerStats(range),
    placeholderData: keepPreviousData,
  })
}

export function useDeliveryHistorySummary() {
  return useQuery({
    queryKey: deliveryKeys.historySummary(),
    queryFn: fetchDeliveryHistorySummary,
  })
}
