import { useStakeholderProfile } from '../stakeholders/hooks'
import { PARTNER_NOT_FOUND, partnerApi, partnerKeys, type PartnerView } from './api'

/** The signed-in delivery partner's profile (partnerId = userId, so there is no extra token claim). */
export function usePartnerProfile() {
  return useStakeholderProfile<PartnerView>({
    queryKey: partnerKeys.mine,
    queryFn: partnerApi.fetchMine,
    notFoundCode: PARTNER_NOT_FOUND,
  })
}
