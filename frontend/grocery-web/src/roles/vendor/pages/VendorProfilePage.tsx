import { VENDOR_NOT_FOUND } from '../../../features/vendor/api'
import { useVendorProfile } from '../../../features/vendor/hooks'
import { VendorOnboarding } from '../../../features/vendor/VendorOnboarding'
import { ProfileGate } from '../../../shared/components/ProfileGate'
import { PageHeader } from '../../../shared/components/ui'

export function VendorProfilePage() {
  const profile = useVendorProfile()
  return (
    <div className="w-full">
      <PageHeader icon="store" title="Business profile" description="Your verified business details and onboarding status." />
      <ProfileGate query={profile} notFoundCode={VENDOR_NOT_FOUND}>
        {(vendor) => <VendorOnboarding profile={vendor} />}
      </ProfileGate>
    </div>
  )
}
