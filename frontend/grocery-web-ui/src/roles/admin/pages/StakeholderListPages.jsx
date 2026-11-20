import { StakeholderDirectory } from '../../../features/stakeholders/admin/StakeholderDirectory'
import { partnerKind, restaurantKind, vendorKind } from '../stakeholderKinds'

export function VendorsPage() {
  return (
    <StakeholderDirectory
      kind={vendorKind}
      title="Vendors"
      description="All grocery vendors: search, filter by status, suspend or reinstate."
    />
  )
}

export function RestaurantsPage() {
  return (
    <StakeholderDirectory
      kind={restaurantKind}
      title="Restaurants"
      description="All restaurants: search, filter by status, suspend or reinstate."
    />
  )
}

export function DeliveryPartnersPage() {
  return (
    <StakeholderDirectory
      kind={partnerKind}
      title="Delivery partners"
      description="The delivery fleet: search, filter by status, suspend or reinstate."
    />
  )
}
