import { useQuery } from '@tanstack/react-query'
import { useCartAccess } from '../cart/hooks'
import { couponOfferKeys, fetchCouponOffers } from './api'

/**
 * The public offers of the cart's store (signed-in customers only).
 * @param {string | null} storeId
 */
export function useCouponOffers(storeId) {
  const access = useCartAccess()
  return useQuery({
    queryKey: couponOfferKeys.forStore(storeId ?? ''),
    queryFn: () => fetchCouponOffers(storeId ?? ''),
    enabled: access === 'customer' && Boolean(storeId),
    staleTime: 60_000,
  })
}
