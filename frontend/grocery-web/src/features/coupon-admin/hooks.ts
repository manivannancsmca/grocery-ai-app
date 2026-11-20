import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  type CouponListParams,
  type CouponOwner,
  type CouponRequest,
  couponAdminKeys,
  createCoupon,
  fetchCouponUsage,
  listCoupons,
  setCouponActive,
  updateCoupon,
} from './api'

export function useCoupons(owner: CouponOwner, params: CouponListParams) {
  return useQuery({ queryKey: couponAdminKeys.list(owner, params), queryFn: () => listCoupons(owner, params), placeholderData: keepPreviousData })
}

export function useCouponUsage(owner: CouponOwner, id: string, page: number) {
  return useQuery({ queryKey: couponAdminKeys.usage(owner, id, page), queryFn: () => fetchCouponUsage(owner, id, page), placeholderData: keepPreviousData })
}

export function useSaveCoupon(owner: CouponOwner) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: string | null; body: CouponRequest }) => (id ? updateCoupon(owner, id, body) : createCoupon(owner, body)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: couponAdminKeys.owner(owner) }),
  })
}

export function useSetCouponActive(owner: CouponOwner) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) => setCouponActive(owner, id, active),
    onSettled: () => queryClient.invalidateQueries({ queryKey: couponAdminKeys.owner(owner) }),
  })
}
