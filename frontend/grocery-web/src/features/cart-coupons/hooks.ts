import { useQuery } from '@tanstack/react-query'
import { useCartAccess } from '../cart/hooks'
import { couponOfferKeys, fetchCouponOffers } from './api'

/** The public offers of the cart's store (signed-in customers only). */
export function useCouponOffers(storeId: string | null) {
  const access = useCartAccess()
  return useQuery({
    queryKey: couponOfferKeys.forStore(storeId ?? ''),
    queryFn: () => fetchCouponOffers(storeId ?? ''),
    enabled: access === 'customer' && Boolean(storeId),
    staleTime: 60_000,
  })
}
