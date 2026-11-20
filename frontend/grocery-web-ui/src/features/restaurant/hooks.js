import { useApprovalClaimSync, useStakeholderProfile } from '../stakeholders/hooks'
import { RESTAURANT_NOT_FOUND, restaurantApi, restaurantKeys } from './api'

/** The signed-in owner's restaurant; on approval it also refreshes the token to pick up `restaurant_id`. */
export function useRestaurantProfile() {
  const query = useStakeholderProfile({
    queryKey: restaurantKeys.mine,
    queryFn: restaurantApi.fetchMine,
    notFoundCode: RESTAURANT_NOT_FOUND,
  })
  useApprovalClaimSync(query.data, 'restaurantId')
  return query
}
