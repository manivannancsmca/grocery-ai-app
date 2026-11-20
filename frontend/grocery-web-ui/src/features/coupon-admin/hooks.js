import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { couponAdminKeys, createCoupon, fetchCouponUsage, listCoupons, setCouponActive, updateCoupon } from './api'

/** @typedef {import('./api').CouponListParams} CouponListParams */
/** @typedef {import('./api').CouponOwner} CouponOwner */

/**
 * @param {CouponOwner} owner
 * @param {CouponListParams} params
 */
export function useCoupons(owner, params) {
  return useQuery({
    queryKey: couponAdminKeys.list(owner, params),
    queryFn: () => listCoupons(owner, params),
    placeholderData: keepPreviousData,
  })
}

/**
 * @param {CouponOwner} owner
 * @param {string} id
 * @param {number} page
 */
export function useCouponUsage(owner, id, page) {
  return useQuery({
    queryKey: couponAdminKeys.usage(owner, id, page),
    queryFn: () => fetchCouponUsage(owner, id, page),
    placeholderData: keepPreviousData,
  })
}

/** @param {CouponOwner} owner */
export function useSaveCoupon(owner) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }) => (id ? updateCoupon(owner, id, body) : createCoupon(owner, body)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: couponAdminKeys.owner(owner) }),
  })
}

/** @param {CouponOwner} owner */
export function useSetCouponActive(owner) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, active }) => setCouponActive(owner, id, active),
    onSettled: () => queryClient.invalidateQueries({ queryKey: couponAdminKeys.owner(owner) }),
  })
}
