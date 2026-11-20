import { PARTNER_NOT_FOUND } from '../../../features/delivery/api'
import { usePartnerProfile } from '../../../features/delivery/hooks'
import { PartnerOnboarding } from '../../../features/delivery/PartnerOnboarding'
import { PartnerApp } from '../../../features/deliveries/PartnerApp'
import { PartnerAssistantCard } from '../../../features/deliveries/PartnerAssistantCard'
import { PartnerPerformance } from '../../../features/deliveries/PartnerPerformance'
import { ProfileGate } from '../../../shared/components/ProfileGate'
import { PageHeader } from '../../../shared/components/ui'

/**
 * Mobile-first. Until the partner is APPROVED the dashboard is the onboarding flow; afterwards it is the partner app
 * (Phase 9), "now" first: a banner with the Online switch and today's numbers, the work of the moment (go online,
 * offers, the active delivery), the assistant; then their performance for the chosen period.
 */
export function DeliveryDashboardPage() {
  const profile = usePartnerProfile()

  return (
    <ProfileGate query={profile} notFoundCode={PARTNER_NOT_FOUND}>
      {(partner) =>
        partner.status !== 'APPROVED' ? (
          <div className="w-full">
            <PageHeader icon="scooter" title="Become a delivery partner" description="A few details about you and your vehicle, then we review your profile." />
            <PartnerOnboarding profile={partner} />
          </div>
        ) : (
          <div className="space-y-6">
            <PartnerApp partner={partner} assistant={<PartnerAssistantCard />} />
            <PartnerPerformance />
          </div>
        )
      }
    </ProfileGate>
  )
}
