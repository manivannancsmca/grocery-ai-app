import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import { adminOrderKeys } from '../admin-orders/api'
import { ADMIN_MONITOR_FALLBACK_POLL_MS } from '../admin-orders/hooks'
import { orderKeys } from '../orders/api'
import { fetchOrderPayment, paymentKeys } from '../payments/api'
import {
  type AdminPaymentListParams,
  adminPaymentKeys,
  createRefund,
  type CreateRefundRequest,
  fetchAdminPayment,
  fetchPaymentReconciliation,
  listAdminPayments,
} from './api'

/** The reconciliation job runs every 2 minutes (PAYMENT_RECONCILE_INTERVAL). */
export const RECONCILIATION_POLL_MS = 60_000

/** Refreshed by live `ADMIN_ORDERS_CHANGED` signals (every payment event); polled only while the stream is down. */
export function useAdminPayments(params: AdminPaymentListParams) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: adminPaymentKeys.list(params),
    queryFn: () => listAdminPayments(params),
    placeholderData: keepPreviousData,
    refetchInterval: live ? false : ADMIN_MONITOR_FALLBACK_POLL_MS,
  })
}

/** Phase 10: last run of the payment reconciliation job; no retries (a small, optional panel). */
export function usePaymentReconciliation() {
  return useQuery({
    queryKey: adminPaymentKeys.reconciliation(),
    queryFn: fetchPaymentReconciliation,
    refetchInterval: RECONCILIATION_POLL_MS,
    retry: false,
  })
}

export function useAdminPayment(id: string) {
  return useQuery({ queryKey: adminPaymentKeys.detail(id), queryFn: () => fetchAdminPayment(id) })
}

/** The payment of one order (`404 PAYMENT_NOT_FOUND` = none yet; the app's retry policy never retries a 4xx). */
export function useOrderPayment(orderId: string) {
  return useQuery({
    queryKey: paymentKeys.forOrder(orderId),
    queryFn: () => fetchOrderPayment(orderId),
  })
}

/** `POST /api/admin/refunds` with the caller's Idempotency-Key; payments and orders are refreshed afterwards. */
export function useCreateRefund() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: CreateRefundRequest; idempotencyKey: string }) => createRefund(body, idempotencyKey),
    onSettled: (_data, _error, { body }) =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: adminPaymentKeys.all }),
        queryClient.invalidateQueries({ queryKey: paymentKeys.forOrder(body.orderId) }),
        queryClient.invalidateQueries({ queryKey: adminOrderKeys.all }),
        queryClient.invalidateQueries({ queryKey: orderKeys.detail(body.orderId) }),
      ]),
  })
}
