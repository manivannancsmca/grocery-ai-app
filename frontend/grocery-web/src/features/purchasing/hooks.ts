import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { inventoryKeys } from '../vendor-inventory/api'
import {
  type AdminPurchaseOrderListParams,
  createPurchaseOrder,
  createSupplier,
  deactivateSupplier,
  fetchPurchaseOrder,
  listAdminPurchaseOrders,
  listPurchaseOrders,
  listSuppliers,
  type PurchaseOrderListParams,
  type PurchaseOrderRequest,
  type PurchaseOrderTransition,
  type PurchaseOrderView,
  purchasingKeys,
  receivePurchaseOrder,
  type ReceiveRequest,
  type SupplierRequest,
  transitionPurchaseOrder,
  updatePurchaseOrder,
  updateSupplier,
} from './api'

export function useSuppliers() {
  return useQuery({ queryKey: purchasingKeys.suppliers(), queryFn: listSuppliers })
}

export function useSaveSupplier() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: string | null; body: SupplierRequest }) => (id ? updateSupplier(id, body) : createSupplier(body)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: purchasingKeys.suppliers() }),
  })
}

export function useDeactivateSupplier() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => deactivateSupplier(id),
    onSettled: () => queryClient.invalidateQueries({ queryKey: purchasingKeys.suppliers() }),
  })
}

export function usePurchaseOrders(params: PurchaseOrderListParams) {
  return useQuery({ queryKey: purchasingKeys.orderList(params), queryFn: () => listPurchaseOrders(params), placeholderData: keepPreviousData })
}

export function usePurchaseOrder(id: string | undefined) {
  return useQuery({ queryKey: purchasingKeys.order(id ?? ''), queryFn: () => fetchPurchaseOrder(id ?? ''), enabled: Boolean(id) })
}

/** The answer goes into the detail cache; the lists refresh. */
function useStoreOrder() {
  const queryClient = useQueryClient()
  return async (order: PurchaseOrderView) => {
    queryClient.setQueryData(purchasingKeys.order(order.id), order)
    await queryClient.invalidateQueries({ queryKey: [...purchasingKeys.orders(), 'list'] })
  }
}

export function useSavePurchaseOrder() {
  const store = useStoreOrder()
  return useMutation({
    mutationFn: ({ id, body }: { id: string | null; body: PurchaseOrderRequest }) => (id ? updatePurchaseOrder(id, body) : createPurchaseOrder(body)),
    onSuccess: store,
  })
}

/** Send, cancel or close. After a 409 the order is re-read (its status changed elsewhere). */
export function usePurchaseOrderTransition() {
  const queryClient = useQueryClient()
  const store = useStoreOrder()
  return useMutation({
    mutationFn: ({ id, transition }: { id: string; transition: PurchaseOrderTransition }) => transitionPurchaseOrder(id, transition),
    onSuccess: store,
    onError: (_error, { id }) => queryClient.invalidateQueries({ queryKey: purchasingKeys.order(id) }),
  })
}

/** Receive goods; the vendor's stock changes too (inventory-service consumes `GoodsReceived`). */
export function useReceivePurchaseOrder() {
  const queryClient = useQueryClient()
  const store = useStoreOrder()
  return useMutation({
    mutationFn: ({ id, body, idempotencyKey }: { id: string; body: ReceiveRequest; idempotencyKey: string }) =>
      receivePurchaseOrder(id, body, idempotencyKey),
    onSuccess: async (order) => {
      await store(order)
      await queryClient.invalidateQueries({ queryKey: inventoryKeys.all })
    },
  })
}

export function useAdminPurchaseOrders(params: AdminPurchaseOrderListParams) {
  return useQuery({ queryKey: purchasingKeys.adminList(params), queryFn: () => listAdminPurchaseOrders(params), placeholderData: keepPreviousData })
}
