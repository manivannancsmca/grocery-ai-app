import { PARTNER_NOT_FOUND } from '../../../features/delivery/api'
import { usePartnerProfile } from '../../../features/delivery/hooks'
import { PartnerOnboarding } from '../../../features/delivery/PartnerOnboarding'
import { ProfileGate } from '../../../shared/components/ProfileGate'
import { PageHeader } from '../../../shared/components/ui'

export function DeliveryProfilePage() {
  const profile = usePartnerProfile()
  return (
    <div className="w-full">
      <PageHeader icon="user" title="Partner profile" description="Your details, vehicle and onboarding status." />
      <ProfileGate query={profile} notFoundCode={PARTNER_NOT_FOUND}>
        {(partner) => <PartnerOnboarding profile={partner} />}
      </ProfileGate>
    </div>
  )
}
