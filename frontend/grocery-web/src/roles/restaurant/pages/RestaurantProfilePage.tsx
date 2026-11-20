import { RESTAURANT_NOT_FOUND } from '../../../features/restaurant/api'
import { useRestaurantProfile } from '../../../features/restaurant/hooks'
import { RestaurantOnboarding } from '../../../features/restaurant/RestaurantOnboarding'
import { ProfileGate } from '../../../shared/components/ProfileGate'
import { PageHeader } from '../../../shared/components/ui'

export function RestaurantProfilePage() {
  const profile = useRestaurantProfile()
  return (
    <div className="w-full">
      <PageHeader icon="cloche" title="Restaurant profile" description="Your restaurant details, opening hours and onboarding status." />
      <ProfileGate query={profile} notFoundCode={RESTAURANT_NOT_FOUND}>
        {(restaurant) => <RestaurantOnboarding profile={restaurant} />}
      </ProfileGate>
    </div>
  )
}
