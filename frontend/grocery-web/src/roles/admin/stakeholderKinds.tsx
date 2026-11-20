import { partnerAdminApi, partnerKeys, type PartnerView } from '../../features/delivery/api'
import { AdminMenuPreview } from '../../features/menu/AdminMenuPreview'
import { VEHICLE_LABELS } from '../../features/delivery/schema'
import { restaurantAdminApi, restaurantKeys, type RestaurantView } from '../../features/restaurant/api'
import type { StakeholderKind } from '../../features/stakeholders/admin/kind'
import { vendorAdminApi, vendorKeys, type VendorView } from '../../features/vendor/api'
import { Badge } from '../../shared/components/ui'
import { formatAddress, humanizeToken } from '../../shared/utils/format'

function coordinates(address: { latitude: number | null; longitude: number | null } | null): string | null {
  if (!address || address.latitude === null || address.longitude === null) return null
  return `${address.latitude}, ${address.longitude}`
}

export const vendorKind: StakeholderKind<VendorView> = {
  slug: 'vendors',
  singular: 'vendor',
  plural: 'Vendors',
  api: vendorAdminApi,
  queryKey: vendorKeys.admin,
  managePermission: 'stakeholders.manage',
  searchPlaceholder: 'Search by business name, GSTIN or email',
  displayName: (vendor) => vendor.businessName ?? 'Unnamed vendor',
  summary: (vendor) => [vendor.contactEmail, vendor.address?.city].filter(Boolean).join(' · ') || '—',
  details: (vendor) => [
    { label: 'Business name', value: vendor.businessName },
    { label: 'Legal name', value: vendor.legalName },
    { label: 'GSTIN', value: vendor.gstin && <span className="font-mono">{vendor.gstin}</span> },
    { label: 'FSSAI licence', value: vendor.fssaiLicense && <span className="font-mono">{vendor.fssaiLicense}</span> },
    { label: 'Contact email', value: vendor.contactEmail },
    { label: 'Contact phone', value: vendor.contactPhone },
    { label: 'Address', value: formatAddress(vendor.address), wide: true },
    { label: 'Coordinates', value: coordinates(vendor.address) },
    { label: 'Owner user ID', value: <span className="font-mono text-xs">{vendor.ownerUserId}</span> },
  ],
}

export const restaurantKind: StakeholderKind<RestaurantView> = {
  slug: 'restaurants',
  singular: 'restaurant',
  plural: 'Restaurants',
  api: restaurantAdminApi,
  queryKey: restaurantKeys.admin,
  managePermission: 'stakeholders.manage',
  searchPlaceholder: 'Search by restaurant name, FSSAI or email',
  displayName: (restaurant) => restaurant.name ?? 'Unnamed restaurant',
  summary: (restaurant) =>
    [restaurant.cuisines?.slice(0, 3).map(humanizeToken).join(', '), restaurant.address?.city].filter(Boolean).join(' · ') ||
    '—',
  details: (restaurant) => [
    { label: 'Name', value: restaurant.name },
    {
      label: 'Cuisines',
      value:
        restaurant.cuisines && restaurant.cuisines.length > 0 ? (
          <span className="flex flex-wrap gap-1">
            {restaurant.cuisines.map((cuisine) => (
              <Badge key={cuisine} tone="green">
                {humanizeToken(cuisine)}
              </Badge>
            ))}
          </span>
        ) : null,
    },
    { label: 'Description', value: restaurant.description, wide: true },
    {
      label: 'Accepting orders',
      value: restaurant.acceptingOrders === undefined ? null : restaurant.acceptingOrders ? 'Yes' : 'No (paused by the owner)',
    },
    { label: 'FSSAI licence', value: restaurant.fssaiLicense && <span className="font-mono">{restaurant.fssaiLicense}</span> },
    { label: 'GSTIN', value: restaurant.gstin && <span className="font-mono">{restaurant.gstin}</span> },
    { label: 'Contact email', value: restaurant.contactEmail },
    { label: 'Contact phone', value: restaurant.contactPhone },
    { label: 'Address', value: formatAddress(restaurant.address), wide: true },
    { label: 'Coordinates', value: coordinates(restaurant.address) },
    {
      label: 'Opening hours',
      wide: true,
      value:
        restaurant.openingHours && restaurant.openingHours.length > 0 ? (
          <ul className="space-y-0.5">
            {restaurant.openingHours.map((slot) => (
              <li key={`${slot.dayOfWeek}-${slot.opensAt}`} className="flex gap-3">
                <span className="w-24 text-slate-500">{humanizeToken(slot.dayOfWeek)}</span>
                <span className="font-mono">
                  {slot.opensAt.slice(0, 5)}–{slot.closesAt.slice(0, 5)}
                </span>
              </li>
            ))}
          </ul>
        ) : null,
    },
    { label: 'Owner user ID', value: <span className="font-mono text-xs">{restaurant.ownerUserId}</span> },
  ],
  extraSection: { title: 'Menu', render: (restaurant) => <AdminMenuPreview restaurantId={restaurant.id} /> },
}

export const partnerKind: StakeholderKind<PartnerView> = {
  slug: 'delivery-partners',
  singular: 'delivery partner',
  plural: 'Delivery partners',
  api: partnerAdminApi,
  queryKey: partnerKeys.admin,
  // Delivery managers may decide on partners too (contract §5).
  managePermission: 'partners.manage',
  searchPlaceholder: 'Search by name, email, phone or city',
  displayName: (partner) => partner.fullName ?? partner.email,
  summary: (partner) =>
    [partner.city, partner.vehicleType ? VEHICLE_LABELS[partner.vehicleType] : null].filter(Boolean).join(' · ') || partner.email,
  details: (partner) => [
    { label: 'Full name', value: partner.fullName },
    { label: 'Email', value: partner.email },
    { label: 'Phone', value: partner.phone },
    { label: 'City', value: partner.city },
    { label: 'Vehicle', value: partner.vehicleType ? VEHICLE_LABELS[partner.vehicleType] : null },
    { label: 'Vehicle number', value: partner.vehicleNumber && <span className="font-mono">{partner.vehicleNumber}</span> },
    {
      label: 'Driving licence',
      value: partner.drivingLicenseNumber && <span className="font-mono">{partner.drivingLicenseNumber}</span>,
    },
    { label: 'User ID', value: <span className="font-mono text-xs">{partner.id}</span> },
  ],
}
